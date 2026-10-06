# Plan: serve Arizona elevation and landcover from a local GeoServer

Status: **plan only**. Nothing in this document has been built or run. Written after the statewide
disk cache (`geocache-az-state/`) was started; the disk cache remains the working fallback.

## Why consider it

The disk cache (`geo cache directory`) works, but it is a fixed rectangle: every new area means a rebuild,
searches within about 17 km of an edge fail, and the patched loader keeps every tile it has read in memory
(about 6.4 MB per 0.25 x 0.30 degree tile). The vendor's own design is `"geodata mode": "geoserver"`: for each
search LandSAR requests only the search's bounding box from a GeoServer. A local GeoServer would remove the
rebuilds, the edge margins and the memory growth, at the cost of running a service.

## What LandSAR needs from a GeoServer

Read from the compiled code (`OsppreConfiguration`, `FinderUtils.createWCSElevationModel`,
`AbstractLandcoverDownloader`) and checked against MRLC's public GeoServer. Items marked **(unverified)**
have not been tested against a GeoServer of our own.

`config.json`, in the `MotionModelCoordinatorPlugin` block:

| Key | Value for this setup |
|---|---|
| `geodata mode` | `geoserver` (default in the code is `internet`; `disk` is what we use today) |
| `geoserver url` | e.g. `http://localhost:8080/geoserver/` |
| `elevation layer` | `workspace:layer` of the DEM, e.g. `az:dem` |
| `landcover layer` | `workspace:layer` of NLCD, e.g. `az:nlcd2019` |
| `landcover load format` | `geotiff` |
| `geoserver null value` | the DEM's NoData value, e.g. `-9999` (optional) |
| `geoserver layers credentials` | `user:password`, if the layers are protected (optional) |
| `geoserver ca certificate path` | only for HTTPS with a private CA (optional) |

Requests LandSAR makes:

- **Elevation: WCS 1.0.0.** `wcs?request=getCapabilities`, `describeCoverage`, then `getCoverage` for the
  search box, GeoTIFF, little-endian (NASA WorldWind's `WCS100Capabilities` / `WCSElevationModel`).
- **Landcover: WMS 1.1.0 `GetMap`**, `format=image/geotiff`, `srs=EPSG:4326`,
  `bbox=MIN_LON,MIN_LAT,MAX_LON,MAX_LAT`, with `width`/`height` for the output grid.
- **Landcover legend: `GetLegendGraphic`, `format=application/json`.**

**Important detail.** MRLC's `image/geotiff` output is a single-band **palette image**: the pixel values are
indices into the style's colour map (observed range 1 to 21), not NLCD codes. The JSON legend supplies, for
each colour-map entry, the NLCD code (`quantity`), a label such as `"Open Water (11)"`, and the colour. So the
landcover layer needs an NLCD style with a colour map in that form; unstyled raster data will not work.
**(unverified)** exactly how LandSAR combines the palette and the legend, which is the main thing to test.

Other behaviour to remember (these apply to any geodata source):

- LandSAR enlarges every search to a minimum box of about 31 x 32 km.
- The landcover class codes must exist in `landcoverMetadata.txt` / `defaultParams/landcover-defaults.txt`.
  A no-data landcover cell crashes the example model with a `NullPointerException`.
- The example model treats elevation and landcover at 30 m.

## Data to host (Arizona)

- **Elevation:** USGS 1 arc-second DEM (about 30 m, matches LandSAR). 54 one-degree tiles cover the state plus
  a margin, about **2.7 GB** in total (USGS catalogue, newest version of each tile, measured 2026-10-05).
- **Landcover:** NLCD 2019, land cover (the 2019 release is what BBN's own configuration names). Clip to
  Arizona plus margin from MRLC's WCS (`mrlc_download__NLCD_2019_Land_Cover_L48`), the same source
  `tools/build_geocache.py` uses, instead of the national download. The clipped size has not been measured.
- Outside the US (the Mexico side of the border) NLCD has no data. `tools/build_geocache.py` fills it from
  ESA WorldCover 2021, mapped to NLCD classes. A GeoServer deployment would need the same solution, or a
  boundary at the border.

Assumption to confirm: "2019" refers to NLCD 2019 land cover. Hydrology data is not used by LandSAR here.

## Plan

Total: roughly one working day elapsed, with about half of it unattended downloads.

### Phase 0. Prepare (about 30 minutes)

1. Confirm the data choice above.
2. Free at least 10 GB of disk (DEM tiles, a mosaic, working copies).
3. Work in a **scratch copy** of the repo, as was done for the first run, so that `config.json` and the
   working cache are untouched until Phase 4 passes.

### Phase 1. Data (about 1 to 1.5 hours, mostly downloads)

1. Download the 54 one-degree DEM tiles from the USGS catalogue (newest version of each).
2. Mosaic them with GDAL (`gdalbuildvrt`, then `gdal_translate` to a tiled, compressed GeoTIFF) and set the
   NoData value.
3. Clip NLCD 2019 for Arizona plus margin from MRLC's WCS into one single-band GeoTIFF (EPSG:4326, or EPSG:5070
   and let GeoServer reproject). Fill the Mexico no-data area as above.

### Phase 2. GeoServer (about 1 hour)

1. Run GeoServer in Docker (Docker with colima is already installed here), port 8080, with its data directory
   on a mounted folder.
2. Create a workspace (`az`) and two GeoTIFF coverage stores: `az:dem` (enable WCS 1.0.0) and `az:nlcd2019`.
3. Give `nlcd2019` an NLCD **colour-map style**: one entry per class, `quantity` = NLCD code, `label` =
   `"<name> (<code>)"`, colour = the NLCD legend colour. Base it on MRLC's published style
   (`GetLegendGraphic` on their layer returns the entries).
4. Check by hand: WCS `getCapabilities`, `describeCoverage`, `getCoverage` (GeoTIFF); WMS `GetMap` with
   `image/geotiff`; `GetLegendGraphic` JSON. Compare the landcover GeoTIFF with MRLC's (single band, palette).

**Decision point:** if LandSAR's WCS client cannot talk to this GeoServer, or the palette and legend do not
satisfy it, stop here. The disk cache remains the answer.

### Phase 3. Connect LandSAR (about 1 hour, in the scratch copy)

1. In the scratch copy's `config.json`, set the keys in the table above (`geodata mode` = `geoserver`,
   `geoserver url`, `elevation layer`, `landcover layer`, `landcover load format` = `geotiff`,
   `geoserver null value`).
