#!/usr/bin/env python3
"""Replays a GPX route as GPS fixes in the Android emulator, to test the navigation.

Usage:
  python pipeline/tools/simulate-ride.py ROUTE.gpx [--speed 5] [--from-m 0] [--to-m END]
                                         [--detour-at M --detour-m 120 --detour-s 15]

Moves along the route's points at --speed metres per second, one fix per second, through the emulator's
test GPS provider (`cmd location providers set-test-provider-location`; `geo fix` does not work on the
LisBora_API37 image). --detour-at leaves the route sideways for --detour-s seconds at that distance, to
test going off route. Needs adb on the PATH or ANDROID_HOME set. Standard library only.
"""
import argparse
import math
import os
import re
import subprocess
import sys
import time

EARTH_RADIUS_M = 6_371_008.8


def adb():
    home = os.environ.get("ANDROID_HOME")
    if home:
        exe = os.path.join(home, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
        if os.path.exists(exe):
            return exe
    return "adb"


def read_points(path):
    text = open(path, encoding="utf-8").read()
    for tag in ("trkpt", "rtept"):
        points = []
        for attributes in re.findall(r"<%s\b([^>]*)>" % tag, text):
            lat = re.search(r'\blat="([^"]+)"', attributes)
            lon = re.search(r'\blon="([^"]+)"', attributes)
            if lat and lon:
                points.append((float(lat.group(1)), float(lon.group(1))))
        if points:
            return points
    sys.exit("No points in " + path)


def distance(a, b):
    dlat = math.radians(b[0] - a[0])
    dlon = math.radians(b[1] - a[1])
    h = math.sin(dlat / 2) ** 2 + math.cos(math.radians(a[0])) * math.cos(math.radians(b[0])) * math.sin(dlon / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(min(1.0, math.sqrt(h)))


def point_at(points, along, m):
    """The point m metres along the polyline, and the direction (unit dx, dy in metres) there."""
    i = max(0, min(len(points) - 2, next((k for k in range(len(along) - 1) if along[k + 1] >= m), len(points) - 2)))
    span = along[i + 1] - along[i]
    t = 0 if span <= 0 else max(0.0, min(1.0, (m - along[i]) / span))
    a, b = points[i], points[i + 1]
    p = (a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]))
    dx = (b[1] - a[1]) * math.cos(math.radians(a[0]))
    dy = b[0] - a[0]
    norm = math.hypot(dx, dy) or 1.0
    return p, (dx / norm, dy / norm)


def offset(p, east_m, north_m):
    lat = p[0] + north_m / (math.pi / 180 * EARTH_RADIUS_M)
    lon = p[1] + east_m / (math.pi / 180 * EARTH_RADIUS_M * math.cos(math.radians(p[0])))
    return lat, lon


def send(exe, p):
    subprocess.run([exe, "shell", "cmd", "location", "providers", "set-test-provider-location", "gps",
                    "--location", "%.6f,%.6f" % p, "--accuracy", "5"], check=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("gpx")
    parser.add_argument("--speed", type=float, default=5.0, help="metres per second (5 = 18 km/h)")
    parser.add_argument("--from-m", type=float, default=0.0)
    parser.add_argument("--to-m", type=float, default=None)
    parser.add_argument("--detour-at", type=float, default=None)
    parser.add_argument("--detour-m", type=float, default=120.0)
    parser.add_argument("--detour-s", type=int, default=15)
    args = parser.parse_args()

    points = read_points(args.gpx)
    along = [0.0]
    for a, b in zip(points, points[1:]):
        along.append(along[-1] + distance(a, b))
    end = min(args.to_m if args.to_m is not None else along[-1], along[-1])
    exe = adb()
    print("%d points, %.0f m; riding %.0f-%.0f m at %.1f m/s" % (len(points), along[-1], args.from_m, end, args.speed))
    m = args.from_m
    detoured = False
    while m <= end:
        p, (dx, dy) = point_at(points, along, m)
        if args.detour_at is not None and not detoured and m >= args.detour_at:
            detoured = True
            # Sideways (to the right of the direction of travel), then back.
            away = offset(p, dy * args.detour_m, -dx * args.detour_m)
            print("detour at %.0f m" % m, flush=True)
            for _ in range(args.detour_s):
                send(exe, away)
                time.sleep(1)
        send(exe, p)
        print("%6.0f m  %.6f,%.6f" % (m, p[0], p[1]), flush=True)
        time.sleep(1)
        m += args.speed
    p, _ = point_at(points, along, end)
    send(exe, p)
    print("done")


if __name__ == "__main__":
    main()
