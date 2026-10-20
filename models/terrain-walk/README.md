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
| `goal pull` | no | detour tolerance toward a drawn goal: a sideways step costs (1 + pull) x a step toward it, a step away (1 + 2 pull) x; 0 = ignore the goal; 3+ is nearly a straight line | 1 if a goal is drawn, else 0 |
| `goal radius` | no | metres from a goal at which a person has arrived and stops | 50 |
| `downhill bias` | no | effort-metres added per metre of elevation gained; 2-5 sends people down drainages | 0 |
| `max slope` | no | steepest ground (rise over run of the terrain itself, not of the step) a person will set foot on; steeper ground is a wall from every direction | 0.75 (about 37 degrees) |
| `corridor` | no | 1 = every hourly map shows all ground covered during the walk (a route corridor) instead of positions by hour | 0 |
| `trail factor` | no | effort multiplier on trail/road cells (needs `trails.txt`); lower = people prefer and stay on trails more | 0.5 |
| `trail slope` | no | steepest rise-over-run crossed on a trail cell, so a trail up a cliff is passable while the cliff is not | 1.5 |

## Goal points (known destination)

The new-lost-person form offers a **goal points** step (green markers). With one or more goals drawn:

- each simulated person picks one goal at random (several goals are alternatives, not a sequence);
- before simulating, the model plans: a least-cost (Dijkstra) search from the goal over a 30 m grid of the bounding
  box, priced exactly as the walkers price steps (slope, landcover, trails, passability), gives the effort still needed
  from every cell. Each candidate step's cost is then multiplied by (1 + `goal pull` x (1 - progress)), where progress
  is +1 for the step that reduces the remaining effort by as much as it costs (the planned route), 0 for no change
  and -1 for a step that adds as much as it costs. A trail up a cliff therefore reads as progress even while it points
  away from the goal, and a river bench that dead-ends below the goal does not. Raise `goal pull` for a tighter bundle,
  lower it (or raise `randomness`) for a wider one;
- if `heading` is blank the first heading is the bearing to the goal, spread by `heading spread`;
- a person within `goal radius` of the goal stops and stays there;
- people who never arrive within `duration` are discarded from the maps and drawn paths, since the subject is known to
  have reached the goal. If nobody arrives, all are kept and a status message says so: give more hours, more pull, a
  larger radius or a gentler `max slope`.

Typical use: last-seen point = where the subject was last on a known route, goal = where a clue was found. `corridor` = 1 shows
the whole route on every hourly map (so the maps do not change over time); leave it 0 to watch the hourly maps move.
The drawn sample paths and the corridor map together show the likely routes between the two.

## Trails and roads (OpenStreetMap)

LandSAR Base's disk geocache loader knows nothing about trails, so the model reads its own layer: a
`trails.txt` grid in each geocache tile (same grid as `elevation.txt`; 0 = none, 1 = trail, 2 = road), written
by `tools/build_trails.py` from an `.osm.pbf` extract:

```bash
python3 tools/build_trails.py ~/Downloads/COCO.osm.pbf geocache-az-state 36.80 36.95 -111.70 -111.50
```

The model finds the tiles through the `"trail cache directory"` property on its `config.json` entry (set to
`geocache-az-state`). Tiles without `trails.txt` simply have no trails. On a trail or road cell a step costs
`trail factor` x the normal effort, the grade used for walking speed is capped at 0.3 (switchbacks are gentler
than the cells they cross), and the slope limit is `trail slope` instead of `max slope`. Nothing else changes, so
people on a trail tend to stay on it because leaving it costs more, and a trail is the only way up ground steeper
than `max slope`.

Passability is judged by the steepness of the ground where a step lands (gradient from DEM samples one cell apart),
so a cliff band cannot be climbed by stepping across it obliquely. The grade along the step still sets walking speed.

## Downhill bias (unknown destination, descending)

`downhill bias` charges extra effort for every metre climbed. With it at 0 the only slope effect is Tobler's speed
function, which makes climbing slower but not avoided. At 2-5, people move downslope and collect in drainages. Slopes
steeper than `max slope` remain impassable, so from a cliff-edge start people stay on the rim until they find a
break they can walk down; nobody goes over the edge.

Corridor maps are only the initial distribution. Updating for a search (search evaluation) always recomputes the
affected hours from positions by hour.

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

- No roads or trails (Base has no trail data), no subject categories (child, dementia, hunter ...), goals are
  alternatives rather than an ordered sequence, and no fatigue or injury (the published model has an exhaustion and recovery level; this one does not).
- It is an interpretation of a published description, not BBN's code, and its parameters are not calibrated.
- Landcover speed factors and costs in Base are placeholders (1.0, water 2.0). Edit
  `defaultParams/landcover-defaults.txt` for realistic values; the model uses both.
- Only the first 300 paths are sent to the web UI for drawing; all samples are used for the distributions.
- Elevation and landcover are 30 m grids, so features narrower than that can be stepped over.
- Not validated against real search-and-rescue data: treat it as a plausible illustration, not a prediction.
