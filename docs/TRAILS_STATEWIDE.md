# Runbook: trails and roads for the whole state

Status: **untested at statewide scale**. Only two tiles (`11-21`, `11-22`, Coconino County) have been built so far.
Times below are guesses. Each step says what to expect.

## What happens

One scan of an OpenStreetMap extract pulls every road and trail line into a local GeoPackage
(`geocache-az-state/osm-highways.gpkg`). Then, for each cache tile, `tools/build_trails.py` burns those lines into a
30 m grid and writes `trails.txt` beside the tile's `elevation.txt` (0 = none, 1 = trail, 2 = road). The terrain-walk
model reads `trails.txt` itself (LandSAR Base ignores it); tiles without the file simply have no trails.
Run it on the machine that holds the cache (the Mac), then copy only the `trails.txt` files to the VPS.

## 1. Check disk space and tools

```bash
df -h ~ | tail -1; python3 -c "from osgeo import gdal, ogr; import numpy; print('gdal', gdal.__version__)"
```

Allow about 1 GB free for the output (the two finished tiles are about 1.5 MB each, so roughly 750 MB for 504 tiles),
plus a few GB of temporary space while GDAL reads the OSM file.

## 2. Download the Arizona extract

From Geofabrik's Arizona page, `download.geofabrik.de/north-america/us/arizona.html`, get `arizona-latest.osm.pbf`
(a few hundred MB). Data is OpenStreetMap, so attribution applies if maps built from it are shared.

## 3. Build

```bash
cd ~/dev/landsar-base && caffeinate -i python3 tools/build_trails.py ~/Downloads/arizona-latest.osm.pbf geocache-az-state --all 2>&1 | tee ~/trails-build.log
```

- First phase prints `extracting highway ways ... reads the whole file once` and nothing more until done. Expected to
  be the longest part (guess: 10 to 40 minutes).
- Then one line per tile: `11-21: N trail cells, M road cells`.
- Resumable: tiles that already have `trails.txt` are skipped (so Coconino is untouched), and a rerun after Ctrl-C
  continues with the rest. Do not add `--overwrite` unless you want finished tiles rebuilt.
- If the extraction runs out of memory or disk, set `OSM_COMPRESS_NODES=YES` in the environment and rerun.
- To build only part of the state, give `S N W E` instead of `--all`; each pass that needs a larger area than the
  GeoPackage covers re-reads the extract, so keep passes few.

## 4. Check

```bash
find geocache-az-state -name trails.txt | wc -l
tail ~/trails-build.log
```

Expect one file per cache tile (504 for the statewide cache). Look for Python errors, and for tiles reporting
0 trail and 0 road cells where roads exist.

## 5. Copy to the VPS and restart

Check free space on the VPS first (`df -h ~`, about 1 GB needed). From the Mac:

```bash
rsync -avP --include='*/' --include='trails.txt' --exclude='*' geocache-az-state/ takadmin@tak.clpdevtak.com:tak-stack/geocache-az-state/
```

This sends only `trails.txt` files (not the multi-hundred-MB GeoPackage). The cache is read at startup, so on the VPS:

```bash
cd ~/tak-stack && docker compose restart landsar
docker compose logs landsar | grep "TerrainWalk: trails from" | cut -c1-200
```

The log line should report 504 tiles with `trails.txt`. `running without trails` or `0 tiles` means the files were not
found: check the `"trail cache directory"` entry in `config.json` (set to `geocache-az-state`).

## 6. Test

Run a search where trails exist (near Flagstaff), then one elsewhere (Tucson), with `trail factor` about 0.3, and see
whether paths prefer trails and roads.

## Unknowns

- Statewide extraction time and temporary disk use.
- VPS memory with all trail tiles loaded; watch `docker stats --no-stream` during a statewide search.
- What counts as a road is the script's rule: any OSM `highway` value except the trail values, motorways and a few
  non-walkable types. Not changed here.
