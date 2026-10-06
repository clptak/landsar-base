#!/usr/bin/env python3
"""Build a LandSAR Base disk geocache (the format of geocache-va) for any lat/lon box.

Landcover: NLCD 2019 (L48) from the MRLC WCS.  Elevation: USGS 3DEP ImageServer.
Both are resampled to ~30 m cells on a 0.25 deg (lat) x 0.3 deg (lon) tile grid
anchored at the south-west corner of the box, matching how geocache-va is laid out.

    python3 tools/build_geocache.py geocache-coconino 34.258411 37.003102 -113.354176 -110.750671
    python3 tools/build_geocache.py geocache-x S N W E --dry-run      # plan only, no network
    python3 tools/build_geocache.py geocache-x S N W E --margin-km 0  # no margin

S N W E is the area you want to serve. The script pads it by --margin-km (default 20) on every
side, because LandSAR enlarges each search to a minimum box of about 31 x 32 km and refuses any
search whose box reaches past the cache edge; the margin keeps starts near the edge usable.

Re-running skips tiles that are already complete (LandcoverLatLon.txt is written last), but only
if they match the planned grid. A different box or margin shifts the grid, so use a new out_dir.
Requires GDAL's Python bindings and numpy.
"""
import argparse
import math
import os
import shutil
import sys
import time
import urllib.parse
import urllib.request

import numpy as np
from osgeo import gdal

gdal.UseExceptions()

TILE_LAT = 0.25
TILE_LON = 0.30
CELL_M = 30.0
M_PER_DEG = 111111.11
PAD = 0.01          # degrees of extra data requested around each tile
DEM_DEG_PER_PX = 0.0002   # keeps 3DEP requests well under its image size limit

NLCD_WCS = ("https://www.mrlc.gov/geoserver/mrlc_download/wcs?service=WCS&version=2.0.1"
            "&request=GetCoverage&coverageId=mrlc_download__NLCD_2019_Land_Cover_L48"
            "&format=image/geotiff&subsettingCrs=http://www.opengis.net/def/crs/EPSG/0/4326"
            "&subset=Long({w},{e})&subset=Lat({s},{n})")
DEM_EXPORT = ("https://elevation.nationalmap.gov/arcgis/rest/services/3DEPElevation/ImageServer/exportImage"
              "?bbox={w},{s},{e},{n}&bboxSR=4326&size={px},{py}&imageSR=4326&format=tiff&pixelType=F32&f=image")


def fetch(url, retries=4):
    """Download a URL, returning bytes that look like a TIFF; retry with backoff."""
    last = None
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(url, timeout=180) as r:
                data = r.read()
            if data[:4] in (b"II*\x00", b"MM\x00*"):
                return data
            last = "non-TIFF response: %r" % data[:120]
        except Exception as ex:  # noqa: BLE001 - report and retry any network error
            last = repr(ex)
        time.sleep(5 * (attempt + 1))
    raise RuntimeError("download failed: %s (%s)" % (last, url[:140]))


def warp_to_grid(data, name, bounds, ncols, nrows, alg):
    """Resample an in-memory GeoTIFF onto the exact tile grid; returns a float32 array."""
    vsi = "/vsimem/%s.tif" % name
    gdal.FileFromMemBuffer(vsi, data)
    try:
        s, n, w, e = bounds
        ds = gdal.Warp("", vsi, format="MEM", outputBounds=(w, s, e, n), width=ncols, height=nrows,
                       dstSRS="EPSG:4326", resampleAlg=alg, outputType=gdal.GDT_Float32, dstNodata=-9999)
        return ds.GetRasterBand(1).ReadAsArray()
    finally:
        gdal.Unlink(vsi)


def write_grid(path, arr, west, south):
    header = ("ncols         %d\nnrows         %d\nxllcorner     %s\nyllcorner     %s\n"
              "cellsize      1.0E-5\nNODATA_value  -9999\n") % (arr.shape[1], arr.shape[0], west, south)
    with open(path, "w") as f:
        f.write(header)
        for row in arr:
            f.write(" ".join(map(str, row)) + " \n")


def write_png(path, rgb):
    h, w = rgb.shape[:2]
    mem = gdal.GetDriverByName("MEM").Create("", w, h, 3, gdal.GDT_Byte)
    for i in range(3):
        mem.GetRasterBand(i + 1).WriteArray(rgb[:, :, i])
    gdal.GetDriverByName("PNG").CreateCopy(path, mem)


def load_palette(meta_path):
    pal = {}
    with open(meta_path) as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) >= 8:
                pal[int(p[0])] = (int(p[5]), int(p[6]), int(p[7]))
    return pal


