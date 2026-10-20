#!/usr/bin/env python3
"""Add a trails grid (trails.txt) to LandSAR Base geocache tiles from an OpenStreetMap .osm.pbf extract.

    python3 tools/build_trails.py ~/Downloads/COCO.osm.pbf geocache-az-state 36.80 36.95 -111.70 -111.50
    python3 tools/build_trails.py ~/Downloads/Arizona.osm.pbf geocache-az-state --all

S N W E is the area to cover; every cache tile that touches it gets a trails.txt on exactly the same
grid as its elevation.txt (same ncols/nrows/corner, ESRI ASCII format, header style of the other grids):

    0 = nothing   1 = trail (OSM highway = path, footway, track, bridleway, steps, cycleway, pedestrian)
    2 = road      (any other OSM highway value except the excluded ones below)

A cell is marked if the way touches it at all (ALL_TOUCHED), so thin lines stay continuous at 30 m.
Where a trail and a road share a cell the road wins. LandSAR Base itself ignores trails.txt; the
terrain-walk motion model reads it (see models/terrain-walk/README.md).

The OSM ways are first extracted once into <cache>/osm-highways.gpkg (clipped to the requested area and
filtered to highway=*), so re-running for a nearby area is fast; use --refresh to re-extract from the pbf.

Requires GDAL's Python bindings with the OSM driver (any normal GDAL build) and numpy.
"""
import argparse
import math
import os
import sys

import numpy as np
from osgeo import gdal, ogr, osr

gdal.UseExceptions()

TRAIL = ("path", "footway", "track", "bridleway", "steps", "cycleway", "pedestrian")
NOT_WALKABLE = ("motorway", "motorway_link", "proposed", "construction", "razed", "abandoned",
                "bus_guideway", "raceway", "corridor", "elevator", "platform")
GPKG_NAME = "osm-highways.gpkg"


def tile_bounds(cache):
    """{tag: (s, n, w, e)} for every complete tile in the cache."""
    out = {}
    for tag in os.listdir(cache):
        bf = os.path.join(cache, tag, "LandcoverLatLon.txt")
        if os.path.isfile(bf):
            s, n, w, e = [float(v) for v in open(bf).read().strip().split(",")]
            out[tag] = (s, n, w, e)
    return out


def grid_shape(path):
    ncols = nrows = None
    with open(path) as f:
        for _ in range(6):
            k, v = f.readline().split()
            if k == "ncols":
                ncols = int(v)
            elif k == "nrows":
                nrows = int(v)
    return ncols, nrows


def write_grid(path, arr, west, south):
    header = ("ncols         %d\nnrows         %d\nxllcorner     %s\nyllcorner     %s\n"
              "cellsize      1.0E-5\nNODATA_value  -9999\n") % (arr.shape[1], arr.shape[0], west, south)
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        f.write(header)
        for row in arr:
            f.write(" ".join(map(str, row)) + " \n")
    os.replace(tmp, path)


def extract_highways(pbf, gpkg, s, n, w, e):
    """Pull highway=* lines inside the box out of the pbf into a GeoPackage (one scan of the pbf)."""
    if os.path.exists(gpkg):
        os.remove(gpkg)
    print("extracting highway ways from %s (this reads the whole file once) ..." % pbf)
    gdal.SetConfigOption("OGR_INTERLEAVED_READING", "YES")
    gdal.VectorTranslate(gpkg, pbf, options=gdal.VectorTranslateOptions(
        format="GPKG", layers=["lines"], layerName="highways",
        spatFilter=(w, s, e, n), where="highway IS NOT NULL",
        selectFields=["highway", "name"]))
    ds = ogr.Open(gpkg)
    print("  %d ways" % ds.GetLayer(0).GetFeatureCount())
    ds = None


