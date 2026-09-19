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
import sys
from datetime import datetime, timezone

import requests

# Absolute: the script writes next to itself no matter where it's invoked from.
OUTPUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "output")

CKAN_SEARCH_URL = "https://data.gov.ua/api/3/action/package_search"


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
    if "пру" in raw or "протирадіац" in raw:
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


def fetch_csv_dataset(url: str, city: str, oblast: str, source_name: str,
                       field_map: dict) -> list[dict]:
    """
    Generic fetcher for the many data.gov.ua CSV datasets.
    field_map maps our normalized keys -> that dataset's actual column names,
    e.g. {"address": "Адреса", "type": "Вид споруди"}.
    Inspect each dataset's CSV header once and fill this in per source.
    """
    resp = requests.get(url, timeout=30)
    resp.raise_for_status()
    resp.encoding = "utf-8"
    reader = csv.DictReader(io.StringIO(resp.text))

    shelters = []
    for i, row in enumerate(reader):
        try:
            lat = float(row[field_map["lat"]])
            lng = float(row[field_map["lng"]])
        except (KeyError, ValueError, TypeError):
            continue  # no usable coordinates, skip the row

        shelters.append({
            "id": f"{source_name}-{i}",
            "city": city,
            "oblast": oblast,
            "source": source_name,
            "source_id": str(i),
            "lat": lat,
            "lng": lng,
            "address": row.get(field_map.get("address", ""), "").strip() or None,
            "name": row.get(field_map.get("name", ""), "").strip() or None,
            "type": normalize_type(row.get(field_map.get("type", ""), "")),
            "capacity": row.get(field_map.get("capacity", "")),
            "owner": row.get(field_map.get("owner", ""), "").strip() or None,
            "updated_at": None,
        })
    return shelters


# Register one entry per city source here. Kyiv is live (Esri JSON);
# everything else on data.gov.ua is a CSV export — add those as you
# pull each dataset's real URL + column names.
SOURCES = {
    "kyiv": fetch_kyiv,
    # "odesa": lambda: fetch_csv_dataset(
    #     url="<odesa dataset csv resource url>",
    #     city="Odesa", oblast="Odesa", source_name="odesa-datagovua",
    #     field_map={"lat": "Широта", "lng": "Довгота", "address": "Адреса",
    #                "type": "Вид споруди"},
    # ),
}


def main():
    # Windows consoles default to a non-UTF8 codepage, which crashes printing
    # Ukrainian dataset titles — decode-safe stdout instead.
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    generated_at = datetime.now(timezone.utc).isoformat()

    # Print what's actually available before running the hardcoded
    # fetchers below, so you notice new/renamed/dead datasets instead
    # of silently missing them.
    try:
        datasets = discover_shelter_datasets()
        print(f"[discover] {len(datasets)} datasets tagged 'укриття':")
        for d in datasets:
            formats = ", ".join(r["format"] for r in d["resources"] if r["format"])
            print(f"  - {d['title']} [{d['organization']}] ({formats})")
    except Exception as e:
        print(f"[discover] failed, continuing with known SOURCES only: {e}")

    for city_key, fetcher in SOURCES.items():
        try:
            shelters = fetcher()
        except Exception as e:
            print(f"[skip] {city_key} failed: {e}")
            continue

        out_path = os.path.join(OUTPUT_DIR, f"{city_key}.json")
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump({
                "generated_at": generated_at,
                "city": city_key,
                "count": len(shelters),
                "shelters": shelters,
            }, f, ensure_ascii=False, indent=2)

        print(f"[ok] {city_key}: {len(shelters)} shelters -> {out_path}")


if __name__ == "__main__":
    main()