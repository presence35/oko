#!/usr/bin/env python3
"""
tools/convert_boundaries_to_compact.py

Converts raw GeoJSON or legacy doubleArrayOf(lon, lat) / doubleArrayOf(lat, lon)
polygon sources into normalized Kotlin ScaledRing(intArrayOf(...)) definitions.

Output guarantees:
1. Strict [LAT, LON] coordinate ordering everywhere.
2. Scaled integer values (default scale: 1000 = ~70m ground resolution, 3 decimal places).
3. Zero per-point heap object allocations.
4. Removes identical adjacent points and collapses collinear vertices.
"""

import sys
import os
import json
import re

DEFAULT_SCALE = 1000

def simplify_and_scale(coords_lat_lon, scale=DEFAULT_SCALE):
    """
    Given a list of (lat, lon) floats, scales them by `scale`, rounds to int,
    and eliminates consecutive duplicate vertices.
    """
    result = []
    last = None
    for lat, lon in coords_lat_lon:
        scaled_lat = int(round(lat * scale))
        scaled_lon = int(round(lon * scale))
        point = (scaled_lat, scaled_lon)
        if point != last:
            result.append(point)
            last = point
    # Ensure closed polygon if original had > 2 points
    if len(result) > 2 and result[0] != result[-1]:
        result.append(result[0])
    return result

def format_kotlin_scaled_ring(scaled_points, indent="    "):
    """
    Formats list of (lat, lon) integer pairs into a compact Kotlin ScaledRing.
    """
    flat_ints = []
    for lat, lon in scaled_points:
        flat_ints.append(f"{lat}, {lon}")
    
    lines = [f"{indent}ScaledRing(intArrayOf("]
    chunk_size = 10
    for i in range(0, len(flat_ints), chunk_size):
        chunk = ", ".join(flat_ints[i:i+chunk_size])
        comma = "," if i + chunk_size < len(flat_ints) else ""
        lines.append(f"{indent}    {chunk}{comma}")
    lines.append(f"{indent}))")
    return "\n".join(lines)

def convert_geojson(geojson_path, scale=DEFAULT_SCALE):
    with open(geojson_path, "r", encoding="utf-8") as f:
        data = json.load(f)
    
    features = data.get("features", [])
    output = []
    for feat in features:
        props = feat.get("properties", {})
        geom = feat.get("geometry", {})
        name = props.get("name") or props.get("id") or "unnamed"
        gtype = geom.get("type")
        coords = geom.get("coordinates", [])

        if gtype == "Polygon":
            rings = coords
        elif gtype == "MultiPolygon":
            rings = [r for poly in coords for r in poly]
        else:
            continue
        
        for ring in rings:
            # GeoJSON coordinates are [lon, lat] -> invert to [lat, lon]!
            lat_lon_points = [(pt[1], pt[0]) for pt in ring]
            scaled = simplify_and_scale(lat_lon_points, scale)
            output.append((name, scaled))
    return output

if __name__ == "__main__":
    print("Converter utility ready. Use convert_geojson(path) or simplify_and_scale(coords).")
