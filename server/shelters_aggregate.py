"""
Shelter data ETL — run this offline (cron / GitHub Action), NOT from the app.

Pulls each city's source, normalizes into the shared schema (see schema.md),
and writes one JSON file per city to ./output/. Those files are what you
host and what the app fetches (with ETag/If-Modified-Since) or bundles.

Add a new city by writing one `fetch_<city>()` function that returns a list
of dicts already matching the normalized schema, then register it in SOURCES.
Keep each fetcher small and defensive — government data sources go stale,
change field names, or return malformed rows without warning. A fetcher
should skip a bad row and keep going, not crash the whole run.
"""

import csv
import io
import json
import os
import re
import sys
from datetime import datetime, timezone

import requests

try:
    import openpyxl
except ImportError:
    openpyxl = None

# Absolute: the script writes next to itself no matter where it's invoked from.
OUTPUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "output")

CKAN_SEARCH_URL = "https://data.gov.ua/api/3/action/package_search"

_UA_LATIN = {
    "а": "a", "б": "b", "в": "v", "г": "h", "ґ": "g", "д": "d", "е": "e",
    "є": "ye", "ж": "zh", "з": "z", "и": "y", "і": "i", "ї": "yi",
    "й": "y", "к": "k", "л": "l", "м": "m", "н": "n", "о": "o",
    "п": "p", "р": "r", "с": "s", "т": "t", "у": "u", "ф": "f",
    "х": "kh", "ц": "ts", "ч": "ch", "ш": "sh", "щ": "shch",
    "ь": "", "ю": "yu", "я": "ya",
}


def transliterate(text: str) -> str:
    """Ukrainian -> latin slug piece. Unknown Cyrillic is dropped so keys
    stay ASCII-safe for filenames."""
    ua_extra = {"э": "e", "ы": "y", "ъ": "", "ё": "yo"}
    out = []
    for ch in (text or "").lower():
        if "\u0400" <= ch <= "\u04ff":
            out.append(_UA_LATIN.get(ch, ua_extra.get(ch, "")))
        elif ch.isascii() and ch.isalnum():
            out.append(ch)
        elif ch in " -_.":
            out.append("-")
    slug = re.sub(r"-+", "-", "".join(out)).strip("-")
    return slug


# Council-type words AND dataset-title boilerplate stripped when deriving a
# city key — without the boilerplate every standard-titled feed would slug
# to "dani" (Дані про розташування...) and collide.
_SLUG_STOPWORDS = {
    "міська", "місто", "сільська", "селищна", "село", "селище", "рада",
    "район", "району", "область", "області", "обласна", "державна",
    "адміністрація", "територіальна", "громада", "громади", "комунальної",
    "власності", "виконавчі", "органи", "департамент", "управління",
    "захисту", "цивільного", "мтг", "тг", "отг",
    "дані", "про", "розташування", "захисних", "захисні", "споруд",
    "споруди", "перелік", "обєктів", "адреси", "адрес", "набір",
    "містить", "інформацію", "місцезнаходження", "укриттів", "укриття",
    "найпростіших", "подвійного", "призначення", "території",
    "протирадіаційних", "готовності", "обмеженої", "адміністративні",
    "цз", "на", "та", "у", "в", "з", "для", "населення", "mtg",
}

# Auto-slug -> short canonical key for the cities that matter.
CITY_ALIASES = {
    "lvivska": "lviv",
    "kyivska": "kyiv",
    "zhytomyrska": "zhytomyr",
    "rivnenska": "rivne",
    "kremenchutska": "kremenchuk",
    "uzhhorodska": "uzhhorod",
}


def _first_content_token(text: str) -> str:
    """First token (original case) that isn't council/title boilerplate."""
    for m in re.finditer(r"[А-ЯІЇЄҐа-яіїєґA-Za-z']+", text or ""):
        if m.group(0).lower() not in _SLUG_STOPWORDS:
            return m.group(0)
    return ""


def slug_city_key(title: str, org: str) -> tuple[str, str, bool]:
    """
    Derive (key, oblast, is_oblast_aggregate) from a dataset's title/org.
    Council-published feeds key off the org ("Бучанська міська рада");
    oblast-published feeds key off the title (the hromada hides there).
    Villages get transliterated slugs; collisions are suffixed by the caller.
    """
    text = f"{title or ''} {org or ''}"
    is_oblast = "обласна" in text or "облдержадміністрація" in text.replace(" ", "")
    token = (_first_content_token("" if is_oblast else (org or ""))
             or _first_content_token(title or "")
             or _first_content_token(org or ""))
    base = transliterate(token) if token else "unknown"
    key = CITY_ALIASES.get(base, base)
    if is_oblast:
        key = f"oblast-{key}"
    oblast = ""
    m = re.search(
        r"([А-ЯІЇЄҐ][а-яіїєґ']+(?:ська|цька|дська|нська|вська) област[ьіаох])",
        text)
    if m:
        oblast = m.group(1)
    return key, oblast, is_oblast


