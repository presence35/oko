#!/usr/bin/env python3
"""Generate RaionBoundaries.kt: simplified polygons for Ukraine's post-2020 raions.

Same data source as tools/gen_cities_raion.py — OSM admin_level=6 boundary
relations (fetched from Overpass) — so the adjectival name keys match both
CityRaions.cityRaion values and NEPTUN's raion alert names. Each raion is
assigned to its parent oblast by centroid point-in-polygon against the oblast
polygons (EugeneBorshch/ukraine_geojson, the OblastBoundaries source), because
raion adjectival names repeat across oblasts and the fill must key on both.

The map fills an alerting raion only when "Fill alerting regions" is on and the
alert is raion-level; oblast-wide alerts fill the whole oblast (OblastBoundaries).

Usage: python tools/gen_raion_boundaries.py
"""
import json
import math
import os
import re
import sys
import urllib.parse
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

TOOLS = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(TOOLS)
OUT_FILE = os.path.join(REPO, "app", "src", "main", "java", "ua", "ukrainedrones", "domain", "RaionBoundaries.kt")

OVERQUERY = (
    '[out:json][timeout:300];'
    'area["ISO3166-1"="UA"]->.ua;'
    'relation["boundary"="administrative"]["admin_level"="6"](area.ua);'
    'out geom;'
)
GEOJSON_URL = "https://github.com/EugeneBorshch/ukraine_geojson/raw/refs/heads/master/UA_FULL_Ukraine.geojson"

# Same simplification as the oblast fills, so raion fills read as the same texture.
EPSILON = 0.012

OVERPASS_MIRRORS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.osm.ch/api/interpreter",
]

ISO_TO_STEM = {
    "UA-05": "Вінницьк", "UA-07": "Волинськ", "UA-09": "Луганськ",
    "UA-12": "Дніпропетровськ", "UA-14": "Донецьк", "UA-18": "Житомирськ",
    "UA-21": "Закарпатськ", "UA-23": "Запорізьк", "UA-26": "Івано-Франківськ",
    "UA-32": "Київськ", "UA-35": "Кіровоградськ", "UA-40": "Севастополь",
    "UA-43": "Крим", "UA-46": "Львівськ", "UA-48": "Миколаївськ",
    "UA-51": "Одеськ", "UA-53": "Полтавськ", "UA-56": "Рівненськ",
    "UA-59": "Сумськ", "UA-61": "Тернопільськ", "UA-63": "Харківськ",
    "UA-65": "Херсонськ", "UA-68": "Хмельницьк", "UA-71": "Черкаськ",
    "UA-74": "Чернігівськ", "UA-77": "Чернівецьк", "UA-80": "Київськ",
}


def fetch(url, data=None, timeout=300):
    req = urllib.request.Request(url, data=data, headers={"User-Agent": "oko-gen-tool/1.0"})
    return urllib.request.urlopen(req, timeout=timeout).read()


def fetch_overpass(overquery, timeout=300):
    last = None
    for base in OVERPASS_MIRRORS:
        for attempt in range(3):
            try:
                return json.loads(fetch(base, urllib.parse.urlencode({"data": overquery}).encode(), timeout=timeout))
            except Exception as e:  # noqa: BLE001
                last = e
                print(f"  retry {base} attempt {attempt}: {e}")
    raise last


def rdp(points, epsilon):
    if len(points) <= 2:
        return points

    def pd(p, a, b):
        dx, dy = b[0] - a[0], b[1] - a[1]
        if dx == 0 and dy == 0:
            return math.hypot(p[0] - a[0], p[1] - a[1])
        t = max(0.0, min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / (dx * dx + dy * dy)))
        px, py = a[0] + t * dx, a[1] + t * dy
        return math.hypot(p[0] - px, p[1] - py)

    first, last = points[0], points[-1]
    md, mi = 0.0, 0
    for i in range(1, len(points) - 1):
        d = pd(points[i], first, last)
        if d > md:
            md, mi = d, i
    if md > epsilon:
        left = rdp(points[: mi + 1], epsilon)
        right = rdp(points[mi:], epsilon)
        return left[:-1] + right
    return [first, last]


def assemble_rings(segments):
    """Assemble closed rings from way polylines by matching shared endpoints."""
    unused = [list(s) for s in segments]
    rings = []
    while unused:
        ring = list(unused.pop(0))
        changed = True
        while changed:
            changed = False
            for i, s in enumerate(unused):
                if s[0] == ring[-1]:
                    ring.extend(s[1:]); unused.pop(i); changed = True; break
                if s[-1] == ring[-1]:
                    ring.extend(reversed(s[:-1])); unused.pop(i); changed = True; break
                if s[-1] == ring[0]:
                    ring = list(s[:-1]) + ring; unused.pop(i); changed = True; break
                if s[0] == ring[0]:
                    ring = list(reversed(s[1:])) + ring; unused.pop(i); changed = True; break
        rings.append(ring)
    return rings


def point_in_ring(lat, lon, ring):
    inside = False
    j = len(ring) - 1
    for i in range(len(ring)):
        yi, xi = ring[i]
        yj, xj = ring[j]
        if (yi > lat) != (yj > lat):
            xint = xi + (lat - yi) / (yj - yi) * (xj - xi)
            if lon < xint:
                inside = not inside
        j = i
    return inside


def ring_centroid(ring):
    area2 = 0.0
    cx = cy = 0.0
    for i in range(len(ring)):
        x1, y1 = ring[i]
        x2, y2 = ring[(i + 1) % len(ring)]
        cross = x1 * y2 - x2 * y1
        area2 += cross
        cx += (x1 + x2) * cross
        cy += (y1 + y2) * cross
    if area2 == 0:
        return (ring[0][0], ring[0][1])
    return (cx / (3 * area2), cy / (3 * area2))


