#!/usr/bin/env python3
"""Generate ui/UkraineBorder.kt with two outlines:

1. UKRAINE_BORDER — the exact closed outer hull of the oblast polygons
   (EugeneBorshch/ukraine_geojson, the SAME source the red oblast fills use),
   so the country outline coincides with the outer edges of the combined fills.
   Used as the red Ukraine-silhouette icon.

2. UKRAINE_LAND_BORDER — the same ring with every sea-coastline edge removed
   (edges whose midpoint falls inside the ocean polygon, Natural Earth 10m),
   chained into an open polyline. Used as the map's white outline, so it hugs
   the land borders tightly (incl. river borders) and never crosses water.
   Drawn as an osmdroid Polyline, not a closed Polygon.

No shapely dependency. Interior edges of a coherent admin-1 tessellation are
shared by two oblasts (they appear twice); exterior edges appear once. Counting
canonicalized edges yields the boundary; we chain the surviving edges into loops
and keep the largest (the country exterior).

Usage: python tools/gen_ukraine_border.py
"""
import json
import math
import os
import urllib.request
from collections import defaultdict

import numpy as np

GEOJSON_URL = "https://github.com/EugeneBorshch/ukraine_geojson/raw/refs/heads/master/UA_FULL_Ukraine.geojson"
OCEAN_URL = "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_ocean.geojson"
OUT_FILE = os.path.join(
    os.path.dirname(__file__), "..", "app", "src", "main", "java", "ua",
    "ukrainedrones", "ui", "UkraineBorder.kt",
)

# Rounding grid for edge identity — shared admin-1 boundaries must match exactly
# even if the source floats differ by float32 noise (~1e-5). 1e-5 deg ~ 1 m.
EDGE_ROUND = 5
RING_EPSILON = 0.012  # same simplification as the oblast fills (UKRAINE_BORDER)
LAND_EPSILON = 0.012  # same as RING_EPSILON so outer fill edge and land outline coincide when both visible

# Bounding box of the Ukrainian coast region (Black Sea + Sea of Azov). Edge
# midpoints outside it are land borders by definition; only candidates inside
# are tested against the ocean polygon.
SEA_BBOX = (43.5, 47.8, 26.0, 41.5)  # min_lat, max_lat, min_lon, max_lon


def fetch(url, timeout=180):
    req = urllib.request.Request(url, headers={"User-Agent": "oko-gen-tool/1.0"})
    return urllib.request.urlopen(req, timeout=timeout).read()


def rdp_simplify(points, epsilon):
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


def outer_ring():
    """Rebuild the full-resolution country outer ring from the admin-1 tessellation."""
    raw = json.loads(fetch(GEOJSON_URL))
    all_rings = []
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

    pt_lookup = {}
    for ring in all_rings:
        for p in ring:
            pt_lookup[tuple(round(c, EDGE_ROUND) for c in p)] = (p[1], p[0])  # (lat, lon)

    full = [pt_lookup[p] for p in exterior]
    if full[0] != full[-1]:
        full.append(full[0])
    return full


def ocean_polygon():
    """Return the giant Natural Earth 10m ocean ring (the world ocean incl. the
    Black Sea and the Sea of Azov) as a numpy edge array for fast PIP tests."""
    raw = json.loads(fetch(OCEAN_URL))
    geom = raw["features"][0]["geometry"]
    rings = []
    if geom["type"] == "Polygon":
        rings.append(geom["coordinates"][0])
    elif geom["type"] == "MultiPolygon":
        rings.extend(poly[0] for poly in geom["coordinates"])
    ring = max(rings, key=len)
    pts = np.array([[p[1], p[0]] for p in ring], dtype=np.float64)  # (lat, lon)
    return pts[:-1], pts[1:]


def points_in_ocean(midpoints, ys0, ys1, xs0, xs1):
    """Ray-casting point-in-polygon against the ocean ring, batched per point."""
    inside = np.zeros(len(midpoints), dtype=bool)
    with np.errstate(divide="ignore", invalid="ignore"):
        for i, (lat, lon) in enumerate(midpoints):
            cross = ((ys0 > lat) != (ys1 > lat))
            xint = xs0 + (lat - ys0) / (ys1 - ys0) * (xs1 - xs0)
            inside[i] = bool(np.logical_xor.reduce(cross & (lon < xint)))
    return inside