# Canonical column roles -> known header spellings (lowercased, stripped).
# Built from headers actually observed across data.gov.ua shelter feeds.
_ROLE_SYNONYMS = {
    "uid": {"uid", "id", "shelterid", "shelter_id", "objectid", "guid",
            "globalid", "object_id", "shelterld"},
    "lat": {"lat", "latitude", "shelterlat", "shelter_lat", "y", "широта"},
    "lng": {"lon", "lng", "long", "longitude", "shelterlon", "shelter_lon",
            "x", "довгота"},
    "address": {"address", "addr", "adres",
                "addressthoroughfare", "address_thoroughfare",
                "addresslocatordesignator", "address_locator_designator",
                "addresslocatorbuilding", "address_locator_building",
                "addressdescription", "address_description",
                "addresspostcode", "address_postcode",
                "street_type", "street_name", "housenumber", "street",
                "house", "адреса", "адресаукриття"},
    "latlng": {"coordinatesshelter", "coordinates", "coord", "coords",
               "координати"},
    "type": {"type", "kind", "status", "view", "вид", "видспоруди",
             "object", "typeshelter", "shelterтype"},
    "name": {"name", "title", "type_building", "назва"},
    "owner": {"owner", "balanceholder", "balanceholdername", "holder",
              "балансоутримувач", "власник"},
    "capacity": {"capacity", "місткість"},
    "updated": {"last_edited_date", "updated", "updated_at"},
}

# Preferred order when composing multi-column address / type signals.
_ADDRESS_ORDER = [
    "addressthoroughfare", "address_thoroughfare",
    "addresslocatordesignator", "address_locator_designator",
    "addresslocatorbuilding", "address_locator_building",
    "addressdescription", "address_description",
    "address", "addr", "adres", "адреса", "адресаукриття",
    "street_type", "street_name", "housenumber", "street", "house",
    "addresspostcode", "address_postcode",
]


def _norm_col(col: str) -> str:
    return re.sub(r"[\s_\-]+", "", (col or "").lower())


def auto_field_map(columns: list[str]) -> tuple[dict, list[str]]:
    """
    Map raw header names onto fetch_csv_dataset field_map roles.
    Returns (field_map, warnings). Multi-column roles come back as lists.
    A dataset is ingestible when lat+lng resolve; everything else degrades.
    """
    normed = [(_norm_col(c), c) for c in columns]
    used: set[str] = set()
    fmap: dict = {}
    warnings: list[str] = []

    def claim(role: str, synonyms: set[str]) -> list[str]:
        hits = [orig for norm, orig in normed
                if norm in synonyms and orig not in used]
        for h in hits:
            used.add(h)
        return hits

    for role in ("uid", "lat", "lng"):
        hits = claim(role, _ROLE_SYNONYMS[role])
        if len(hits) == 1:
            fmap[role] = hits[0]
        elif len(hits) > 1:
            fmap[role] = hits[0]
            warnings.append(f"{role}: ambiguous {hits}, took {hits[0]}")

    addr_hits = claim("address", _ROLE_SYNONYMS["address"])
    if addr_hits:
        order = {_norm_col(c): i for i, c in enumerate(_ADDRESS_ORDER)}
        addr_hits.sort(key=lambda c: order.get(_norm_col(c), 999))
        fmap["address"] = addr_hits if len(addr_hits) > 1 else addr_hits[0]

    for role in ("type", "name", "owner", "capacity", "updated", "latlng"):
        hits = claim(role, _ROLE_SYNONYMS[role])
        if hits:
            fmap[role] = hits if len(hits) > 1 else hits[0]

    if ("lat" not in fmap or "lng" not in fmap) and "latlng" not in fmap:
        warnings.append("no lat/lng columns — not ingestible")
    return fmap, warnings


# Per-dataset overrides, keyed by CKAN package id or auto slug (slugs are
# readable; uids survive renames — either works, uid wins on conflict).
# Keys: skip (reason) | field_map | delimiter | city | city_name | oblast.
OVERRIDES: dict[str, dict] = {
    # Address register without coordinates — ungeocodeable, nothing to ingest.
    "oblast-shchodo": {"skip": "no coords, district/community/city/street only"},
    "oblast-ternopilskiy": {"skip": "register-style rows, no coords or address"},
}


def discover_shelter_datasets() -> list[dict]:
    """
    data.gov.ua is a CKAN portal, so it exposes the standard CKAN
    package_search API. This finds every dataset tagged "укриття" instead
    of relying on a hand-maintained list of city URLs, which will always
    drift (new councils publish, old ones go stale or get pulled).

    Returns a list of {"title", "organization", "resources": [{"url",
    "format"}]} — one entry per dataset. Run this occasionally (e.g. as
    a first step before the actual per-city fetch) to see what's newly
    available or what changed, rather than assuming SOURCES is complete.
    """
    resp = requests.get(
        CKAN_SEARCH_URL,
        params={"fq": "tags:укриття", "rows": 200},
        timeout=30,
    )
    resp.raise_for_status()
    payload = resp.json()
    if not payload.get("success"):
        raise RuntimeError(f"CKAN search failed: {payload}")

    datasets = []
    for pkg in payload["result"]["results"]:
        datasets.append({
            "uid": pkg.get("id"),
            "title": pkg.get("title"),
            "organization": (pkg.get("organization") or {}).get("title"),
            "update_frequency": pkg.get("update_frequency"),
            "resources": [
                {"url": r.get("url"), "format": r.get("format")}
                for r in pkg.get("resources", [])
            ],
        })
    return datasets

