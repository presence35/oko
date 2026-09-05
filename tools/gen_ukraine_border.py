#!/usr/bin/env python3
"""Generate ui/UkraineBorder.kt as the exact outer hull of the oblast polygons
from EugeneBorshch/ukraine_geojson — the SAME source the red oblast fills use
(OblastBoundaries.kt). This makes the country outline coincide with the outer
edges of the combined oblast fills (previously a separate, coarser
geoBoundaries ADM0 outline drifted from them along the coast).

No shapely dependency: interior edges of a coherent admin-1 tessellation are
shared by two oblasts, so they appear twice; exterior edges appear once.
Counting canonicalized edges yields the boundary; we chain the surviving edges
into loops and keep the largest (the country exterior).

Usage: python tools/gen_ukraine_border.py
"""
import json
import math
import os
import urllib.request

GEOJSON_URL = "https://github.com/EugeneBorshch/ukraine_geojson/raw/refs/heads/master/UA_FULL_Ukraine.geojson"
OUT_FILE = os.path.join(
    os.path.dirname(__file__), "..", "app", "src", "main", "java", "ua",
    "ukrainedrones", "ui", "UkraineBorder.kt",
)

# Rounding grid for edge identity — shared admin-1 boundaries must match exactly
# even if the source floats differ by float32 noise (~1e-5). 1e-5 deg ~ 1 m.
EDGE_ROUND = 5
EPSILON = 0.012  # same simplification as the oblast fills


def rdp_simplify(points, epsilon=EPSILON):
    if len(points) <= 2:
        return points

    def perp_dist(p, a, b):
        dx, dy = b[0] - a[0], b[1] - a[1]
        if dx == 0 and dy == 0:
            return math.hypot(p[0] - a[0], p[1] - a[1])
        t = max(0.0, min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / (dx * dx + dy * dy)))
        px, py = a[0] + t * dx, a[1] + t * dy
        return math.hypot(p[0] - px, p[1] - py)

    first, last = points[0], points[-1]
    max_d, max_i = 0.0, 0
    for i in range(1, len(points) - 1):
        d = perp_dist(points[i], first, last)
        if d > max_d:
            max_d, max_i = d, i
    if max_d > epsilon:
        left = rdp_simplify(points[: max_i + 1], epsilon)
        right = rdp_simplify(points[max_i:], epsilon)
        return left[:-1] + right
    return [first, last]


def ring_edges(ring):
    """Return canonical, order-independent edges of a closed ring."""
    out = []
    for i in range(len(ring) - 1):
        a = tuple(round(c, EDGE_ROUND) for c in ring[i])
        b = tuple(round(c, EDGE_ROUND) for c in ring[i + 1])
        out.append((min(a, b), max(a, b)))
    return out


def main():
    print(f"Fetching {GEOJSON_URL}...")
    with urllib.request.urlopen(GEOJSON_URL, timeout=60) as resp:
        raw = json.loads(resp.read())

    all_rings = []  # every polygon ring of every feature (incl. Sevastopol UA-40)
    for feature in raw["features"]:
        geom = feature["geometry"]
        coords = geom["coordinates"]
        if geom["type"] == "Polygon":
            all_rings.extend(coords)
        elif geom["type"] == "MultiPolygon":
            for polygon in coords:
                all_rings.extend(polygon)

    counts = {}
    for ring in all_rings:
        if len(ring) < 4:
            continue
        for e in ring_edges(ring):
            counts[e] = counts.get(e, 0) + 1

    boundary = {e for e, n in counts.items() if n == 1}
    print(f"  {len(all_rings)} rings, {len(counts)} distinct edges, {len(boundary)} boundary edges")

    # Chain boundary edges into loops. Every boundary vertex has degree 2, so a
    # greedy walk from any unused edge traces a closed loop.
    adj = {}
    for (a, b) in boundary:
        adj.setdefault(a, []).append(b)
        adj.setdefault(b, []).append(a)

    used = set()
    loops = []
    for start, _ in list(boundary):
        if start in used:
            continue
        loop = [start]
        used.add(start)
        prev, cur = None, start
        while True:
            nxt = next((v for v in adj[cur] if v != prev and v not in used), None)
            if nxt is None:
                break
            used.add(nxt)
            loop.append(nxt)
            prev, cur = cur, nxt
            if cur == start:
                break
        loops.append(loop)

    def loop_area(loop):
        s = 0.0
        n = len(loop)
        for i in range(n):
            x1, y1 = loop[i]
            x2, y2 = loop[(i + 1) % n]
            s += x1 * y2 - x2 * y1
        return abs(s) / 2.0

    exterior = max(loops, key=loop_area)
    print(f"  {len(loops)} boundary loop(s); exterior = {len(exterior)} points")

    # Full-precision coordinates in exterior order (round-trip through the edge map).
    pt_lookup = {}
    for ring in all_rings:
        for p in ring:
            pt_lookup[tuple(round(c, EDGE_ROUND) for c in p)] = (p[1], p[0])  # (lat, lon)

    full = [pt_lookup[p] for p in exterior]
    if full[0] != full[-1]:
        full.append(full[0])
    simplified = rdp_simplify(full)
    if simplified[0] != simplified[-1]:
        simplified.append(simplified[0][:])

    lines = [
        "package ua.ukrainedrones",
        "",
        "import org.osmdroid.util.GeoPoint",
        "",
        "/**",
        " * Simplified outline of Ukraine (incl. Crimea): the outer hull of the oblast boundary",
        " * polygons (OblastBoundaries.kt) — same source (EugeneBorshch/ukraine_geojson), same",
        " * simplification — so the country edge coincides with the combined red oblast fills.",
        " * Regenerate with tools/gen_ukraine_border.py. Values are (lat, lon) GeoPoints.",
        " */",
        "val UKRAINE_BORDER: List<GeoPoint> = listOf(",
    ]
    chunks = []
    row = []
    for lat, lon in simplified:
        row.append(f"GeoPoint({lat}, {lon})")
        if len(row) == 4:
            chunks.append("    " + ", ".join(row) + ",")
            row = []
    if row:
        chunks.append("    " + ", ".join(row) + ",")
    lines.extend(chunks)
    lines.append(")")
    lines.append("")

    os.makedirs(os.path.dirname(OUT_FILE), exist_ok=True)
    with open(OUT_FILE, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    print(f"\nDone: {len(simplified)} points, {os.path.getsize(OUT_FILE)/1024:.1f} KB -> {OUT_FILE}")


if __name__ == "__main__":
    main()