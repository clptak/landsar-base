# Terrain-walk motion model

A motion model plugin for LandSAR Base that replaces the example model's single straight line with a cloud of
simulated people on foot. It exists because LandSAR Base ships no usable operational model: the example
model gives one path, and the built-in "core" models in `osppre-core` (LOST_HIKER, FLEEING, ...) are stubs.

## What it does

It follows the published description of LandSAR's "easiest short-term path" lost-hiker behaviour (Soule et al.,
GEOProcessing 2020, sections V.A.2-3): a person with no known destination keeps taking the easiest next step.

Each of N simulated people (default 5000) starts at the last-known point and repeats:

1. **Look at options.** Draw `options considered` (default 20) candidate next steps of length `step length`
   (default 100 m), with directions spread around the person's current direction (standard deviation `course change`,
   default 45 degrees).
2. **Price each option.** Effort = step length / (landcover speed factor x slope factor), times the landcover
   cost. The slope factor is Tobler's hiking function (1 on flat ground, less when climbing or on steep descent,
   a little more on gentle descent); slopes steeper than 0.75 (about 37 degrees) are impassable. Water (if "stay
   out of water" is on), exclusion zones and the box edge are impassable too.
3. **Choose.** The easiest option is the most likely, not the only one: an option's chance falls off exponentially
   with its extra effort over the best one, on a scale of `randomness` x step length (default 0.2; 0 = always the
   easiest; large = a plain random walk).
4. **Spend time.** Each step uses effort from a budget that grows with the walking time the movement schedule allows
   (day, night or continuous), at the person's own speed (flat-ground `speed` x a per-person factor of 0.5 to 2).

Positions are recorded hourly. Search evaluation, saved state and cumulative detection use the SDK's
`ExampleInternalModel`, which works on any set of sample paths. The probability map is blurred (`smoothing`) and
sized by the SDK from the bounding box (see tools/patch_resolution.py for finer maps).

The result: people funnel along gentle ground and open cover, and bundles of paths follow valleys and benches
instead of spreading evenly in all directions.

## Parameters (shown in the web form)

| Name | Required | Meaning | Default |
|---|---|---|---|
| `speed` | yes | typical walking speed on flat, open ground (the form converts from mph) | none |
| `heading` | no | compass bearing of initial travel, 0 = north, 90 = east (degrees) | no preference |
| `heading spread` | no | standard deviation of the first heading around `heading` (degrees) | 25 |
| `course change` | no | spread of candidate directions around the current one (degrees) | 45 |
| `options considered` | no | candidate steps compared each step (1 = no choice, 1 to 200) | 20 |
| `step length` | no | metres per step (10 to 1000) | 100 |
| `randomness` | no | 0 = always the easiest option; larger = more random | 0.2 |
| `duration` | no | hours simulated after the last-seen time (1 to 168) | 24 |
| `samples` | no | number of simulated people (10 to 20000) | 5000 |
| `distance` | no | cap on total distance walked, in kilometres (like the form's "number of segments") | none |
| `smoothing` | no | blur of the probability map in metres; 0 for none | 1.5 cells |

Optional parameters deliberately have no unit: a blank optional field that carries a unit is sent by the web form
as the whole number 0, which LandSAR's parameter conversion cannot cast to a Double, and the request is silently
dropped. Only `speed` (required) keeps its unit so the form can convert mph.

## Build and use

```bash
python3 tools/build_terrain_walk.py        # needs dependencies/ (./gradlew copyRuntimeLibs) and a JDK
```

This writes `plugins/landsar-terrain-walk-0.1.0.jar`. It is registered in `config.json` in two places: an entry
named `TerrainWalk` (class `landsar.terrainwalk.TerrainWalkMotionModelPlugin`) in the UtilityService plugin
list, and the name added to `"motion model plugin names"`. Restart LandSAR; the model then appears in the
motion-model list of the new-lost-person form.

Apply the patches with `python3 tools/apply_patches.py` first (finer probability maps, disk cache fix).

## Limits

- No roads or trails (Base has no trail data), no subject categories (child, dementia, hunter ...), no goals or
  destinations, and no fatigue or injury (the published model has an exhaustion and recovery level; this one does not).
- It is an interpretation of a published description, not BBN's code, and its parameters are not calibrated.
- Landcover speed factors and costs in Base are placeholders (1.0, water 2.0). Edit
  `defaultParams/landcover-defaults.txt` for realistic values; the model uses both.
- Only the first 300 paths are sent to the web UI for drawing; all samples are used for the distributions.
- Elevation and landcover are 30 m grids, so features narrower than that can be stepped over.
- Not validated against real search-and-rescue data: treat it as a plausible illustration, not a prediction.