KYIV_ARCGIS_URL = (
    "https://gisserver.kyivcity.gov.ua/mayno/rest/services/KYIV_API/"
    "%D0%9A%D0%B8%D1%97%D0%B2_%D0%A6%D0%B8%D1%84%D1%80%D0%BE%D0%B2%D0%B8%D0%B9/"
    "MapServer/0/query"
)


def ms_to_date(ms) -> str | None:
    """Esri date (ms epoch) -> YYYY-MM-DD, None when missing/garbage."""
    try:
        return datetime.fromtimestamp(int(ms) / 1000, tz=timezone.utc).date().isoformat()
    except (TypeError, ValueError, OverflowError, OSError):
        return None


def normalize_type(raw: str) -> str:
    """Map whatever a source calls a shelter type onto the fixed enum."""
    raw = (raw or "").strip().lower()
    if "підвал" in raw or "basement" in raw:
        return "basement"
    if "паркінг" in raw or "parking" in raw:
        return "underground_parking"
    if "метро" in raw or "metro" in raw:
        return "metro"
    if "пру" in raw or "протирад" in raw:
        return "pru"
    if "сховище" in raw or "bunker" in raw:
        return "bunker"
    return "other"


def fetch_kyiv() -> list[dict]:
    """
    Kyiv's ArcGIS FeatureServer. Paginates with resultOffset until
    exceededTransferLimit goes false — a single query is capped by the
    server (2000 rows) and would silently truncate the city.
    Field keys verified against a live response: lowercase names, with
    `kind` (physical kind, e.g. "Підвал ОЗ") as the type discriminator
    and `title`/`type_building` as the name source.
    """
    shelters = []
    offset = 0
    page_size = 1000
    while True:
        resp = requests.get(
            KYIV_ARCGIS_URL,
            params={
                "where": "1=1",
                "outFields": "*",
                "outSR": "4326",
                "f": "json",
                "orderByFields": "objectid",
                "resultOffset": offset,
                "resultRecordCount": page_size,
            },
            timeout=30,
        )
        resp.raise_for_status()
        data = resp.json()
        features = data.get("features", [])

        for feature in features:
            attrs = feature.get("attributes", {})
            geom = feature.get("geometry", {})
            lat, lng = geom.get("y"), geom.get("x")
            if lat is None or lng is None:
                continue  # skip rows with no coordinates rather than crash
            if attrs.get("actual") not in (None, 1):
                continue  # inactive/closed record

            oid = attrs.get("objectid")
            guid = attrs.get("guid") or attrs.get("globalid")
            shelters.append({
                "id": f"kyiv-{guid or oid}",
                "city": "Kyiv",
                "oblast": "Kyiv",
                "source": "kyiv-arcgis",
                "source_id": str(oid),
                "lat": lat,
                "lng": lng,
                "address": attrs.get("address") or attrs.get("address_old"),
                "name": attrs.get("title") or attrs.get("type_building"),
                "type": normalize_type(attrs.get("kind") or attrs.get("type")),
                "capacity": None,
                "owner": attrs.get("owner"),
                "updated_at": ms_to_date(attrs.get("last_edited_date")),
            })

        if not features or not data.get("exceededTransferLimit"):
            break
        offset += page_size
        if offset > 100_000:  # sanity cap, ~100 pages
            print("[warn] kyiv pagination cap hit, stopping")
            break
    return shelters


def _cell(row: dict, spec) -> str | None:
    """
    Read one normalized field from a CSV row. spec is a column name or a
    list of them (joined with ", " — for split addresses like thoroughfare
    + number, or combined type signals like object + status). Literal
    "null"/empty cells count as missing.
    """
    cols = [spec] if isinstance(spec, str) else (spec or [])
    parts = []
    for col in cols:
        v = row.get(col)
        if v is None:
            continue
        v = v if isinstance(v, str) else str(v)
        v = v.strip()
        if v and v.lower() != "null":
            parts.append(v)
    return ", ".join(parts) or None


_COORD_NUM = re.compile(r"-?\d+(?:[.,]\d+)?")


def _parse_coord(raw: str | None) -> float | None:
    """
    Float coords, tolerating comma decimals, trailing punctuation and
    annotated values councils publish ("50.60329 північної широти").
    Takes the first number in the cell — the field is numeric by contract.
    """
    if raw is None:
        return None
    raw = raw.strip().rstrip(",;")
    try:
        return float(raw)
    except ValueError:
        pass
    m = _COORD_NUM.search(raw)
    if not m:
        return None
    try:
        return float(m.group(0).replace(",", "."))
    except ValueError:
        return None


def _ukraine_bbox(lat: float, lng: float) -> bool:
    return 44.0 <= lat <= 53.0 and 22.0 <= lng <= 41.0