def rasterize(gpkg, where, s, n, w, e, ncols, nrows, value, into):
    """Burn matching ways into the numpy array `into` (rows north to south)."""
    src = ogr.Open(gpkg)
    lyr = src.GetLayer(0)
    lyr.SetAttributeFilter(where)
    lyr.SetSpatialFilterRect(w, s, e, n)
    if lyr.GetFeatureCount() == 0:
        return 0
    mem = gdal.GetDriverByName("MEM").Create("", ncols, nrows, 1, gdal.GDT_Byte)
    mem.SetGeoTransform((w, (e - w) / ncols, 0.0, n, 0.0, -(n - s) / nrows))
    srs = osr.SpatialReference()
    srs.ImportFromEPSG(4326)
    mem.SetProjection(srs.ExportToWkt())
    gdal.RasterizeLayer(mem, [1], lyr, burn_values=[1], options=["ALL_TOUCHED=TRUE"])
    hit = mem.GetRasterBand(1).ReadAsArray() > 0
    into[hit] = value
    return int(hit.sum())


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("pbf")
    ap.add_argument("cache")
    ap.add_argument("bounds", nargs="*", type=float, metavar="S N W E")
    ap.add_argument("--all", action="store_true", help="every tile in the cache")
    ap.add_argument("--refresh", action="store_true", help="re-extract from the pbf even if the GeoPackage exists")
    ap.add_argument("--overwrite", action="store_true", help="rebuild tiles that already have trails.txt")
    args = ap.parse_args()

    tiles = tile_bounds(args.cache)
    if not tiles:
        sys.exit("no tiles with LandcoverLatLon.txt under " + args.cache)
    if args.all:
        wanted = dict(tiles)
    elif len(args.bounds) == 4:
        S, N, W, E = args.bounds
        wanted = {t: b for t, b in tiles.items() if b[0] < N and b[1] > S and b[2] < E and b[3] > W}
    else:
        sys.exit("give S N W E or --all")
    if not wanted:
        sys.exit("no cache tiles touch that area")
    S = min(b[0] for b in wanted.values()); N = max(b[1] for b in wanted.values())
    W = min(b[2] for b in wanted.values()); E = max(b[3] for b in wanted.values())
    print("%d tile(s): lat %.3f..%.3f lon %.3f..%.3f" % (len(wanted), S, N, W, E))

    gpkg = os.path.join(args.cache, GPKG_NAME)
    box_file = gpkg + ".bounds"
    have = open(box_file).read().split(",") if os.path.exists(box_file) else None
    covered = have is not None and float(have[0]) <= S and float(have[1]) >= N and float(have[2]) <= W and float(have[3]) >= E
    if args.refresh or not os.path.exists(gpkg) or not covered:
        extract_highways(args.pbf, gpkg, S, N, W, E)
        with open(box_file, "w") as f:
            f.write("%s,%s,%s,%s" % (S, N, W, E))

    trail_where = "highway IN (%s)" % ",".join("'%s'" % v for v in TRAIL)
    road_where = "highway NOT IN (%s)" % ",".join("'%s'" % v for v in TRAIL + NOT_WALKABLE)
    for tag in sorted(wanted, key=lambda t: tuple(int(i) for i in t.split("-"))):
        s, n, w, e = wanted[tag]
        out = os.path.join(args.cache, tag, "trails.txt")
        if os.path.exists(out) and not args.overwrite:
            print(tag, "already has trails.txt (use --overwrite)")
            continue
        ncols, nrows = grid_shape(os.path.join(args.cache, tag, "elevation.txt"))
        grid = np.zeros((nrows, ncols), np.int16)
        nt = rasterize(gpkg, trail_where, s, n, w, e, ncols, nrows, 1, grid)
        nr = rasterize(gpkg, road_where, s, n, w, e, ncols, nrows, 2, grid)
        write_grid(out, grid, w, s)
        print("%s: %d trail cells, %d road cells -> %s" % (tag, nt, nr, out))


if __name__ == "__main__":
    main()
