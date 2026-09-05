#!/usr/bin/env python3
"""Generate CityRaions.kt: map each city in Cities.kt to its (post-2020) raion.

Cities are assigned to raions geographically: fetch Ukraine admin_level=6 (raion) boundary
geometry from Overpass, then point-in-polygon each city's coordinates. The stored value is the
raion's Ukrainian adjectival name WITHOUT the trailing "rayon" word, matching NEPTUN's raion
keys (e.g. "Dniprovskyi" for "Dniprovskyi raion" / NEPTUN key "dniprovskyi").
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
CITIES_KT = os.path.join(REPO, "app", "src", "main", "java", "ua", "ukrainedrones", "domain", "Cities.kt")
OUT_FILE = os.path.join(REPO, "app", "src", "main", "java", "ua", "ukrainedrones", "domain", "CityRaions.kt")

OVERQUERY = (
    '[out:json][timeout:300];'
    'area["ISO3166-1"="UA"]->.ua;'
    'relation["boundary"="administrative"]["admin_level"="6"](area.ua);'
    'out geom;'
)

EPSILON = 0.004


def fetch(url, data=None, timeout=300):
    req = urllib.request.Request(url, data=data, headers={"User-Agent": "oko-gen-tool/1.0"})
    return urllib.request.urlopen(req, timeout=timeout).read()


OVERPASS_MIRRORS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.osm.ch/api/interpreter",
]


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


def parse_cities(path):
    """Return list of (name_ua, lat, lon, oblast_stem)."""
    text = open(path, encoding="utf-8").read()
    cities = []
    cur_stem = None
    for line in text.splitlines():
        m = re.search(r'Region\("([^"]+)"', line)
        if m:
            cur_stem = m.group(1)
            continue
        m = re.search(r'City\("([^"]+)",\s*([0-9.]+),\s*([0-9.]+)', line)
        if m and cur_stem:
            cities.append((m.group(1), float(m.group(2)), float(m.group(3)), cur_stem))
    return cities


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


def dist_to_ring(lat, lon, ring):
    best = float("inf")
    j = len(ring) - 1
    for i in range(len(ring)):
        yi, xi = ring[i]
        yj, xj = ring[j]
        dx = xj - xi
        dy = yj - yi
        ll = dx * dx + dy * dy
        t = 0.0 if ll == 0 else max(0.0, min(1.0, ((lon - xi) * dx + (lat - yi) * dy) / ll))
        px, py = xi + t * dx, yi + t * dy
        best = min(best, math.hypot(lat - py, lon - px))
        j = i
    return best


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
                    ring.extend(s[1:])
                    unused.pop(i)
                    changed = True
                    break
                if s[-1] == ring[-1]:
                    ring.extend(reversed(s[:-1]))
                    unused.pop(i)
                    changed = True
                    break
                if s[-1] == ring[0]:
                    ring = list(s[:-1]) + ring
                    unused.pop(i)
                    changed = True
                    break
                if s[0] == ring[0]:
                    ring = list(reversed(s[1:])) + ring
                    unused.pop(i)
                    changed = True
                    break
        rings.append(ring)
    return rings


def main():
    cities = parse_cities(CITIES_KT)
    print("cities parsed:", len(cities))

    raw = fetch_overpass(OVERQUERY)
    raions = []
    for e in raw.get("elements", []):
        name_uk = e.get("tags", {}).get("name:uk") or e.get("tags", {}).get("name")
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
        big = [r for r in rings if len(r) >= 4]
        if big:
            # use the largest assembled ring per relation (outer shell) for containment
            shell = max(big, key=len)
            shell = rdp(shell, EPSILON)
            raions.append((name_uk, shell))
    print("raions with geometry:", len(raions))

    # assign each city to the raion whose outer ring contains it; nearest-ring as a boundary tiebreak
    result = {}
    unmatched = []
    for name, lat, lon, stem in cities:
        hit = None
        for rname, ring in raions:
            if point_in_ring(lat, lon, ring):
                hit = rname
                break
        if hit:
            adjectival = re.sub(r"\s+район$", "", hit, flags=re.IGNORECASE)
            result[name] = adjectival.strip()
            continue
        # boundary miss: nearest raion polygon
        best, best_r = float("inf"), None
        for rname, ring in raions:
            d = dist_to_ring(lat, lon, ring)
            if d < best:
                best, best_r = d, rname
        if best_r is not None and best < 0.04:
            adjectival = re.sub(r"\s+район$", "", best_r, flags=re.IGNORECASE)
            result[name] = adjectival.strip()
        else:
            unmatched.append((name, stem))

    print("assigned:", len(result), "unmatched:", len(unmatched))
    if unmatched:
        print("unmatched:", unmatched[:40])

    # Emit Kotlin
    keys = sorted(result.keys())
    lines = [
        "package ua.ukrainedrones",
        "",
        "/**",
        " * Auto-generated: each city in Cities.kt mapped to its (post-2020) raion's Ukrainian",
        " * adjectival name WITHOUT the trailing \"район\" word (e.g. \"Дніпровський\"), matching",
        " * NEPTUN's raion alert keys. Assigned by point-in-polygon against OSM admin_level=6",
        " * boundaries. Regenerate with tools/gen_cities_raion.py.",
        " */",
        "object CityRaions {",
        "    val cityRaion: Map<String, String> = mapOf(",
    ]
    entries = [f'        "{k}" to "{result[k]}"' for k in keys]
    lines.append(",\n".join(entries))
    lines.append("    )")
    lines.append("}")
    lines.append("")

    os.makedirs(os.path.dirname(OUT_FILE), exist_ok=True)
    with open(OUT_FILE, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"wrote {OUT_FILE} ({len(keys)} entries)")


if __name__ == "__main__":
    main()