def _parse_updated(raw: str | None) -> str | None:
    """Esri ms-epoch or ISO date prefix -> YYYY-MM-DD, else None."""
    if not raw:
        return None
    raw = raw.strip()
    if re.fullmatch(r"\d{10,13}", raw):
        v = int(raw)
        return ms_to_date(v if len(raw) > 10 else v * 1000)
    m = re.search(r"\d{4}-\d{2}-\d{2}", raw)
    return m.group(0) if m else None


def _normalize_row(row: dict, field_map: dict, city: str, oblast: str,
                   source_name: str, i: int) -> tuple[dict | None, str | None]:
    """
    One raw row -> schema dict. Shared by the pinned CSV fetcher and the
    auto-ingest path so both apply identical rules (uid ids, swap healing,
    null-cell handling). Returns (shelter|None, drop_reason|None).
    """
    lat = _parse_coord(_cell(row, field_map.get("lat", "")))
    lng = _parse_coord(_cell(row, field_map.get("lng", "")))
    if lat is None or lng is None:
        # Combined "lat,lon" in a single column (coordinatesShelter et al).
        pair = _cell(row, field_map.get("latlng", "")) or ""
        parts = re.split(r"[;,]", pair)
        if len(parts) >= 2:
            lat = _parse_coord(parts[0])
            lng = _parse_coord(parts[1])
    if lat is None or lng is None:
        return None, "no-coords"
    if not _ukraine_bbox(lat, lng) and _ukraine_bbox(lng, lat):
        lat, lng = lng, lat  # source swapped the columns, heal it

    uid = _cell(row, field_map.get("uid", ""))
    return {
        "id": f"{source_name}-{uid or i}",
        "city": city,
        "oblast": oblast,
        "source": source_name,
        "source_id": uid or str(i),
        "lat": lat,
        "lng": lng,
        "address": _cell(row, field_map.get("address", "")),
        "name": _cell(row, field_map.get("name", "")),
        "type": normalize_type(_cell(row, field_map.get("type", "")) or ""),
        "capacity": _cell(row, field_map.get("capacity", "")),
        "owner": _cell(row, field_map.get("owner", "")),
        "updated_at": _parse_updated(_cell(row, field_map.get("updated", ""))),
    }, None


def fetch_csv_dataset(url: str, city: str, oblast: str, source_name: str,
                       field_map: dict, delimiter: str = ",") -> list[dict]:
    """
    Generic fetcher for the many data.gov.ua CSV datasets.
    field_map maps our normalized keys -> that dataset's actual column names,
    e.g. {"lat": "lat", "lng": "lon", "address": ["addressThoroughfare",
    "addressLocatorDesignator"], "uid": "uid"}.
    Inspect each dataset's CSV header once and fill this in per source.
    Encoding falls back utf-8-sig -> cp1251 (both occur in the wild).
    A stable `uid` column is used for ids when present, otherwise the row
    index (unstable across source reorders — flagged, not fixed here).
    Rows whose lat/lng are swapped (seen live in Rivne's feed) are healed
    when the swap lands inside Ukraine and the original doesn't.
    """
    resp = requests.get(url, timeout=60)
    resp.raise_for_status()
    text = None
    for enc in ("utf-8-sig", "cp1251"):
        try:
            text = resp.content.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    if text is None:
        text = resp.content.decode("utf-8", errors="replace")
    reader = csv.DictReader(io.StringIO(text), delimiter=delimiter)

    shelters = []
    for i, row in enumerate(reader):
        shelter, _ = _normalize_row(row, field_map, city, oblast,
                                    source_name, i)
        if shelter is not None:
            shelters.append(shelter)
    return shelters


class _NeedXlsx(Exception):
    """Raised when a resource needs openpyxl, which isn't installed."""


def _decode_text(blob: bytes) -> tuple[str, str]:
    for enc in ("utf-8-sig", "cp1251"):
        try:
            return blob.decode(enc), enc
        except UnicodeDecodeError:
            continue
    return blob.decode("utf-8", errors="replace"), "utf-8-replace"