2. Run it on a spare port, with `tools/start-landsar.sh` and both patches applied
   (`tools/patch_offline_cache.py` is not needed in this mode; `tools/patch_sample_builder.py` still is).

### Phase 4. Acceptance tests

1. Repeat searches that matter: Flagstaff, Grand Canyon (direction 235), Page, and one in the Phoenix or Tucson
   area, which the Coconino caches never covered.
2. Compare each search's area data with the same search served from the disk cache. They should be nearly
   identical, since both come from NLCD 2019 and 3DEP.
3. Time a search from submit to result, and note how many requests it makes.
4. Search close to the state edge and near the Mexico border.

**Decision point:** switch the main configuration to GeoServer, keep both modes, or stay on the disk cache.

## Risks and unknowns

1. LandSAR's old WorldWind WCS 1.0.0 client may not work with a current GeoServer. **(unverified)**
2. How the palette GeoTIFF and the legend JSON are combined by LandSAR. **(unverified)**
3. Per-search download time, and any GeoServer limits on output size or rendering time. **(unverified)**
4. A GeoServer is another service to start, secure and keep up to date.
5. The example model is a demonstration: it produces a single straight line. A GeoServer only changes where
   the data comes from, not what the model does.

## Rollback

Nothing in the real setup changes until Phase 4 passes. Switching back is the `geo cache directory` /
`geodata mode` edit in `config.json`. The container is removed with `docker rm -f` and its data directory
deleted.

## References

- `tools/build_geocache.py`: the disk cache builder (NLCD 2019 via MRLC WCS, 3DEP via the USGS ImageServer,
  ESA WorldCover fallback where NLCD has no data).
- `tools/patch_offline_cache.py`, `tools/patch_sample_builder.py`: the two fixes that apply to disk mode and
  to the example model.
- LandSAR SDK: `https://github.com/raytheonbbn/landsar-sdk` (also at `~/dev/landsar-sdk`).