def build_tile(out_dir, ix, iy, bounds, palette, make_png):
    s, n, w, e = bounds
    mid = (s + n) / 2.0
    nrows = int(math.floor(TILE_LAT * M_PER_DEG / CELL_M))
    ncols = int(round(TILE_LON * M_PER_DEG * math.cos(math.radians(mid)) / CELL_M))
    pw, pe, ps, pn = w - PAD, e + PAD, s - PAD, n + PAD

    lc_raw = fetch(NLCD_WCS.format(w=pw, e=pe, s=ps, n=pn))
    time.sleep(1)
    px = int(math.ceil((pe - pw) / DEM_DEG_PER_PX))
    py = int(math.ceil((pn - ps) / DEM_DEG_PER_PX))
    dem_raw = fetch(DEM_EXPORT.format(w=pw, e=pe, s=ps, n=pn, px=px, py=py))
    time.sleep(1)

    lc = warp_to_grid(lc_raw, "lc%d_%d" % (ix, iy), bounds, ncols, nrows, "near")
    elev = warp_to_grid(dem_raw, "dem%d_%d" % (ix, iy), bounds, ncols, nrows, "average")
    elev = np.where(np.isnan(elev) | (elev <= -9000), -9999, np.rint(elev)).astype(int)
    lc = np.where(np.isnan(lc) | (lc <= 0), -9999, lc).astype(int)

    tile_dir = os.path.join(out_dir, "%d-%d" % (ix, iy))
    os.makedirs(tile_dir, exist_ok=True)
    write_grid(os.path.join(tile_dir, "elevation.txt"), elev, w, s)
    write_grid(os.path.join(tile_dir, "landcover.txt"), lc, w, s)
    if make_png:
        rgb = np.zeros(lc.shape + (3,), np.uint8)
        for code, color in palette.items():
            rgb[lc == code] = color
        valid = elev > -9000
        lo, hi = (elev[valid].min(), elev[valid].max()) if valid.any() else (0, 1)
        g = np.clip((elev - lo) / max(hi - lo, 1) * 255, 0, 255).astype(np.uint8)
        write_png(os.path.join(tile_dir, "landcover.png"), rgb)
        write_png(os.path.join(tile_dir, "elevation.png"), np.dstack([g, g, g]))
    # Written last: marks the tile complete so a re-run can skip it.
    with open(os.path.join(tile_dir, "LandcoverLatLon.txt"), "w") as f:
        f.write("%s,%s,%s,%s" % (s, n, w, e))
    return ncols, nrows, int((lc == -9999).sum()), int((elev == -9999).sum())


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("out_dir")
    ap.add_argument("south", type=float)
    ap.add_argument("north", type=float)
    ap.add_argument("west", type=float)
    ap.add_argument("east", type=float)
    ap.add_argument("--margin-km", type=float, default=20.0,
                    help="pad the box by this many km on every side (default 20; 0 for none)")
    ap.add_argument("--metadata-from", default="geocache-va",
                    help="existing cache to copy landcovertype.txt / landcoverMetadata.txt from")
    ap.add_argument("--no-png", action="store_true", help="skip the PNG previews")
    ap.add_argument("--dry-run", action="store_true", help="print the tile plan and exit")
    args = ap.parse_args()

    if args.margin_km > 0:
        dlat = args.margin_km * 1000.0 / M_PER_DEG
        # Use the latitude farthest from the equator so the margin is at least margin_km everywhere.
        dlon = dlat / math.cos(math.radians(max(abs(args.south), abs(args.north)) + dlat))
        args.south, args.north = round(args.south - dlat, 6), round(args.north + dlat, 6)
        args.west, args.east = round(args.west - dlon, 6), round(args.east + dlon, 6)
        print("Requested box padded by %.1f km -> S,N,W,E = %.6f,%.6f,%.6f,%.6f" %
              (args.margin_km, args.south, args.north, args.west, args.east))

    nx = int(math.ceil((args.east - args.west) / TILE_LON - 1e-9))
    ny = int(math.ceil((args.north - args.south) / TILE_LAT - 1e-9))
    cache = (args.south, args.south + ny * TILE_LAT, args.west, args.west + nx * TILE_LON)
    print("Tiles: %d columns x %d rows = %d" % (nx, ny, nx * ny))
    print("Cache bounds (S,N,W,E): %.6f,%.6f,%.6f,%.6f" % cache)
    if args.dry_run:
        return 0

    for fname in ("landcovertype.txt", "landcoverMetadata.txt"):
        src = os.path.join(args.metadata_from, fname)
        if not os.path.exists(src):
            sys.exit("missing %s (use --metadata-from)" % src)
    os.makedirs(args.out_dir, exist_ok=True)
    for fname in ("landcovertype.txt", "landcoverMetadata.txt"):
        shutil.copyfile(os.path.join(args.metadata_from, fname), os.path.join(args.out_dir, fname))
    palette = load_palette(os.path.join(args.out_dir, "landcoverMetadata.txt"))

    failed, done, t0 = [], 0, time.time()
    total = nx * ny
    for ix in range(nx):
        for iy in range(ny):
            w = round(args.west + ix * TILE_LON, 6)
            e = round(args.west + (ix + 1) * TILE_LON, 6)
            s = round(args.south + iy * TILE_LAT, 6)
            n = round(args.south + (iy + 1) * TILE_LAT, 6)
            tag = "%d-%d" % (ix, iy)
            bounds_file = os.path.join(args.out_dir, tag, "LandcoverLatLon.txt")
            if os.path.exists(bounds_file):
                have = [float(v) for v in open(bounds_file).read().strip().split(",")]
                if any(abs(a - b) > 1e-6 for a, b in zip(have, (s, n, w, e))):
                    sys.exit("%s exists with different bounds %s than planned %s - this grid does not "
                             "match the existing cache; use a new out_dir" % (tag, have, (s, n, w, e)))
                done += 1
                print("[%d/%d] %s exists, skipping" % (done, total, tag), flush=True)
                continue
            try:
                ncols, nrows, lc_bad, el_bad = build_tile(args.out_dir, ix, iy, (s, n, w, e), palette,
                                                           not args.no_png)
                done += 1
                print("[%d/%d] %s ok  %dx%d  nodata lc=%d elev=%d  (%.0fs elapsed)" %
                      (done, total, tag, ncols, nrows, lc_bad, el_bad, time.time() - t0), flush=True)
            except Exception as ex:  # noqa: BLE001
                failed.append(tag)
                print("[%s] FAILED: %s" % (tag, ex), flush=True)
    print("Done. %d/%d tiles complete, %d failed%s" %
          (done, total, len(failed), (": " + ", ".join(failed)) if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