def _dispatch_json(payload) -> tuple[str, list[str], list[dict], str]:
    """Route a parsed JSON payload to row dicts. Returns (kind, columns, rows, note)."""
    if isinstance(payload, dict) and payload.get("type") == "FeatureCollection":
        feats = payload.get("features", [])
        rows = []
        for f in feats:
            props = dict((f.get("properties") or {}))
            coords = ((f.get("geometry") or {}).get("coordinates") or [])
            if len(coords) >= 2:
                props["lat"], props["lng"] = coords[1], coords[0]
            rows.append(props)
        cols = list(dict.fromkeys(k for r in rows for k in r.keys()))
        return "geojson", cols, rows, f"{len(rows)} features"
    if isinstance(payload, dict) and isinstance(payload.get("features"), list):
        feats = payload["features"]
        if feats and isinstance(feats[0], dict) and "attributes" in feats[0]:
            rows = []
            for f in feats:
                base = dict(f.get("attributes") or {})
                geom = f.get("geometry") or {}
                if isinstance(geom.get("x"), (int, float)) and isinstance(geom.get("y"), (int, float)):
                    for k in list(base.keys()):
                        if _norm_col(k) in _ROLE_SYNONYMS["lat"] | _ROLE_SYNONYMS["lng"]:
                            del base[k]
                    base["lat"], base["lng"] = geom["y"], geom["x"]
                rows.append(base)
            cols = list(dict.fromkeys(k for r in rows for k in r.keys()))
            return "esri", cols, rows, f"{len(rows)} features"
    if isinstance(payload, dict):
        for key in ("records", "result", "data", "items"):
            v = payload.get(key)
            if isinstance(v, list) and v and isinstance(v[0], dict):
                cols = list(dict.fromkeys(k for r in v for k in r.keys()))
                return "json", cols, v, f"key={key}"
        res = payload.get("result")
        if isinstance(res, dict):
            for key in ("records", "items"):
                v = res.get(key)
                if isinstance(v, list) and v and isinstance(v[0], dict):
                    cols = list(dict.fromkeys(k for r in v for k in r.keys()))
                    return "json", cols, v, f"key=result.{key}"
    if isinstance(payload, list) and payload and isinstance(payload[0], dict):
        cols = list(dict.fromkeys(k for r in payload for k in r.keys()))
        return "json", cols, payload, f"{len(payload)} rows"
    raise ValueError("JSON has no recognizable tabular shape")


def fetch_table(url: str, pinned_delimiter: str | None = None
                ) -> tuple[str, list[str], list[dict], str]:
    """
    Download one resource and sniff it into row dicts — never trusts the
    CKAN format label (typos like `josn`/`.сsv` occur live).
    Returns (kind, columns, rows, dialect_note).
    """
    resp = requests.get(url, timeout=45)
    resp.raise_for_status()
    blob = resp.content
    if not blob.strip():
        raise ValueError("empty body")
    if blob[:4] == b"PK\x03\x04":
        if openpyxl is None:
            raise _NeedXlsx("pip install openpyxl for XLSX resources")
        wb = openpyxl.load_workbook(io.BytesIO(blob), read_only=True,
                                    data_only=True)
        ws = wb.active
        gen = ws.iter_rows(values_only=True)
        header = [(str(c).strip() if c is not None else "") for c in next(gen, [])]
        if not any(header):
            raise ValueError("first sheet has no header row")
        rows = []
        for vals in gen:
            if all(v is None or str(v).strip() == "" for v in vals):
                continue
            rows.append({
                h: (str(v).strip() if v is not None else "")
                for h, v in zip(header, vals) if h
            })
        return "xlsx", header, rows, f"sheet={ws.title}"
    if blob.lstrip()[:1] in (b"{", b"["):
        for enc in ("utf-8-sig", "cp1251"):
            try:
                return _dispatch_json(json.loads(blob.decode(enc)))
            except (ValueError, UnicodeDecodeError, AttributeError):
                continue
        # fall through to CSV attempt
    text, enc = _decode_text(blob)
    if text.lstrip()[:1] == "<":
        raise ValueError("HTML error page, not data")
    sample = "\n".join(text.splitlines()[:5])
    if pinned_delimiter is not None:
        delim = pinned_delimiter
    else:
        try:
            delim = csv.Sniffer().sniff(sample, delimiters=";,|\t").delimiter
        except csv.Error:
            delim = ","
    reader = csv.DictReader(io.StringIO(text), delimiter=delim)
    if not reader.fieldnames:
        raise ValueError("no header row")
    rows = [dict(r) for r in reader]
    return "csv", reader.fieldnames, rows, f"delim={delim!r} enc={enc}"


def _candidate_resources(dataset: dict) -> list[tuple[str, str]]:
    """Unique download urls, CSV first (most predictable), then the rest."""
    seen: set[str] = set()
    cands: list[tuple[str, str]] = []

    def rank(fmt: str) -> int:
        f = (fmt or "").strip().lower()
        if f in ("csv", ".csv", ".сsv", "сsv"):
            return 0
        if f in ("json", "josn"):
            return 1
        if f == "geojson":
            return 2
        if f in ("xls", "xlsx"):
            return 3
        return 9

    for r in sorted(dataset.get("resources", []),
                    key=lambda r: rank(r.get("format"))):
        url = r.get("url")
        if url and url not in seen:
            seen.add(url)
            cands.append((url, (r.get("format") or "?").strip()))
    return cands


