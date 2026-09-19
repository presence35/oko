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
from datetime import datetime, timezone

import requests

OUTPUT_DIR = "output"

KYIV_ARCGIS_URL = (
    "https://gisserver.kyivcity.gov.ua/mayno/rest/services/KYIV_API/"
    "%D0%9A%D0%B8%D1%97%D0%B2_%D0%A6%D0%B8%D1%84%D1%80%D0%BE%D0%B2%D0%B8%D0%B9/"
    "MapServer/0/query?where=1%3D1&outFields=*&outSR=4326&f=json"
)


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
    return "other"


def fetch_kyiv() -> list[dict]:
    """Kyiv's ArcGIS FeatureServer — the one live queryable endpoint we found."""
    resp = requests.get(KYIV_ARCGIS_URL, timeout=30)
    resp.raise_for_status()
    data = resp.json()

    shelters = []
    for i, feature in enumerate(data.get("features", [])):
        attrs = feature.get("attributes", {})
        geom = feature.get("geometry", {})
        lat, lng = geom.get("y"), geom.get("x")
        if lat is None or lng is None:
            continue  # skip rows with no coordinates rather than crash

        # NOTE: field names below (ADDRESS, TYPE, OWNER...) are placeholders —
        # confirm the real attribute keys against a live response before
        # trusting this mapping, Esri field names vary a lot per deployment.
        shelters.append({
            "id": f"kyiv-{attrs.get('OBJECTID', i)}",
            "city": "Kyiv",
            "oblast": "Kyiv",
            "source": "kyiv-arcgis",
            "source_id": str(attrs.get("OBJECTID", i)),
            "lat": lat,
            "lng": lng,
            "address": attrs.get("ADDRESS") or attrs.get("ADDR"),
            "name": attrs.get("NAME"),
            "type": normalize_type(attrs.get("TYPE") or attrs.get("VIEW")),
            "capacity": attrs.get("CAPACITY"),
            "owner": attrs.get("OWNER") or attrs.get("BALANCE_HOLDER"),
            "updated_at": None,
        })
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
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    generated_at = datetime.now(timezone.utc).isoformat()

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