def classify_edges(full):
    """Return the ring edge indices that are LAND (midpoint outside the ocean)."""
    min_lat, max_lat, min_lon, max_lon = SEA_BBOX
    ring = full[:-1] if full[0] == full[-1] else full
    n = len(ring)
    mids = np.array([[(ring[i][0] + ring[(i + 1) % n][0]) / 2,
                      (ring[i][1] + ring[(i + 1) % n][1]) / 2] for i in range(n)])

    candidates = [
        i for i in range(n)
        if min_lat <= mids[i][0] <= max_lat and min_lon <= mids[i][1] <= max_lon
    ]
    if not candidates:
        return list(range(n))

    ys0, ys1 = ocean_polygon()
    y0, y1 = ys0[:, 0], ys1[:, 0]  # lat arrays
    x0, x1 = ys0[:, 1], ys1[:, 1]  # lon arrays
    sea = points_in_ocean(mids[candidates], y0, y1, x0, x1)

    candidates_set = set(candidates)
    land = [i for i in range(n) if i not in candidates_set]
    land += [c for c, s in zip(candidates, sea) if not s]
    return sorted(land)


def chain_land_edges(full, land_edges):
    """Chain the kept (land) ring edges into open polylines; return the largest."""
    ring = full[:-1] if full[0] == full[-1] else full
    n = len(ring)
    adj = defaultdict(list)
    for i in land_edges:
        a = ring[i]
        b = ring[(i + 1) % n]
        adj[a].append((b, i))
        adj[b].append((a, i))

    used_edges = set()
    chains = []
    ends = [v for v, ne in adj.items() if len(ne) == 1]
    starts = ends or list(adj)
    for start in starts:
        if all(e in used_edges for _, e in adj[start]):
            continue
        chain = [start]
        cur = start
        while True:
            cands = [x for x in adj[cur] if x[1] not in used_edges]
            if not cands:
                break
            nxt, eid = cands[0]
            used_edges.add(eid)
            chain.append(nxt)
            cur = nxt
        chains.append(chain)

    if not chains:
        return []
    return max(chains, key=len)


def emit_kt(border, land_border):
    def geo_strs(points):
        lines = []
        row = []
        for lat, lon in points:
            row.append(f"GeoPoint({lat}, {lon})")
            if len(row) == 4:
                lines.append("    " + ", ".join(row) + ",")
                row = []
        if row:
            lines.append("    " + ", ".join(row) + ",")
        return lines

    border_pts = rdp_simplify(border, RING_EPSILON)
    if border_pts[0] != border_pts[-1]:
        border_pts.append(border_pts[0][:])
    land_pts = rdp_simplify(land_border, LAND_EPSILON)

    lines = [
        "package ua.ukrainedrones",
        "",
        "import org.osmdroid.util.GeoPoint",
        "",
        "/**",
        " * Simplified outline of Ukraine (incl. Crimea): the closed outer hull of the oblast",
        " * boundary polygons (OblastBoundaries.kt) — same source (EugeneBorshch/ukraine_geojson),",
        " * same simplification — so the silhouette coincides with the combined red oblast fills.",
        " * Regenerate with tools/gen_ukraine_border.py. Values are (lat, lon) GeoPoints.",
        " */",
        "val UKRAINE_BORDER: List<GeoPoint> = listOf(",
    ]
    lines.extend(geo_strs(border_pts))
    lines.append(")")
    lines.append("")
    lines.append("/**")
    lines.append(" * The land border of Ukraine as an open polyline: the same country outer ring with")
    lines.append(" * every sea-coastline edge removed (edges whose midpoint lies in the ocean polygon,")
    lines.append(" * Natural Earth 10m). Drawn as an osmdroid Polyline so the map's white outline hugs")
    lines.append(" * the land borders — including river borders — and never crosses the Black Sea or the")
    lines.append(" * Sea of Azov. Regenerate with tools/gen_ukraine_border.py.")
    lines.append(" */")
    lines.append("val UKRAINE_LAND_BORDER: List<GeoPoint> = listOf(")
    lines.extend(geo_strs(land_pts))
    lines.append(")")
    lines.append("")

    os.makedirs(os.path.dirname(OUT_FILE), exist_ok=True)
    with open(OUT_FILE, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    print(f"Done: UKRAINE_BORDER {len(border_pts)} pts, UKRAINE_LAND_BORDER {len(land_pts)} pts -> {OUT_FILE}")


def main():
    print(f"Fetching {GEOJSON_URL}...")
    full = outer_ring()
    print(f"  outer ring: {len(full)} points")

    land_edges = classify_edges(full)
    dropped = len(full) - 1 - len(land_edges)
    print(f"  {len(land_edges)} land edges, {dropped} coastline edges dropped")

    land_border = chain_land_edges(full, land_edges)
    print(f"  land-border chain: {len(land_border)} points")

    emit_kt(full, land_border)


if __name__ == "__main__":
    main()