def ingest_dataset(dataset: dict, city: str, oblast: str, source_name: str,
                   pinned: dict | None = None) -> tuple[list[dict], dict]:
    """
    Ingest one CKAN dataset: try each usable resource until one yields
    coord-bearing rows. Never raises on bad data — reports it.
    Returns (shelters, report{tried, flags, dropped}).
    """
    pinned = pinned or {}
    report: dict = {"tried": [], "flags": [], "dropped": {}}
    cands = _candidate_resources(dataset)
    if not cands:
        report["flags"].append("NO_USABLE_RESOURCE")
        return [], report
    for url, fmt in cands:
        attempt: dict = {"url": url, "format": fmt}
        try:
            kind, columns, rows, dialect = fetch_table(
                url, pinned.get("delimiter"))
        except _NeedXlsx as e:
            attempt["result"] = f"NEED_XLSX: {e}"
            report["tried"].append(attempt)
            report["flags"].append("NEED_XLSX")
            continue
        except Exception as e:
            attempt["result"] = f"{type(e).__name__}: {e}"[:160]
            report["tried"].append(attempt)
            continue
        attempt["parsed"] = f"{kind} {dialect}, {len(rows)} raw rows"
        attempt["columns"] = [str(c)[:40] for c in columns[:12]]
        fmap = pinned.get("field_map")
        if fmap is None:
            fmap, warns = auto_field_map(columns)
            attempt["automap"] = True
            attempt["warnings"] = warns
            if (("lat" not in fmap or "lng" not in fmap)
                    and "latlng" not in fmap):
                attempt["result"] = "no lat/lng columns"
                report["tried"].append(attempt)
                continue
        else:
            attempt["automap"] = False
        attempt["roles"] = {k: (v if isinstance(v, str) else f"{len(v)}cols")
                            for k, v in fmap.items()}
        if rows:
            latc = fmap.get("lat") or fmap.get("latlng") or ""
            lngc = fmap.get("lng", "")
            if isinstance(latc, list):
                latc = latc[0]
            attempt["sample"] = (
                f"{str(rows[0].get(latc))[:40]} / "
                f"{str(rows[0].get(lngc))[:40]}")
        kept: list[dict] = []
        for i, row in enumerate(rows):
            try:
                shelter, reason = _normalize_row(row, fmap, city, oblast,
                                                source_name, i)
            except Exception as e:
                reason = f"row-error:{type(e).__name__}"
                shelter = None
            if shelter is not None:
                kept.append(shelter)
            elif reason:
                report["dropped"][reason] = report["dropped"].get(reason, 0) + 1
        attempt["result"] = f"{len(kept)} kept"
        report["tried"].append(attempt)
        if kept:
            report["format"] = kind
            return kept, report
    report["flags"].append("ZERO_ROWS")
    return [], report


# Register one entry per city source here. Header shapes verified live
# 2026-09-19 — recheck a header before trusting a source that suddenly
# returns 0 rows (councils rename columns without warning).
# Skipped with reasons: uzhhorod-2 (resource 404s), lviv data-dictionary
# CSV (column docs, not shelters), kremenchuk PRU-addresses (no coords),
# kropyvnytskyi (XLSX-only, no XLSX fetcher yet), odesa city (no dataset
# published — legacy app bundle stays until one appears).
SOURCES = {
    "kyiv": fetch_kyiv,
    "zhytomyr": lambda: fetch_csv_dataset(
        url="https://data.gov.ua/dataset/a96e3962-e792-415f-a5e9-9b2e1b2da636/resource/59625259-5481-4b2b-8cdb-3a53b653d529/download/shelters.csv",
        city="Zhytomyr", oblast="Zhytomyr", source_name="zhytomyr-datagovua",
        field_map={"lat": "lat", "lng": "lon", "uid": "uid",
                   "address": ["addressThoroughfare",
                               "addressLocatorDesignator",
                               "addressLocatorBuilding"],
                   "type": "type", "owner": "balanceHolderName"},
    ),
    "rivne": lambda: fetch_csv_dataset(
        url="https://data.gov.ua/dataset/36c7d727-bb6f-40db-b68d-a23dbec29e24/resource/a28ddbc7-61f5-4e58-9897-9c066568d51f/download/shelters.csv",
        city="Rivne", oblast="Rivne", source_name="rivne-datagovua",
        field_map={"lat": "lat", "lng": "lon", "uid": "uid",
                   "address": ["addressThoroughfare",
                               "addressLocatorDesignator",
                               "addressLocatorBuilding"],
                   "type": "type", "owner": "balanceHolderName"},
    ),
    "kremenchuk": lambda: fetch_csv_dataset(
        url="https://data.gov.ua/dataset/9615dd23-ebd0-4859-8a8b-84f888cf9082/resource/0e89e9cf-384c-486e-b6a1-330a95142264/download/71.csv",
        city="Kremenchuk", oblast="Poltava", source_name="kremenchuk-datagovua",
        field_map={"lat": "lat", "lng": "lon", "uid": "id",
                   "address": ["addressThoroughfare",
                               "addressLocatorDesignator",
                               "addressLocatorBuilding"],
                   "type": "type", "owner": "balanceHolderName"},
    ),
    "uzhhorod": lambda: fetch_csv_dataset(
        url="https://data.rada-uzhgorod.gov.ua/dataset/6520aaa9-c4f7-4410-915f-a950a5b58dea/resource/df791b3b-7b5d-4017-9120-e357b91635d3/download/shelters.csv",
        city="Uzhhorod", oblast="Zakarpattia", source_name="uzhhorod-datagovua",
        field_map={"lat": "shelterLat", "lng": "shelterLon", "uid": "shelterId",
                   "address": ["addressThoroughfare",
                               "addressLocatorDesignator",
                               "addressLocatorBuilding"],
                   "type": "shelterType", "owner": "balanceHolder"},
        delimiter=";",
    ),
    "lviv": lambda: fetch_csv_dataset(
        url="https://opendata.city-adm.lviv.ua/dataset/513062e9-7962-4bc6-b973-970092eb9521/resource/ea0a9269-c05d-40bd-81f5-0cafb3e3f476/download/sporudy_podviinoho_pryznachennia_26062024.csv",
        city="Lviv", oblast="Lviv", source_name="lviv-datagovua",
        # x=lon, y=lat in this file — mapped, not swapped. No uid column,
        # so ids fall back to row index (unstable if the source reorders).
        field_map={"lat": "y", "lng": "x",
                   "address": ["street_type", "street_name", "housenumber"],
                   "type": ["object", "status"],
                   "capacity": "capacity", "owner": "holder"},
        delimiter=";",
    ),
}


