#!/usr/bin/env python3
"""Make resources/public/world-110m-land.path, the land outline of the /map page.

Input: Natural Earth 1:110m land polygons (public domain), the file
geojson/ne_110m_land.geojson of https://github.com/nvkelso/natural-earth-vector.
Output: one SVG path (the `d` attribute, plain text) in the equirectangular
projection of pages/project: x = (lon + 180) * 2, y = (90 - lat) * 2, so the
canvas is 720 x 360 units. Coordinates are rounded to 1 decimal; each ring is
simplified with Douglas-Peucker (tolerance TOLERANCE units) and rings smaller
than MIN_AREA square units are dropped.

Usage: python3 -I scripts/make-world-path.py ne_110m_land.geojson resources/public/world-110m-land.path
Standard library only.
"""

import json
import sys

TOLERANCE = 0.35  # units (1 unit = 0.5 degree)
MIN_AREA = 0.5  # square units


def project(lon, lat):
    return ((lon + 180.0) * 2.0, (90.0 - lat) * 2.0)


def perpendicular(p, a, b):
    (x, y), (x1, y1), (x2, y2) = p, a, b
    dx, dy = x2 - x1, y2 - y1
    if dx == 0 and dy == 0:
        return ((x - x1) ** 2 + (y - y1) ** 2) ** 0.5
    t = max(0.0, min(1.0, ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy)))
    return ((x - x1 - t * dx) ** 2 + (y - y1 - t * dy) ** 2) ** 0.5


def douglas_peucker(points, tol):
    """Iterative Douglas-Peucker on an open polyline; keeps both ends."""
    if len(points) < 3:
        return list(points)
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        i, j = stack.pop()
        best, index = 0.0, None
        for k in range(i + 1, j):
            d = perpendicular(points[k], points[i], points[j])
            if d > best:
                best, index = d, k
        if index is not None and best > tol:
            keep[index] = True
            stack.append((i, index))
            stack.append((index, j))
    return [p for p, k in zip(points, keep) if k]


def area(ring):
    return abs(sum(x1 * y2 - x2 * y1 for (x1, y1), (x2, y2) in zip(ring, ring[1:] + ring[:1]))) / 2.0


def fmt(v):
    s = "%.1f" % v
    if s.endswith(".0"):
        s = s[:-2]
    if s == "-0":
        s = "0"
    return s


def rings_of(geometry):
    if geometry["type"] == "Polygon":
        yield from geometry["coordinates"]
    elif geometry["type"] == "MultiPolygon":
        for polygon in geometry["coordinates"]:
            yield from polygon


def main(src, dst):
    with open(src, encoding="utf-8") as f:
        data = json.load(f)
    parts, before, after = [], 0, 0
    for feature in data["features"]:
        for ring in rings_of(feature["geometry"]):
            before += len(ring)
            points = [project(lon, lat) for lon, lat in ring]
            if len(points) > 1 and points[0] == points[-1]:
                points = points[:-1]
            # split the closed ring at its farthest point from the start so
            # Douglas-Peucker works on two open polylines
            far = max(range(len(points)), key=lambda k: (points[k][0] - points[0][0]) ** 2 + (points[k][1] - points[0][1]) ** 2)
            simple = douglas_peucker(points[: far + 1], TOLERANCE)[:-1] + douglas_peucker(points[far:] + points[:1], TOLERANCE)[:-1]
            rounded = []
            for x, y in simple:
                p = (round(x, 1), round(y, 1))
                if not rounded or rounded[-1] != p:
                    rounded.append(p)
            if len(rounded) > 1 and rounded[0] == rounded[-1]:
                rounded.pop()
            if len(rounded) < 3 or area(rounded) < MIN_AREA:
                continue
            after += len(rounded)
            head, rest = rounded[0], rounded[1:]
            parts.append("M" + fmt(head[0]) + " " + fmt(head[1]) + "L" + " ".join(fmt(x) + " " + fmt(y) for x, y in rest) + "Z")
    d = "".join(parts)
    with open(dst, "w", encoding="utf-8") as f:
        f.write(d + "\n")
    print("%s: %d rings, %d points (from %d), %d bytes" % (dst, len(parts), after, before, len(d) + 1))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