def oblast_polygons():
    """stem -> list of rings (list of (lat, lon)) from the ukraine_geojson."""
    raw = json.loads(fetch(GEOJSON_URL, timeout=120))
    by_stem = {}
    for feature in raw["features"]:
        stem = ISO_TO_STEM.get(feature["properties"].get("iso3166-2", ""))
        if not stem:
            continue
        geom = feature["geometry"]
        rings = []
        if geom["type"] == "Polygon":
            rings = [geom["coordinates"][0]]
        elif geom["type"] == "MultiPolygon":
            rings = [p[0] for p in geom["coordinates"]]
        by_stem.setdefault(stem, []).extend(
            [[(pt[1], pt[0]) for pt in r] for r in rings]
        )
    return by_stem


def main():
    raw = fetch_overpass(OVERQUERY)
    raions = []
    for e in raw.get("elements", []):
        tags = e.get("tags", {})
        name_uk = tags.get("name:uk") or tags.get("name")
        if not name_uk:
            continue
        segments = []
        for m in e.get("members", []):
            if m.get("role") != "outer":
                continue
            geom = m.get("geometry") or []
            pts = [(g["lat"], g["lon"]) for g in geom]
            if len(pts) >= 2:
                segments.append(pts)
        rings = assemble_rings(segments)
        rings = [r for r in rings if len(r) >= 4]
        if not rings:
            continue
        rings = [rdp(r, EPSILON) for r in rings]
        if rings[0][0] != rings[0][-1]:
            rings[0].append(rings[0][0][:])
        adjectival = re.sub(r"\s+район$", "", name_uk, flags=re.IGNORECASE).strip()
        raions.append((adjectival, rings))
    print(f"raions with geometry: {len(raions)}")

    stems = oblast_polygons()

    # Assign each raion to its parent oblast by centroid containment.
    result = {}  # stem -> {adjectival.lower(): rings}
    unmatched = []
    for adjectival, rings in raions:
        big = max(rings, key=len)
        lat, lon = ring_centroid(big)
        hit = None
        for stem, orings in stems.items():
            if any(point_in_ring(lat, lon, r) for r in orings):
                hit = stem
                break
        if hit is None:
            # boundary centroid miss (multi-part or water): nearest oblast ring
            best, best_s = float("inf"), None
            for stem, orings in stems.items():
                for r in orings:
                    # cheap representative distance to the ring's first vertex
                    d = math.hypot(lat - r[0][0], lon - r[0][1])
                    if d < best:
                        best, best_s = d, stem
            hit = best_s
        if hit is None:
            unmatched.append(adjectival)
            continue
        bucket = result.setdefault(hit, {})
        key = adjectival.lower()
        if key in bucket:
            # keep the larger polygon for a duplicate name within the stem
            if len(rings) > len(bucket[key]):
                bucket[key] = rings
        else:
            bucket[key] = rings
    print(f"assigned: {sum(len(v) for v in result.values())} raions, unmatched: {len(unmatched)}")
    if unmatched:
        print("unmatched:", unmatched[:20])

    # Emit Kotlin — per-stem helper functions to stay under the JVM 64 KB clinit limit.
    lines = [
        "package ua.ukrainedrones",
        "",
        "/**",
        " * Auto-generated post-2020 raion boundary polygons from OSM admin_level=6 relations",
        " * (Overpass), simplified to ~0.01 precision. Keys are (oblast stem, raion adjectival",
        " * name) matching CityRaions and NEPTUN's raion alert names — raion names repeat",
        " * across oblasts, so fills must scope by parent oblast. Used by MapView to shade the",
        " * alerting raion when \"Fill alerting regions\" is on and the alert is raion-level.",
        " * Regenerate with tools/gen_raion_boundaries.py.",
        " */",
        "object RaionBoundaries {",
        "    /** Rings for the raion, or null when unknown. [raionName] is case-insensitive.",
        "     *  Each ring is a list of doubleArrayOf(lat, lon) pairs. */",
        "    fun forKey(oblastStem: String, raionName: String): List<List<DoubleArray>>? =",
        "        when (oblastStem) {",
    ]
    stem_names = sorted(result.keys())
    for stem in stem_names:
        fn = "_" + stem.replace("-", "_").replace("'", "_").replace(" ", "_")
        lines.append(f'            "{stem}" -> {fn}()[raionName.lowercase()]')
    lines.append("            else -> null")
    lines.append("        }")
    lines.append("")

    for stem in stem_names:
        bucket = result[stem]
        fn = "_" + stem.replace("-", "_").replace("'", "_").replace(" ", "_")
        lines.append(f"    private fun {fn}(): Map<String, List<List<DoubleArray>>> = mapOf(")
        entries = []
        for key in sorted(bucket.keys()):
            rings = bucket[key]
            ring_strs = []
            for ring in rings:
                pts = ",\n".join(f"                doubleArrayOf({float(p[0])}, {float(p[1])})" for p in ring)
                ring_strs.append(f"            listOf(\n{pts}\n            )")
            entries.append(f'        "{key}" to listOf(\n' + ",\n".join(ring_strs) + "\n        )")
        lines.append(",\n".join(entries))
        lines.append("    )")
        lines.append("")
    lines.append("}")
    lines.append("")

    os.makedirs(os.path.dirname(OUT_FILE), exist_ok=True)
    with open(OUT_FILE, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    total_pts = sum(len(r) for rings in result.values() for rr in rings.values() for r in rr)
    print(f"Done: {len(result)} stems, {total_pts} points, {os.path.getsize(OUT_FILE)/1024:.1f} KB -> {OUT_FILE}")


if __name__ == "__main__":
    main()