# Resource URL prefixes owned by the pinned SOURCES fetchers above — the
# auto-ingest loop skips any dataset whose download URL starts with one of
# these, so the same feed is never ingested twice (pinned + auto).
PINNED_URLS = (
    "https://gisserver.kyivcity.gov.ua/",
    "https://data.gov.ua/dataset/a96e3962-e792-415f-a5e9-9b2e1b2da636/",
    "https://data.gov.ua/dataset/36c7d727-bb6f-40db-b68d-a23dbec29e24/",
    "https://data.gov.ua/dataset/9615dd23-ebd0-4859-8a8b-84f888cf9082/",
    "https://data.rada-uzhgorod.gov.ua/dataset/6520aaa9-c4f7-4410-915f-a950a5b58dea/",
    "https://opendata.city-adm.lviv.ua/dataset/513062e9-7962-4bc6-b973-970092eb9521/resource/ea0a9269",
)


def _disk_count(path: str) -> int:
    """Envelope count of an existing output file (head-sniff, no full parse)."""
    try:
        with open(path, encoding="utf-8") as f:
            m = re.search(r'"count":\s*(\d+)', f.read(400))
        return int(m.group(1)) if m else 0
    except (OSError, ValueError):
        return 0


def _write_city_file(city_key: str, shelters: list[dict],
                     generated_at: str) -> tuple[str, int]:
    out_path = os.path.join(OUTPUT_DIR, f"{city_key}.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump({
            "generated_at": generated_at,
            "city": city_key,
            "count": len(shelters),
            "shelters": shelters,
        }, f, ensure_ascii=False, indent=2)
    return out_path, os.path.getsize(out_path)


def _display_name(title: str, org: str, is_oblast: bool) -> str:
    """Raw-layer display name — same priority as the key derivation."""
    text = f"{title or ''} {org or ''}"
    oblast_pub = is_oblast or "обласна" in text
    return (_first_content_token("" if oblast_pub else (org or ""))
            or _first_content_token(title or "")
            or _first_content_token(org or "")
            or "Unknown")


