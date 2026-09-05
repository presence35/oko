#!/usr/bin/env python3
"""Generate OblastBoundaries.kt from Ukraine admin-1 GeoJSON.

Splits initialization across per-stem helper functions so that no single
JVM method exceeds the 64 KB limit (the old all-in-one mapOf hit
MethodTooLargeException)."""
import json
import urllib.request
import math
import os

GEOJSON_URL = "https://github.com/EugeneBorshch/ukraine_geojson/raw/refs/heads/master/UA_FULL_Ukraine.geojson"
OUT_FILE = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "java", "ua", "ukrainedrones", "domain", "OblastBoundaries.kt")

# ISO3166-2 code -> Ukrainian stem (Cities.kt Region names)
ISO_TO_STEM = {
    "UA-05": "Вінницьк",
    "UA-07": "Волинськ",
    "UA-09": "Луганськ",
    "UA-12": "Дніпропетровськ",
    "UA-14": "Донецьк",
    "UA-18": "Житомирськ",
    "UA-21": "Закарпатськ",
    "UA-23": "Запорізьк",
    "UA-26": "Івано-Франківськ",
    "UA-32": "Київськ",
    "UA-35": "Кіровоградськ",
    "UA-40": "Севастополь",
    "UA-43": "Крим",
    "UA-46": "Львівськ",
    "UA-48": "Миколаївськ",
    "UA-51": "Одеськ",
    "UA-53": "Полтавськ",
    "UA-56": "Рівненськ",
    "UA-59": "Сумськ",
    "UA-61": "Тернопільськ",
    "UA-63": "Харківськ",
    "UA-65": "Херсонськ",
    "UA-68": "Хмельницьк",
    "UA-71": "Черкаськ",
    "UA-74": "Чернігівськ",
    "UA-77": "Чернівецьк",
    "UA-80": "Київськ",  # Kyiv city -> same stem as oblast
}

def point_line_distance(p, a, b):
    dx, dy = b[0] - a[0], b[1] - a[1]
    len_sq = dx * dx + dy * dy
    if len_sq == 0:
        ex, ey = p[0] - a[0], p[1] - a[1]
        return math.sqrt(ex * ex + ey * ey)
    t = max(0, min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len_sq))
    proj_x = a[0] + t * dx
    proj_y = a[1] + t * dy
    ex, ey = p[0] - proj_x, p[1] - proj_y
    return math.sqrt(ex * ex + ey * ey)

def simplify_rdp(points, epsilon=0.012):
    if len(points) <= 2:
        return points
    first, last = points[0], points[-1]
    max_dist = 0
    max_idx = 0
    for i in range(1, len(points) - 1):
        d = point_line_distance(points[i], first, last)
        if d > max_dist:
            max_dist = d
            max_idx = i
    if max_dist > epsilon:
        left = simplify_rdp(points[:max_idx + 1], epsilon)
        right = simplify_rdp(points[max_idx:], epsilon)
        return left[:-1] + right
    return [first, last]

def sanitize_func_name(stem):
    """Convert a Ukrainian stem to a valid Kotlin identifier."""
    return "_ring_" + stem.replace("-", "_").replace("'", "_")

def main():
    print(f"Fetching {GEOJSON_URL}...")
    with urllib.request.urlopen(GEOJSON_URL, timeout=30) as resp:
        raw = json.loads(resp.read())

    by_stem = {}  # stem -> list of rings

    for feature in raw["features"]:
        props = feature["properties"]
        iso = props.get("iso3166-2", "")
        stem = ISO_TO_STEM.get(iso)
        if not stem:
            print(f"  Warning: no stem for {iso} - skipping")
            continue

        geom = feature["geometry"]
        rings = []

        if geom["type"] == "Polygon":
            for ring in geom["coordinates"]:
                pts = [[c[0], c[1]] for c in ring]
                simplified = simplify_rdp(pts)
                if simplified[0] != simplified[-1]:
                    simplified.append(simplified[0][:])
                rings.append(simplified)
        elif geom["type"] == "MultiPolygon":
            for polygon in geom["coordinates"]:
                for ring in polygon:
                    pts = [[c[0], c[1]] for c in ring]
                    simplified = simplify_rdp(pts)
                    if simplified[0] != simplified[-1]:
                        simplified.append(simplified[0][:])
                    rings.append(simplified)

        by_stem.setdefault(stem, []).extend(rings)
        total_pts = sum(len(r) for r in rings)
        print(f"  {iso}: {len(rings)} ring(s), {total_pts} points")

    # Emit Kotlin — one private function per stem, mapOf at top.
    lines = [
        "package ua.ukrainedrones",
        "",
        "/**",
        " * Auto-generated oblast boundary polygons from EugeneBorshch/ukraine_geojson (ODbL).",
        " * Simplified to ~0.01 precision. Regenerate with tools/gen_oblast_boundaries.py.",
        " */",
        "object OblastBoundaries {",
        "    /** Oblast stem (matching Cities.kt) -> list of rings. Each ring is a list of",
        "     *  doubleArrayOf(lon, lat) pairs. Most oblasts have one ring; multi-part may",
        "     *  have multiple. Split into per-stem helper functions to stay under the JVM",
        "     *  64 KB <clinit> limit. */",
        "    val byStem: Map<String, List<List<DoubleArray>>> = mapOf(",
    ]

    stem_names = sorted(by_stem.keys())
    map_entries = []
    for stem in stem_names:
        func_name = sanitize_func_name(stem)
        map_entries.append(f'        "{stem}" to {func_name}()')
    lines.append(",\n".join(map_entries))
    lines.append("    )")
    lines.append("")

    # Per-stem helper functions
    for stem in stem_names:
        rings = by_stem[stem]
        func_name = sanitize_func_name(stem)
        lines.append(f"    private fun {func_name}(): List<List<DoubleArray>> = listOf(")

        ring_strs = []
        for ring in rings:
            pts = ",\n".join(f"            doubleArrayOf({float(p[0])}, {float(p[1])})" for p in ring)
            ring_strs.append(f"        listOf(\n{pts}\n        )")
        lines.append(",\n".join(ring_strs))
        lines.append("    )")
        lines.append("")

    lines.append("}")
    lines.append("")

    kt = "\n".join(lines)
    os.makedirs(os.path.dirname(OUT_FILE), exist_ok=True)
    with open(OUT_FILE, "w", encoding="utf-8") as f:
        f.write(kt)

    total_pts = sum(len(r) for rings in by_stem.values() for r in rings)
    file_size = os.path.getsize(OUT_FILE)
    print(f"\nDone: {len(by_stem)} stems, {total_pts} total points, {file_size/1024:.1f} KB")

if __name__ == "__main__":
    main()