def main(argv=None):
    # Windows consoles default to a non-UTF8 codepage, which crashes printing
    # Ukrainian dataset titles — decode-safe stdout instead.
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    args = list(sys.argv[1:] if argv is None else argv)
    only: str | None = None
    list_only = False
    skip_existing = False
    for a in args:
        if a == "--list":
            list_only = True
        elif a == "--skip-existing":
            skip_existing = True
        elif a.startswith("--only="):
            only = a.split("=", 1)[1].lower()
        else:
            print(f"unknown arg {a}; usage: [--only=<substr> --list --skip-existing]")
            return 1
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    generated_at = datetime.now(timezone.utc).isoformat()

    def want(key: str, title: str = "") -> bool:
        return only is None or only in key.lower() or only in title.lower()

    coverage: list[tuple[str, int, int, str, str]] = []
    _last_reports: dict[str, dict] = {}

    def run_pinned():
        for city_key, fetcher in SOURCES.items():
            if not want(city_key):
                continue
            out_path = os.path.join(OUTPUT_DIR, f"{city_key}.json")
            if skip_existing and os.path.exists(out_path):
                coverage.append((city_key, _disk_count(out_path),
                                 os.path.getsize(out_path),
                                 "pinned", "SKIP_EXISTING"))
                continue
            try:
                shelters = fetcher()
            except Exception as e:
                print(f"[skip] {city_key} failed: {e}")
                coverage.append((city_key, 0, 0, "pinned",
                                 f"FAIL:{type(e).__name__}"))
                continue
            if not shelters:
                print(f"[empty] {city_key}: no rows, file left untouched")
                coverage.append((city_key, 0, 0, "pinned", "EMPTY"))
                continue
            _, size = _write_city_file(city_key, shelters, generated_at)
            print(f"[ok] {city_key}: {len(shelters)} shelters -> {out_path}")
            coverage.append((city_key, len(shelters), size, "pinned", ""))

    try:
        datasets = discover_shelter_datasets()
        print(f"[discover] {len(datasets)} datasets tagged 'укриття'")
    except Exception as e:
        print(f"[discover] live failed ({e}), trying cache...")
        cache_candidates = [
            os.environ.get("DISCOVERY_CACHE", ""),
            os.path.join(os.environ.get("TEMP", ""), "opencode",
                         "discovery.json"),
            os.path.join(os.path.dirname(os.path.abspath(__file__)),
                         "discovery.json"),
        ]
        cache_path = next((c for c in cache_candidates if c and os.path.exists(c)), "")
        if os.path.exists(cache_path):
            with open(cache_path, encoding="utf-8") as cf:
                cached = json.load(cf)
            if isinstance(cached, list):
                datasets = cached
            else:
                datasets = cached.get(
                    "result", {}).get("packages", cached.get(
                        "packages", []))
            print(f"[discover] loaded {len(datasets)} from cache {cache_path}")
        else:
            print(f"[discover] no cache at {cache_path}, pinned SOURCES only")
            datasets = []

    if list_only:
        for d in sorted(datasets, key=lambda d: d.get("title") or ""):
            key, _, _ = slug_city_key(d.get("title"), d.get("organization"))
            resources = _candidate_resources(d)
            link = resources[0][0] if resources else None
            print(f"  {key} :: {d['title']} [{d['organization']}]")
            if link:
                print(f"    {link}")
        return 0

    run_pinned()

    used_keys = set(SOURCES)
    for d in sorted(datasets, key=lambda d: d.get("uid") or ""):
        uid = d.get("uid") or ""
        title, org = d.get("title") or "", d.get("organization") or ""
        urls = [u for u, _ in _candidate_resources(d)]
        if any(u.startswith(p) for u in urls for p in PINNED_URLS):
            continue  # pinned fetcher owns this feed; no double ingest
        key, oblast, is_oblast = slug_city_key(title, org)
        ov = OVERRIDES.get(uid, {}) or OVERRIDES.get(key, {})
        if ov.get("skip"):
            coverage.append((key, 0, 0, "auto", f"SKIPPED:{ov['skip']}"[:60]))
            continue
        if ov.get("city"):
            key = ov["city"]
        base, n = key, 2
        while key in used_keys:
            key = f"{base}-{n}"
            n += 1
        if not want(key, title):
            continue
        used_keys.add(key)
        out_path = os.path.join(OUTPUT_DIR, f"{key}.json")
        if skip_existing and os.path.exists(out_path):
            coverage.append((key, _disk_count(out_path),
                             os.path.getsize(out_path),
                             "auto", "SKIP_EXISTING"))
            continue
        city = ov.get("city_name") or _display_name(title, org, is_oblast)
        oblast = ov.get("oblast") or oblast
        shelters, report = ingest_dataset(d, city, oblast, f"{key}-ckan",
                                          pinned=ov)
        _last_reports[key] = report
        flags = list(report.get("flags", []))
        for w in sum((a.get("warnings", []) for a in report.get("tried", [])),
                     []):
            flags.append(f"MAP:{w}"[:80])
        if report.get("dropped"):
            flags.append("DROP:" + ",".join(
                f"{k}={v}" for k, v in report["dropped"].items()))
        if not shelters:
            print(f"[empty] {key}: {title[:60]} "
                  f"({'; '.join(a.get('result', '?') for a in report['tried'])[:120]})")
            coverage.append((key, 0, 0, "auto", ";".join(flags) or "ZERO_ROWS"))
            continue
        _, size = _write_city_file(key, shelters, generated_at)
        fmt = report.get("format", "?")
        print(f"[ok] {key}: {len(shelters)} shelters ({fmt}) -> {out_path}")
        coverage.append((key, len(shelters), size, "auto", ";".join(flags)))

    total_rows = sum(r for _, r, _, _, _ in coverage if r > 0)
    total_bytes = sum(b for _, _, b, _, _ in coverage if b > 0)
    files = sum(1 for _, r, _, _, _ in coverage if r > 0)
    print(f"\n--- coverage: {files} files, {total_rows} shelters, "
          f"{total_bytes / 1048576:.1f} MB ---")
    for key, rows, size, origin, flags in coverage:
        mark = " " if rows and rows > 0 else "!"
        print(f"  {mark} {key}: rows={rows} KB={size // 1024} "
              f"{origin} {flags}")
    problems = [(k, f or "ZERO_ROWS") for k, r, _, _, f in coverage
                if "SKIP_EXISTING" not in f and (r <= 0 or f)]
    if problems:
        print(f"--- problems: {len(problems)} ---")
        for k, f in problems:
            print(f"  ! {k}: {f}")
    diag = []
    for key, rows, _, origin, _ in coverage:
        if origin != "auto" or rows != 0:
            continue
        for a in _last_reports.get(key, {}).get("tried", []):
            res = a.get("result", "?")
            if "no lat/lng" in res and a.get("columns"):
                diag.append((key, f"{res} :: {' | '.join(a['columns'])}"))
                break
            if res.endswith("kept") and a.get("roles"):
                roles = " ".join(f"{k}={v}" for k, v in a["roles"].items()
                                 if k in ("lat", "lng", "latlng", "uid"))
                diag.append((key, f"{res} [{roles}] sample={a.get('sample')}"))
                break
    if diag:
        print(f"--- headers ({len(diag)} failed sources) ---")
        for key, detail in diag:
            print(f"  H {key}: {detail}")
    return 0


if __name__ == "__main__":
    main()