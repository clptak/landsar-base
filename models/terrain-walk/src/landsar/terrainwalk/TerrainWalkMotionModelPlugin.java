package landsar.terrainwalk;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bbn.landsar.MovementSchedule;
import com.bbn.landsar.geospatial.AbstractElevationData;
import com.bbn.landsar.geospatial.AbstractLandCoverData;
import com.bbn.landsar.geospatial.AreaData;
import com.bbn.landsar.geospatial.BoundingBox;
import com.bbn.landsar.geospatial.PolygonalExclusionZone;
import com.bbn.landsar.motionmodel.AreaDataType;
import com.bbn.landsar.motionmodel.DistOrMap;
import com.bbn.landsar.motionmodel.DistributionBySamplePoints;
import com.bbn.landsar.motionmodel.GeospatialInputDescriptions;
import com.bbn.landsar.motionmodel.LandMotionModelPlugin;
import com.bbn.landsar.motionmodel.MotionModelAttributeDescription;
import com.bbn.landsar.motionmodel.MotionModelConstants;
import com.bbn.landsar.motionmodel.MotionModelInput;
import com.bbn.landsar.motionmodel.MotionModelManager;
import com.bbn.landsar.motionmodel.MotionModelResult;
import com.bbn.landsar.motionmodel.ProbabilityDistribution;
import com.bbn.landsar.motionmodel.Unit;
import com.bbn.landsar.motionmodel.UserEnteredGeospatialData;
import com.bbn.landsar.motionmodel.example.ExampleInternalModel;
import com.bbn.landsar.motionmodel.path.Path;
import com.bbn.landsar.motionmodel.path.Sample;
import com.bbn.landsar.search.LandsarSearch;
import com.bbn.landsar.searchtheory.ContainmentMap;
import com.bbn.landsar.search.SearchUtilities;
import com.bbn.landsar.utils.GroundUtilities;
import com.bbn.landsar.utils.StatusUpdateMessage;
import com.bbn.roger.annotation.Plugin;
import com.bbn.roger.config.AttributeDescription;
import com.bbn.roger.plugin.PluginContext;
import com.bbn.roger.plugin.exception.InsufficientConfigurationException;
import com.bbn.roger.plugin.exception.RogerInstantiationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metsci.glimpse.util.geo.LatLonGeo;

/**
 * A terrain-aware random-walk motion model for a lost person on foot.
 *
 * Each of many simulated people starts at the last-known point and walks on a persistent but wandering
 * heading. Speed on every 5-minute step is the user's flat-ground speed times a per-person factor, times the
 * landcover speed factor at the person's position, times a slope factor from the elevation grid
 * (Tobler's hiking function). Slopes steeper than MAX_SLOPE are impassable. People rest according to the
 * movement schedule (day / night / continuous), do not enter water if "stay out of water" is on, do not
 * enter exclusion zones, and stay inside the bounding box. Positions are recorded hourly.
 *
 * Search evaluation, persistence and cumulative detection are provided by the SDK's ExampleInternalModel,
 * which is generic: it stores the sample paths and re-weights them after failed searches.
 */
@Plugin(dependencies = {"motion-model-sdk-0.0.6-SNAPSHOT.jar"})
public class TerrainWalkMotionModelPlugin implements LandMotionModelPlugin {
	private static final Logger LOGGER = LoggerFactory.getLogger(TerrainWalkMotionModelPlugin.class);

	static final long HOUR_MS = 3_600_000L;
	/** Walk simulation step. Must divide an hour. */
	static final long STEP_MS = 5L * 60_000L;
	/** Slopes (rise over run) steeper than this are treated as cliffs: about 37 degrees. */
	static final double MAX_SLOPE = 0.75;

	public static final String SPEED = "speed";
	public static final String HEADING = "heading";
	public static final String HEADING_SPREAD = "heading spread";
	public static final String COURSE_CHANGE = "course change";
	public static final String OPTIONS = "options considered";
	public static final String STEP_LENGTH = "step length";
	public static final String RANDOMNESS = "randomness";
	public static final String DURATION = "duration";
	public static final String SAMPLES = "samples";
	public static final String DISTANCE = "distance";
	public static final String SMOOTHING = "smoothing";
	public static final String GOAL_PULL = "goal pull";
	public static final String GOAL_RADIUS = "goal radius";
	public static final String DOWNHILL_BIAS = "downhill bias";
	public static final String MAX_SLOPE_PARAM = "max slope";
	public static final String CORRIDOR = "corridor";
	public static final String TRAIL_FACTOR = "trail factor";
	public static final String TRAIL_SLOPE = "trail slope";
	/** config.json property on this plugin's entry: directory of geocache tiles that carry trails.txt. */
	public static final String TRAIL_CACHE_DIRECTORY = "trail cache directory";

	/** Effort multiplier on trail and road cells when the field is blank. */
	static final double DEFAULT_TRAIL_FACTOR = 0.5;
	/** Steepest cell-to-cell slope crossed on a trail cell when the field is blank (a trail up a cliff). */
	static final double DEFAULT_TRAIL_SLOPE = 1.5;
	/** On a trail the grade used for walking speed is capped here: switchbacks are gentler than the cells they cross. */
	static final double TRAIL_MAX_GRADE = 0.3;
	/** Trace points kept per drawn path. */
	static final int PATH_POINTS = 400;

	/** Detour tolerance toward a drawn goal when the field is blank: a sideways step costs (1 + pull) x, a step away (1 + 2 pull) x. */
	static final double DEFAULT_GOAL_PULL = 1.0;
	/** Spacing, in metres, of the elevation samples used to measure the ground gradient at a candidate step (the DEM cell). */
	static final double GRADIENT_SAMPLE_M = 30.0;
	/** Target cell size of the effort-to-goal field, and the most cells it may have (the cell grows beyond that). */
	static final double FIELD_CELL_M = 30.0;
	static final int FIELD_MAX_CELLS = 1_500_000;
	/** A person within this distance of the goal has arrived. */
	static final double DEFAULT_GOAL_RADIUS_M = 50.0;
	/** Most trace points kept per person for the corridor map. */
	static final int CORRIDOR_POINTS_PER_PERSON = 200;

	static final double DEFAULT_HEADING_SPREAD_DEG = 25.0;
	static final double DEFAULT_COURSE_CHANGE_DEG = 45.0;
	static final double DEFAULT_OPTIONS = 20.0;
	static final double MAX_OPTIONS = 200.0;
	static final double DEFAULT_STEP_M = 100.0;
	/** 0 = always take the easiest option; the weight of an option falls by e for every (randomness x step) of extra effort. */
	static final double DEFAULT_RANDOMNESS = 0.2;
	static final double DEFAULT_DURATION_HOURS = 24.0;
	static final double MAX_DURATION_HOURS = 168.0;
	static final double DEFAULT_SAMPLES = 5000.0;
	/** Blur radius (standard deviation) as a multiple of the cell size, when "smoothing" is blank. */
	static final double DEFAULT_SMOOTHING_CELLS = 1.5;
	static final double MAX_SAMPLES = 20000.0;
	/** Number of paths sent to the web UI for drawing; all samples are still used for the distributions. */
	static final int PATHS_TO_DISPLAY = 300;

	private static final Set<MotionModelAttributeDescription> PER_LPI_ATTRS = new HashSet<>();
	private static final Set<GeospatialInputDescriptions> PER_LPI_GEOSPATIAL_ATTRS = new HashSet<>();
	private static final Set<AreaDataType> REQUIRED_AREA_DATA = new HashSet<>();

	static {
		PER_LPI_ATTRS.add(new MotionModelAttributeDescription(SPEED,
				"typical walking speed on flat, open ground", true, Double.class, Unit.KILOMETERS_PER_HOUR));
		PER_LPI_ATTRS.add(new MotionModelAttributeDescription(HEADING,
				"direction the person was heading, as a compass bearing (0 = north, 90 = east, 180 = south, 270 = west); leave blank if unknown",
				false, Double.class, Unit.DEGREES));
		PER_LPI_ATTRS.add(new MotionModelAttributeDescription(HEADING_SPREAD,
				"how widely people's first direction varies around the heading (standard deviation); default 25. Ignored without a heading",
				false, Double.class, Unit.DEGREES));
		PER_LPI_ATTRS.add(new MotionModelAttributeDescription(COURSE_CHANGE,
				"how far to either side of their current direction a person looks for the next step (standard deviation); default 45",
				false, Double.class, Unit.DEGREES));
		// Optional parameters must not carry a unit (see DISTANCE below), so these two are unit-free.
		PER_LPI_ATTRS.add(new MotionModelAttributeDescription(OPTIONS,
				"how many possible next steps a person compares, choosing the easiest (steepness and ground cover). 1 = no choice, a plain random walk; default 20, maximum 200",
				false, Double.class, Unit.NONE));
		PER_LPI_ATTRS.add(new MotionModelAttributeDescription(RANDOMNESS,
				"how often a person takes a step that is not the easiest. 0 = always the easiest; default 0.2; large values approach a random walk",
				false, Double.class, Unit.NONE));
		PER_LPI_ATTRS.add(new MotionModelAttributeDescription(STEP_LENGTH,
				"length in metres of each step a person chooses; default 100, 10 to 1000",
				false, Double.class, Unit.NONE));
		PER_LPI_ATTRS.add(new MotionModelAttributeDescription(DURATION,
				"hours to simulate after the last-seen time; default 24, maximum 168", false, Double.class, Unit.NONE));
		PER_LPI_ATTRS.add(new MotionModelAttributeDescription(SAMPLES,
				"number of simulated people; default 5000, maximum 20000", false, Double.class, Unit.NONE));
		// Optional parameters must not carry a unit: left blank, the web form sends a unit field as the whole number 0
		// (an Integer), which LandSAR's parameter conversion cannot cast to Double, and the whole request is dropped.
		PER_LPI_ATTRS.add(new MotionModelAttributeDescription(DISTANCE,
				"optional limit, in kilometres, on how far a person walks in total", false, Double.class, Unit.NONE));
		PER_LPI_ATTRS.add(new MotionModelAttributeDescription(SMOOTHING,
				"blur, in metres, applied to the probability map so a few thousand simulated people do not look speckled; default 1.5 x the map's cell size, 0 for none",
				// no unit on purpose: a unit makes the web form send whole numbers as Integers, which LandSAR's parameter
				// conversion cannot cast to Double ("Integer cannot be cast to Double") and the whole request is dropped
				false, Double.class, Unit.NONE));

		PER_LPI_ATTRS.add(new MotionModelAttributeDescription(GOAL_PULL,
				"how strongly a person heads for a drawn goal point: a step sideways to the goal costs (1 + pull) times a step toward it, a step away (1 + 2 x pull) times. 0 = ignore the goal; default 1 when a goal is drawn; 3 or more approaches a straight line",
				false, Double.class, Unit.NONE));
		PER_LPI_ATTRS.add(new MotionModelAttributeDescription(GOAL_RADIUS,
				"distance in metres from a goal point at which a person has arrived and stops; default 50",
				false, Double.class, Unit.NONE));
		PER_LPI_ATTRS.add(new MotionModelAttributeDescription(DOWNHILL_BIAS,
				"how much a person avoids climbing: effort-metres added per metre of elevation gained. 0 = no preference (default); 2 to 5 sends people down drainages",
				false, Double.class, Unit.NONE));
		PER_LPI_ATTRS.add(new MotionModelAttributeDescription(MAX_SLOPE_PARAM,
				"steepest ground a person will set foot on, as rise over run of the terrain itself (not of the step taken); default 0.75 (about 37 degrees). Steeper ground is a wall from every direction",
				false, Double.class, Unit.NONE));
		PER_LPI_ATTRS.add(new MotionModelAttributeDescription(CORRIDOR,
				"1 = every hourly map shows the ground covered over the whole walk (a route corridor) instead of positions at that hour; 0 = positions by hour (default). Search updates always use positions by hour",
				false, Double.class, Unit.NONE));

		PER_LPI_ATTRS.add(new MotionModelAttributeDescription(TRAIL_FACTOR,
				"effort multiplier on trail and road cells (needs trails.txt in the geocache); default 0.5. Lower = people prefer and stay on trails more",
				false, Double.class, Unit.NONE));
		PER_LPI_ATTRS.add(new MotionModelAttributeDescription(TRAIL_SLOPE,
				"steepest rise-over-run a person will cross on a trail cell; default 1.5, so a switchbacked trail up a cliff is passable while the cliff is not",
				false, Double.class, Unit.NONE));

		PER_LPI_GEOSPATIAL_ATTRS.add(new GeospatialInputDescriptions(
				UserEnteredGeospatialData.GeospatialDataType.EXCLUSION_ZONE, false));
		PER_LPI_GEOSPATIAL_ATTRS.add(new GeospatialInputDescriptions(
				UserEnteredGeospatialData.GeospatialDataType.GOAL_POINTS, false));

		REQUIRED_AREA_DATA.add(new AreaDataType(AreaDataType.ELEVATION, true));
		REQUIRED_AREA_DATA.add(new AreaDataType(AreaDataType.LANDCOVER, true));
	}

	private MotionModelManager motionModelManager;
	private String name = "";
	private final Map<UUID, ExampleInternalModel> lpiData = new ConcurrentHashMap<>();
	/** Smoothing (metres) chosen for each LPI, or absent for the default. Not saved: after a restart the default applies. */
	private final Map<UUID, Double> smoothingSettings = new ConcurrentHashMap<>();
	private static final String INTERNAL_MODEL_FILENAME = "internal_model.json";
	private boolean started;
	/** Trails grid from "trail cache directory", or null when not configured or the directory is missing. */
	private TrailGrid trails;

	// ---- plugin plumbing ------------------------------------------------------------------------------

	@Override
	public void configure(Map<String, Object> configurationOptions, PluginContext context)
			throws InsufficientConfigurationException {
		Object dir = configurationOptions == null ? null : configurationOptions.get(TRAIL_CACHE_DIRECTORY);
		if (dir != null && !dir.toString().trim().isEmpty()) {
			File f = new File(dir.toString().trim());
			if (f.isDirectory()) {
				trails = new TrailGrid(f);
				LOGGER.info("TerrainWalk: trails from {} ({} tiles with trails.txt)", f, trails.tileCount());
			} else {
				LOGGER.warn("TerrainWalk: '{}' = {} is not a directory; running without trails", TRAIL_CACHE_DIRECTORY, f);
			}
		} else {
			LOGGER.info("TerrainWalk: no '{}' configured; running without trails", TRAIL_CACHE_DIRECTORY);
		}
	}

	@Override
	public Set<AttributeDescription> getConfigurationAttributes() {
		Set<AttributeDescription> attrs = new HashSet<>();
		attrs.add(new AttributeDescription(TRAIL_CACHE_DIRECTORY,
				"geocache directory whose tiles carry trails.txt (see tools/build_trails.py); optional", false, String.class));
		return attrs;
	}

	@Override
	public void initialize() throws RogerInstantiationException {
		// nothing to initialize
	}

	@Override
	public void setName(String name) {
		this.name = name;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public Set<MotionModelAttributeDescription> getMotionModelParameters() {
		return PER_LPI_ATTRS;
	}

	@Override
	public Set<GeospatialInputDescriptions> getMotionModelGeospatialDescriptions() {
		return PER_LPI_GEOSPATIAL_ATTRS;
	}

	@Override
	public Set<AreaDataType> getRequiredAreaData() {
		return new HashSet<>(REQUIRED_AREA_DATA);
	}

	@Override
	public void setMotionModelManager(MotionModelManager motionModelManager) {
		this.motionModelManager = motionModelManager;
	}

	@Override
	public void start() {
		this.started = true;
	}

	@Override
	public boolean isStarted() {
		return started;
	}

	// ---- parameters -----------------------------------------------------------------------------------

	/** Reads a numeric parameter that may be missing, blank, a Number or a numeric string. */
	static Double number(Map<String, Object> params, String key) {
		Object v = params.get(key);
		if (v instanceof Number) {
			return ((Number) v).doubleValue();
		}
		if (v instanceof String && !((String) v).trim().isEmpty()) {
			try {
				return Double.parseDouble(((String) v).trim());
			} catch (NumberFormatException e) {
				return Double.NaN;
			}
		}
		return null;
	}

	@Override
	public boolean validateMotionModelParameters(Map<String, Object> params, UserEnteredGeospatialData geospatialInputs,
			StatusUpdateMessage status) {
		LOGGER.debug("Motion model parameters: {}", params);
		boolean anyErrors = false;

		Double speed = number(params, SPEED);
		if (speed == null || Double.isNaN(speed) || speed <= 0.0 || speed > 15.0) {
			status.addError("invalid parameter", String.format("'%s' must be a number greater than 0 and at most 15 km/h", SPEED));
			anyErrors = true;
		}
		anyErrors |= checkRange(status, params, HEADING, -360.0, 720.0);
		anyErrors |= checkRange(status, params, HEADING_SPREAD, 0.0, 180.0);
		anyErrors |= checkRange(status, params, COURSE_CHANGE, 0.0, 180.0);
		anyErrors |= checkRange(status, params, OPTIONS, 1.0, MAX_OPTIONS);
		anyErrors |= checkRange(status, params, STEP_LENGTH, 10.0, 1000.0);
		anyErrors |= checkRange(status, params, RANDOMNESS, 0.0, 100.0);
		anyErrors |= checkRange(status, params, DURATION, 1.0, MAX_DURATION_HOURS);
		anyErrors |= checkRange(status, params, SAMPLES, 10.0, MAX_SAMPLES);
		anyErrors |= checkRange(status, params, DISTANCE, 0.01, 1000.0);
		anyErrors |= checkRange(status, params, SMOOTHING, 0.0, 5000.0);
		anyErrors |= checkRange(status, params, GOAL_PULL, 0.0, 100.0);
		anyErrors |= checkRange(status, params, GOAL_RADIUS, 1.0, 5000.0);
		anyErrors |= checkRange(status, params, DOWNHILL_BIAS, 0.0, 100.0);
		anyErrors |= checkRange(status, params, MAX_SLOPE_PARAM, 0.05, 10.0);
		anyErrors |= checkRange(status, params, CORRIDOR, 0.0, 1.0);
		anyErrors |= checkRange(status, params, TRAIL_FACTOR, 0.01, 10.0);
		anyErrors |= checkRange(status, params, TRAIL_SLOPE, 0.05, 10.0);

		if (anyErrors) {
			motionModelManager.sendStatusUpdateMessage(status);
		}
		return !anyErrors;
	}

	private static boolean checkRange(StatusUpdateMessage status, Map<String, Object> params, String key, double min, double max) {
		Double v = number(params, key);
		if (v == null) {
			return false; // optional and blank
		}
		if (Double.isNaN(v) || v < min || v > max) {
			status.addError("invalid parameter", String.format("'%s' must be a number between %s and %s", key, min, max));
			return true;
		}
		return false;
	}

	// ---- the model ------------------------------------------------------------------------------------

	@Override
	public MotionModelResult generateInitialDistribution(MotionModelInput input) {
		UUID lpiId = input.getLpiId();
		UserEnteredGeospatialData geospatialInputs = input.getGeospatialInputs();
		AreaData areaData = input.getAreaData();
		Map<String, Object> params = input.getMotionModelParameters();
		MovementSchedule schedule = input.getMovementSchedule();
		long startTime = input.getStartTime();
		List<LatLonGeo> starts = input.getStartTimeDistribution();
		StatusUpdateMessage status = input.getStatus();

		Boolean stayOutOfWaterParam = (Boolean) params.get(MotionModelConstants.STAY_OUT_OF_WATER);
		final boolean stayOutOfWater = stayOutOfWaterParam != null && stayOutOfWaterParam;

		final double speedKph = number(params, SPEED);
		final Double heading = number(params, HEADING);
		final double headingSpread = orDefault(number(params, HEADING_SPREAD), DEFAULT_HEADING_SPREAD_DEG);
		final double courseChange = orDefault(number(params, COURSE_CHANGE), DEFAULT_COURSE_CHANGE_DEG);
		final int options = (int) Math.round(orDefault(number(params, OPTIONS), DEFAULT_OPTIONS));
		final double stepM = orDefault(number(params, STEP_LENGTH), DEFAULT_STEP_M);
		final double randomness = orDefault(number(params, RANDOMNESS), DEFAULT_RANDOMNESS);
		final int hours = (int) Math.round(orDefault(number(params, DURATION), DEFAULT_DURATION_HOURS));
		final int samples = (int) Math.round(orDefault(number(params, SAMPLES), DEFAULT_SAMPLES));
		final Double distanceKm = number(params, DISTANCE);
		final double maxDistanceM = distanceKm == null ? Double.MAX_VALUE : distanceKm * 1000.0;
		final List<LatLonGeo> goals = geospatialInputs != null && geospatialInputs.getGoalPoints() != null
				&& !geospatialInputs.getGoalPoints().isEmpty() ? new ArrayList<>(geospatialInputs.getGoalPoints()) : null;
		final double goalPull = orDefault(number(params, GOAL_PULL), goals == null ? 0.0 : DEFAULT_GOAL_PULL);
		final double goalRadiusM = orDefault(number(params, GOAL_RADIUS), DEFAULT_GOAL_RADIUS_M);
		final double downhillBias = orDefault(number(params, DOWNHILL_BIAS), 0.0);
		final double maxSlope = orDefault(number(params, MAX_SLOPE_PARAM), MAX_SLOPE);
		final boolean corridor = orDefault(number(params, CORRIDOR), 0.0) >= 0.5;
		final double trailFactor = orDefault(number(params, TRAIL_FACTOR), DEFAULT_TRAIL_FACTOR);
		final double trailSlope = orDefault(number(params, TRAIL_SLOPE), DEFAULT_TRAIL_SLOPE);
		final TrailGrid trailGrid = trails;
		Double smoothing = number(params, SMOOTHING);
		if (smoothing != null && !Double.isNaN(smoothing)) {
			smoothingSettings.put(lpiId, smoothing);
		} else {
			smoothingSettings.remove(lpiId);
		}

		Number seedParam = params.get(MotionModelConstants.RANDOM_SEED_PARAMETER) instanceof Number
				? (Number) params.get(MotionModelConstants.RANDOM_SEED_PARAMETER) : null;
		final long baseSeed = (seedParam == null || seedParam.longValue() == 0L) ? System.nanoTime() : seedParam.longValue();

		final List<CostField> fields = new ArrayList<>();
		if (goals != null && goalPull > 0.0) {
			motionModelManager.sendInProgressUpdate(this.name + ": planning routes to " + goals.size() + " goal(s).", status);
			for (LatLonGeo g : goals) {
				CostField f = CostField.build(areaData, g, geospatialInputs, stayOutOfWater, maxSlope, trails, trailFactor, trailSlope);
				fields.add(f);
				LOGGER.info("TerrainWalk: effort-to-goal field {}x{} cells of {} m; start reachable: {}", f.nLon, f.nLat,
						Math.round(f.cellM), starts.isEmpty() ? "?" : Double.isFinite(f.costAt(starts.get(0))));
			}
		}
		motionModelManager.sendInProgressUpdate(this.name + ": simulating " + samples + " people for " + hours + " hours.", status);

		List<Walk> walks = IntStream.range(0, samples).parallel().mapToObj(i -> {
			Random rnd = new Random(baseSeed + i * 0x9E3779B97F4A7C15L);
			LatLonGeo start = starts.get(i % starts.size());
			int gi = goals == null ? -1 : rnd.nextInt(goals.size());
			LatLonGeo goal = gi < 0 ? null : goals.get(gi);
			CostField field = gi < 0 || fields.isEmpty() ? null : fields.get(gi);
			List<LatLonGeo> trace = (corridor || i < PATHS_TO_DISPLAY) ? new ArrayList<>() : null;
			return walk(rnd, start, startTime, hours, speedKph, heading, headingSpread, courseChange,
					options, stepM, randomness, maxDistanceM, schedule, areaData, geospatialInputs, stayOutOfWater, trace,
					goal, field, goalPull, goalRadiusM, downhillBias, maxSlope, trailGrid, trailFactor, trailSlope);
		}).collect(Collectors.toList());

		if (goals != null) {
			long arrived = walks.stream().filter(w -> w.arrived).count();
			if (arrived == 0) {
				String msg = this.name + ": none of the " + samples + " simulated people reached the goal within " + hours
						+ " hours; keeping all of them. Try more hours, a higher goal pull, a larger goal radius or a gentler max slope.";
				LOGGER.warn(msg);
				motionModelManager.sendInProgressUpdate(msg, status);
			} else {
				motionModelManager.sendInProgressUpdate(this.name + ": " + arrived + " of " + samples
						+ " simulated people reached the goal; the rest are discarded.", status);
				walks = walks.stream().filter(w -> w.arrived).collect(Collectors.toList());
			}
		}
		List<Sample> samplePaths = walks.stream()
				.map(w -> new Sample(startTime, HOUR_MS, w.hourly, new ArrayList<Double>())).collect(Collectors.toList());

		ExampleInternalModel model = new ExampleInternalModel();
		model.setSpeed(speedKph);
		model.setDirection(heading == null ? 0.0 : heading);
		model.setDistanceKm(distanceKm == null ? 0.0 : distanceKm);
		model.setSamples(samplePaths);
		lpiData.put(lpiId, model);

		Map<Long, ProbabilityDistribution> distributions = new HashMap<>();
		if (corridor) {
			// one map of all the ground covered, repeated for every hour; search updates fall back to positions by hour
			List<LatLonGeo> covered = new ArrayList<>();
			for (Walk w : walks) {
				covered.addAll(w.thinnedTrace(CORRIDOR_POINTS_PER_PERSON));
			}
			double[][] grid = makeGrid(areaData.getBoundingBox(), covered, null, smoothingSettings.get(lpiId));
			for (long t = startTime; t <= model.endTime(); t += HOUR_MS) {
				double[][] copy = new double[grid.length][];
				for (int r = 0; r < grid.length; r++) {
					copy[r] = grid[r].clone();
				}
				distributions.put(t, new ProbabilityDistribution(lpiId, t, copy, areaData.getBoundingBox()));
			}
		} else {
			for (long t = startTime; t <= model.endTime(); t += HOUR_MS) {
				distributions.put(t, distribution(lpiId, t, areaData.getBoundingBox(), model.getLocationsAtTime(t), null));
			}
		}

		motionModelManager.sendInProgressUpdate(this.name + ": finished computing.", status);
		MotionModelResult result = new MotionModelResult();
		result.setLpiId(lpiId);
		result.setInitialDistribution(distributions);
		result.copyInitialDistToDistWithSearches();
		List<Path> paths = walks.stream().limit(PATHS_TO_DISPLAY)
				.map(w -> new Path(new ArrayList<>(w.thinnedTrace(PATH_POINTS)))).collect(Collectors.toList());
		result.setPaths(paths);
		result.setGeneratedTimestamp(System.currentTimeMillis());
		result.setGeneratingModelName(this.getName());
		return result;
	}

	private static double orDefault(Double v, double dflt) {
		return (v == null || Double.isNaN(v)) ? dflt : v;
	}

	/**
	 * Simulates one person; returns their position at the start time and at every hour after, for {@code hours} hours.
	 *
	 * The person moves in steps of {@code stepM} metres. For each step they look at {@code options} candidate next
	 * points, spread around their current direction (standard deviation {@code courseChangeDeg}), and take the
	 * easiest: the one that costs the least effort, where effort is the step length divided by the speed of
	 * advance there (landcover speed factor x a Tobler slope factor), multiplied by the landcover cost. People are
	 * not perfectly rational: with {@code randomness} above 0 an option's chance falls off exponentially with its extra
	 * effort over the best one (scale = randomness x step length); at 0 the easiest option is always taken. {@code trace}, if
	 * not null, receives the position after every step (for tests and drawing). This is
	 * the "easiest short-term path" behaviour described for LandSAR's lost-hiker model: it makes people follow
	 * gentle ground and open or easy cover and avoid steep slopes. With {@code options} = 1 there is no choice and
	 * the walk is a plain persistent random walk. Effort is paid from a budget that grows with the walking time the
	 * movement schedule allows. Blocked candidates (water, exclusion zones, the box edge, cliffs) are skipped.
	 *
	 * Package-private and static so that it can be tested without a running LandSAR.
	 */
	/** Tobler's hiking function as a speed factor (1 on the flat), for a grade in rise over run. */
	static double tobler(double grade) {
		return Math.max(0.1, Math.min(1.25, Math.exp(-3.5 * (Math.abs(grade + 0.05) - 0.05))));
	}

	/**
	 * Effort still needed to reach a goal from every cell of a grid over the bounding box: Dijkstra from the goal,
	 * with edges priced exactly as the walkers price their steps (slope, landcover, trails, passability). Walkers
	 * use it to measure progress, so a trail up a cliff reads as progress even though it points away from the goal.
	 */
	static final class CostField {
		final double south, west, dLat, dLon, cellM;
		final int nLat, nLon;
		final float[] cost; // row-major, row 0 = south; +Inf = unreachable

		private CostField(double south, double west, double dLat, double dLon, double cellM, int nLat, int nLon, float[] cost) {
			this.south = south; this.west = west; this.dLat = dLat; this.dLon = dLon; this.cellM = cellM;
			this.nLat = nLat; this.nLon = nLon; this.cost = cost;
		}

		double costAt(LatLonGeo p) {
			int i = (int) ((p.getLatDeg() - south) / dLat), j = (int) ((p.getLonDeg() - west) / dLon);
			if (i < 0 || j < 0 || i >= nLat || j >= nLon) {
				return Double.POSITIVE_INFINITY;
			}
			return cost[i * nLon + j];
		}

		static CostField build(AreaData area, LatLonGeo goal, UserEnteredGeospatialData geo, boolean stayOutOfWater,
				double maxSlope, TrailGrid trails, double trailFactor, double trailSlope) {
			BoundingBox box = area.getBoundingBox();
			AbstractElevationData elevation = area.getElevationData();
			AbstractLandCoverData landcover = area.getLandcoverData();
			double south = box.getSouthLatDeg(), north = box.getNorthLatDeg(), west = box.getWestLonDeg(), east = box.getEastLonDeg();
			double nsM = (north - south) * 111_111.11;
			double ewM = (east - west) * 111_111.11 * Math.cos(Math.toRadians((north + south) / 2.0));
			double cellM = FIELD_CELL_M;
			if ((nsM / cellM) * (ewM / cellM) > FIELD_MAX_CELLS) {
				cellM = Math.sqrt(nsM * ewM / FIELD_MAX_CELLS);
			}
			int nLat = Math.max(2, (int) Math.ceil(nsM / cellM)), nLon = Math.max(2, (int) Math.ceil(ewM / cellM));
			double dLat = (north - south) / nLat, dLon = (east - west) / nLon;
			int n = nLat * nLon;

			float[] elev = new float[n], soa = new float[n];
			byte[] lc = new byte[n];
			boolean[] passable = new boolean[n], onTrail = new boolean[n];
			for (int i = 0; i < nLat; i++) {
				double lat = south + (i + 0.5) * dLat;
				for (int j = 0; j < nLon; j++) {
					double lon = west + (j + 0.5) * dLon;
					int k = i * nLon + j;
					LatLonGeo p = new LatLonGeo(lat, lon);
					elev[k] = (float) elevation.getElevationMetersForLatLon(p);
					double sf = landcover.getSoaFactor(lat, lon);
					soa[k] = (float) ((Double.isNaN(sf) || sf <= 0.0) ? 1.0 : sf);
					lc[k] = (byte) Math.max(1, Math.min(127, landcover.getCost(lat, lon)));
					onTrail[k] = trails != null && trails.at(lat, lon) > 0;
					double g = gradient(elevation, p);
					passable[k] = allowed(p, box, geo, landcover, stayOutOfWater) && g <= (onTrail[k] ? trailSlope : maxSlope);
				}
			}

			float[] cost = new float[n];
			java.util.Arrays.fill(cost, Float.POSITIVE_INFINITY);
			int gi = (int) ((goal.getLatDeg() - south) / dLat), gj = (int) ((goal.getLonDeg() - west) / dLon);
			gi = Math.max(0, Math.min(nLat - 1, gi));
			gj = Math.max(0, Math.min(nLon - 1, gj));
			int start = gi * nLon + gj;
			passable[start] = true; // the goal itself is always reachable
			PriorityQueue<long[]> pq = new PriorityQueue<>((a, b) -> Double.compare(Double.longBitsToDouble(a[0]), Double.longBitsToDouble(b[0])));
			cost[start] = 0f;
			pq.add(new long[] {Double.doubleToLongBits(0.0), start});
			double mLat = dLat * 111_111.11, mLon = dLon * 111_111.11 * Math.cos(Math.toRadians((north + south) / 2.0));
			int[] di = {-1, -1, -1, 0, 0, 1, 1, 1}, dj = {-1, 0, 1, -1, 1, -1, 0, 1};
			while (!pq.isEmpty()) {
				long[] top = pq.poll();
				double c = Double.longBitsToDouble(top[0]);
				int b = (int) top[1];
				if (c > cost[b]) {
					continue;
				}
				int bi = b / nLon, bj = b % nLon;
				for (int d = 0; d < 8; d++) {
					int ai = bi + di[d], aj = bj + dj[d];
					if (ai < 0 || aj < 0 || ai >= nLat || aj >= nLon) {
						continue;
					}
					int a = ai * nLon + aj;
					if (!passable[b] && b != start) {
						break;
					}
					if (!passable[a]) {
						continue;
					}
					// price the step a -> b the way a walker would
					double dist = Math.sqrt((di[d] * mLat) * (di[d] * mLat) + (dj[d] * mLon) * (dj[d] * mLon));
					double grade = (elev[b] - elev[a]) / dist;
					if (onTrail[b]) {
						grade = Math.max(-TRAIL_MAX_GRADE, Math.min(TRAIL_MAX_GRADE, grade));
					}
					double effort = dist / (soa[b] * tobler(grade));
					if (onTrail[b]) {
						effort *= trailFactor;
					}
					double step = effort * lc[b];
					double nc = c + step;
					if ((float) nc < cost[a]) {
						cost[a] = (float) nc;
						pq.add(new long[] {Double.doubleToLongBits(cost[a]), a}); // queue the stored float, so the pop check matches
					}
				}
			}
			return new CostField(south, west, dLat, dLon, cellM, nLat, nLon, cost);
		}
	}

	/**
	 * Trail / road cells from trails.txt files in geocache tiles (written by tools/build_trails.py): 0 = none,
	 * 1 = trail, 2 = road. Tiles are found by their LandcoverLatLon.txt bounds and loaded on first use.
	 * Safe to call from parallel streams.
	 */
	static final class TrailGrid {
		private static final class Tile {
			final double s, n, w, e;
			final File file;
			volatile byte[] cells; // row-major, row 0 = north
			volatile int ncols, nrows;
			volatile boolean failed;

			Tile(double s, double n, double w, double e, File file) {
				this.s = s; this.n = n; this.w = w; this.e = e; this.file = file;
			}

			boolean contains(double lat, double lon) {
				return lat >= s && lat < n && lon >= w && lon < e;
			}

			synchronized void load() {
				if (cells != null || failed) {
					return;
				}
				try (BufferedReader r = new BufferedReader(new FileReader(file), 1 << 16)) {
					int nc = 0, nr = 0;
					for (int i = 0; i < 6; i++) {
						String[] kv = r.readLine().trim().split("\\s+");
						if (kv[0].equals("ncols")) nc = Integer.parseInt(kv[1]);
						if (kv[0].equals("nrows")) nr = Integer.parseInt(kv[1]);
					}
					byte[] data = new byte[nc * nr];
					int row = 0;
					String line;
					while (row < nr && (line = r.readLine()) != null) {
						int col = 0, i = 0, len = line.length();
						while (col < nc && i < len) {
							while (i < len && line.charAt(i) == ' ') i++;
							int start = i;
							while (i < len && line.charAt(i) != ' ') i++;
							if (i > start) {
								int v = Integer.parseInt(line.substring(start, i));
								data[row * nc + col] = (byte) (v < 0 ? 0 : Math.min(v, 127));
								col++;
							}
						}
						row++;
					}
					ncols = nc;
					nrows = nr;
					cells = data;
					LOGGER.info("TerrainWalk: loaded trails {} ({}x{})", file, nc, nr);
				} catch (Exception ex) {
					failed = true;
					LOGGER.warn("TerrainWalk: could not read {}: {}", file, ex.toString());
				}
			}

			int at(double lat, double lon) {
				if (cells == null) {
					load();
					if (cells == null) {
						return 0;
					}
				}
				int col = (int) ((lon - w) / (e - w) * ncols);
				int row = (int) ((n - lat) / (n - s) * nrows);
				if (col < 0 || row < 0 || col >= ncols || row >= nrows) {
					return 0;
				}
				return cells[row * ncols + col];
			}
		}

		private final List<Tile> tiles = new ArrayList<>();

		TrailGrid(File dir) {
			File[] subs = dir.listFiles(File::isDirectory);
			if (subs == null) {
				return;
			}
			for (File sub : subs) {
				File bounds = new File(sub, "LandcoverLatLon.txt");
				File trails = new File(sub, "trails.txt");
				if (!bounds.isFile() || !trails.isFile()) {
					continue;
				}
				try (BufferedReader r = new BufferedReader(new FileReader(bounds))) {
					String[] p = r.readLine().trim().split(",");
					tiles.add(new Tile(Double.parseDouble(p[0]), Double.parseDouble(p[1]),
							Double.parseDouble(p[2]), Double.parseDouble(p[3]), trails));
				} catch (Exception ex) {
					LOGGER.warn("TerrainWalk: bad bounds file {}: {}", bounds, ex.toString());
				}
			}
		}

		int tileCount() {
			return tiles.size();
		}

		/** 0 = no trail or no data, 1 = trail, 2 = road. */
		int at(double lat, double lon) {
			for (Tile t : tiles) {
				if (t.contains(lat, lon)) {
					return t.at(lat, lon);
				}
			}
			return 0;
		}
	}

	/** One simulated person: hourly positions, whether they reached their goal, and (optionally) every step. */
	static final class Walk {
		final List<LatLonGeo> hourly;
		final boolean arrived;
		final List<LatLonGeo> trace;

		Walk(List<LatLonGeo> hourly, boolean arrived, List<LatLonGeo> trace) {
			this.hourly = hourly;
			this.arrived = arrived;
			this.trace = trace;
		}

		/** At most {@code max} evenly spaced points of the trace (or the hourly positions if no trace was kept). */
		List<LatLonGeo> thinnedTrace(int max) {
			List<LatLonGeo> src = trace != null ? trace : hourly;
			if (src.size() <= max) {
				return src;
			}
			List<LatLonGeo> out = new ArrayList<>(max);
			for (int i = 0; i < max; i++) {
				out.add(src.get((int) ((long) i * src.size() / max)));
			}
			return out;
		}
	}

	/** Compass bearing in degrees (0 = north, 90 = east) from {@code from} to {@code to}. */
	static double bearingDeg(LatLonGeo from, LatLonGeo to) {
		double lat1 = Math.toRadians(from.getLatDeg()), lat2 = Math.toRadians(to.getLatDeg());
		double dLon = Math.toRadians(to.getLonDeg() - from.getLonDeg());
		double y = Math.sin(dLon) * Math.cos(lat2);
		double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
		return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
	}

	/**
	 * Goal and slope additions to the walk described above. If {@code goal} is not null, each option's cost is
	 * reduced by {@code goalPull} x the metres of progress it makes toward the goal (negative progress costs), the
	 * first heading defaults to the bearing to the goal, and a person within {@code goalRadiusM} of the goal stops
	 * there for the rest of the simulation. {@code downhillBias} x metres of elevation gained is added to an
	 * option's cost, so climbing is avoided. {@code maxSlope} (rise over run) is the steepest ground crossed.
	 */
	static Walk walk(Random rnd, LatLonGeo start, long startTime, int hours, double speedKph, Double headingDeg,
			double headingSpreadDeg, double courseChangeDeg, int options, double stepM, double randomness, double maxDistanceM,
			MovementSchedule schedule, AreaData area, UserEnteredGeospatialData geo, boolean stayOutOfWater,
			List<LatLonGeo> trace, LatLonGeo goal, CostField field, double goalPull, double goalRadiusM, double downhillBias,
			double maxSlope, TrailGrid trails, double trailFactor, double trailSlope) {
		AbstractElevationData elevation = area.getElevationData();
		AbstractLandCoverData landcover = area.getLandcoverData();
		BoundingBox box = area.getBoundingBox();

		List<LatLonGeo> hourly = new ArrayList<>(hours + 1);
		hourly.add(start);
		if (trace != null) {
			trace.add(start);
		}
		LatLonGeo pos = start;
		boolean arrived = goal != null && start.getDistanceTo(goal) <= goalRadiusM;
		double heading;
		if (headingDeg != null) {
			heading = headingDeg + rnd.nextGaussian() * headingSpreadDeg;
		} else if (goal != null) {
			heading = bearingDeg(start, goal) + rnd.nextGaussian() * headingSpreadDeg;
		} else {
			heading = rnd.nextDouble() * 360.0;
		}
		// people differ: some walk faster than others, for the whole simulation
		double personFactor = Math.max(0.5, Math.min(2.0, Math.exp(0.25 * rnd.nextGaussian())));
		double travelled = 0.0;
		double budget = 0.0; // effort, in metres of flat open ground, that the person may still spend
		long t = startTime;
		int stepsPerHour = (int) (HOUR_MS / STEP_MS);

		for (int h = 0; h < hours; h++) {
			for (int s = 0; s < stepsPerHour; s++) {
				long t1 = t + STEP_MS;
				long movingMs = schedule.timeSpentMoving(t, t1);
				t = t1;
				if (movingMs > 0) {
					budget += speedKph * 1000.0 * ((double) movingMs / HOUR_MS) * personFactor;
				}
				while (budget > 0.0 && travelled < maxDistanceM && !arrived) {
					double elevHere = elevation.getElevationMetersForLatLon(pos);
					double distToGoal = goal == null ? 0.0 : pos.getDistanceTo(goal);
					double costHere = field == null ? Double.POSITIVE_INFINITY : field.costAt(pos);
					int n = 0;
					LatLonGeo[] candPos = new LatLonGeo[options];
					double[] candHeading = new double[options], candCost = new double[options], candEffort = new double[options];
					double minCost = Double.MAX_VALUE;
					for (int k = 0; k < options; k++) {
						double hd = heading + rnd.nextGaussian() * courseChangeDeg;
						LatLonGeo cand = pos.displacedBy(stepM, Math.toRadians(90.0 - hd));
						if (!allowed(cand, box, geo, landcover, stayOutOfWater)) {
							continue;
						}
						double rise = elevation.getElevationMetersForLatLon(cand) - elevHere;
						double slope = rise / stepM;
						boolean onTrail = trails != null && trails.at(cand.getLatDeg(), cand.getLonDeg()) > 0;
						// passability is decided by how steep the ground is where the step lands, whatever the approach angle
						double ground = Math.max(Math.abs(slope), gradient(elevation, cand));
						if (ground > (onTrail ? trailSlope : maxSlope)) {
							continue;
						}
						double grade = onTrail ? Math.max(-TRAIL_MAX_GRADE, Math.min(TRAIL_MAX_GRADE, slope)) : slope;
						double tobler = tobler(grade);
						double soa = landcover.getSoaFactor(cand.getLatDeg(), cand.getLonDeg());
						if (Double.isNaN(soa) || soa <= 0.0) {
							soa = 1.0;
						}
						int lcCost = landcover.getCost(cand.getLatDeg(), cand.getLonDeg());
						double effort = stepM / (soa * tobler);          // metres of flat open ground this step is worth
						if (onTrail) {
							effort *= trailFactor;
						}
						candPos[n] = cand;
						candHeading[n] = hd;
						candEffort[n] = effort;
						double cost = effort * Math.max(1, lcCost);      // what the person tries to minimise
						if (goal != null && goalPull > 0.0) {
							// progress is +1 for the step that best reduces the effort still needed to reach the goal
							// (planned route), 0 for no change, -1 for a step that adds as much as it costs. Without a
							// usable field (off its grid, unreachable), fall back to straight-line progress.
							double progress;
							double costThere = field == null ? Double.POSITIVE_INFINITY : field.costAt(cand);
							if (Double.isFinite(costHere) && Double.isFinite(costThere)) {
								progress = (costHere - costThere) / Math.max(effort, 1.0);
							} else {
								progress = (distToGoal - cand.getDistanceTo(goal)) / stepM;
							}
							cost *= 1.0 + goalPull * (1.0 - Math.max(-1.0, Math.min(1.0, progress)));
						}
						if (rise > 0.0) {
							cost += downhillBias * rise;
						}
						candCost[n] = cost;
						minCost = Math.min(minCost, candCost[n]);
						n++;
					}
					LatLonGeo bestPos = null;
					double bestHeading = heading, bestEffort = 0.0;
					if (n > 0) {
						int pick = 0;
						double scale = randomness * stepM;
						if (scale <= 1e-9) {
							for (int k = 1; k < n; k++) {
								if (candCost[k] < candCost[pick]) {
									pick = k;
								}
							}
						} else if (n > 1) {
							double total = 0.0;
							double[] weight = new double[n];
							for (int k = 0; k < n; k++) {
								weight[k] = Math.exp(-(candCost[k] - minCost) / scale);
								total += weight[k];
							}
							double r = rnd.nextDouble() * total;
							while (pick < n - 1 && (r -= weight[pick]) > 0.0) {
								pick++;
							}
						}
						bestPos = candPos[pick];
						bestHeading = candHeading[pick];
						bestEffort = candEffort[pick];
					}
					if (bestPos == null) {
						// boxed in: turn around somewhere else and lose a little time
						heading = rnd.nextDouble() * 360.0;
						budget -= stepM;
						continue;
					}
					pos = bestPos;
					heading = bestHeading;
					budget -= bestEffort;
					travelled += stepM;
					if (trace != null) {
						trace.add(pos);
					}
					if (goal != null && pos.getDistanceTo(goal) <= goalRadiusM) {
						arrived = true;
					}
				}
			}
			hourly.add(pos);
		}
		return new Walk(hourly, arrived, trace);
	}

	/** Steepness of the ground at p, as rise over run, from elevation samples one DEM cell away in each direction. */
	static double gradient(AbstractElevationData elevation, LatLonGeo p) {
		double d = GRADIENT_SAMPLE_M;
		double n = elevation.getElevationMetersForLatLon(p.displacedBy(d, Math.toRadians(90.0)));
		double s = elevation.getElevationMetersForLatLon(p.displacedBy(d, Math.toRadians(270.0)));
		double e = elevation.getElevationMetersForLatLon(p.displacedBy(d, 0.0));
		double w = elevation.getElevationMetersForLatLon(p.displacedBy(d, Math.toRadians(180.0)));
		double dzdy = (n - s) / (2.0 * d), dzdx = (e - w) / (2.0 * d);
		return Math.sqrt(dzdx * dzdx + dzdy * dzdy);
	}

	private static boolean allowed(LatLonGeo p, BoundingBox box, UserEnteredGeospatialData geo,
			AbstractLandCoverData landcover, boolean stayOutOfWater) {
		if (!box.contains(p)) {
			return false;
		}
		if (geo != null) {
			if (geo.getExclusionZones() != null && GroundUtilities.exclusionZonesContainsPoint(geo.getExclusionZones(), p)) {
				return false;
			}
			List<PolygonalExclusionZone> polys = geo.getPolyExclusions();
			if (polys != null && !polys.isEmpty() && PolygonalExclusionZone.pointInsideSet(polys, p)) {
				return false;
			}
		}
		return !(stayOutOfWater && landcover.isWater(p.getLatDeg(), p.getLonDeg()));
	}

	// ---- the probability map -------------------------------------------------------------------------

	private ProbabilityDistribution distribution(UUID lpiId, long time, BoundingBox bbox, List<LatLonGeo> pts, List<Double> weights) {
		return new ProbabilityDistribution(lpiId, time, makeGrid(bbox, pts, weights, smoothingSettings.get(lpiId)), bbox);
	}

	/**
	 * Bins weighted points into a grid whose [0][0] is the north-west corner (LandSAR's convention), then blurs it
	 * with a Gaussian. The grid size must be exactly what the SDK computes for this bounding box, because
	 * ProbabilityDistribution.validate() rejects any other size. Total probability is preserved (a failed search can
	 * legitimately leave less than 1). Points outside the box are ignored. Package-private and static for testing.
	 *
	 * @param weights   per-point weights, or null for equal weights summing to 1
	 * @param smoothing blur standard deviation in metres, or null for the default (1.5 cells)
	 */
	static double[][] makeGrid(BoundingBox bbox, List<LatLonGeo> pts, List<Double> weights, Double smoothing) {
		double north = bbox.getNorthLatDeg(), south = bbox.getSouthLatDeg(), west = bbox.getWestLonDeg(), east = bbox.getEastLonDeg();
		int[] dims = ContainmentMap.determineNumLonAndNumLat(bbox); // {numLat, numLon}
		int numLat = dims[0], numLon = dims[1];
		double nsM = (north - south) * 111_111.11;
		double ewM = (east - west) * 111_111.11 * Math.cos(Math.toRadians((north + south) / 2.0));
		double cellM = Math.sqrt((nsM / numLat) * (ewM / numLon));
		double smoothM = smoothing == null ? DEFAULT_SMOOTHING_CELLS * cellM : smoothing;
		double[][] grid = new double[numLat][numLon];
		double total = 0.0;
		for (int i = 0; i < pts.size(); i++) {
			LatLonGeo p = pts.get(i);
			double w = weights == null ? 1.0 / pts.size() : weights.get(i);
			int r = (int) (((north - p.getLatDeg()) / (north - south)) * numLat);
			int c = (int) (((p.getLonDeg() - west) / (east - west)) * numLon);
			if (r < 0 || c < 0 || p.getLatDeg() > north || p.getLonDeg() < west || p.getLatDeg() < south || p.getLonDeg() > east) {
				continue; // outside the box
			}
			grid[Math.min(r, numLat - 1)][Math.min(c, numLon - 1)] += w;
			total += w;
		}
		double sigmaRows = smoothM / (nsM / numLat);
		double sigmaCols = smoothM / (ewM / numLon);
		if (smoothM > 0.0 && total > 0.0) {
			grid = blurColumns(grid, gaussian(sigmaCols));
			grid = blurRows(grid, gaussian(sigmaRows));
			double sum = 0.0;
			for (double[] row : grid) {
				for (double v : row) {
					sum += v;
				}
			}
			if (sum > 0.0) {
				double scale = total / sum; // blur loses a little mass at the box edge; put it back
				for (double[] row : grid) {
					for (int j = 0; j < row.length; j++) {
						row[j] *= scale;
					}
				}
			}
		}
		return grid;
	}

	/** Normalised 1-D Gaussian kernel, radius 3 sigma; {1} when sigma is tiny. */
	static double[] gaussian(double sigmaCells) {
		if (sigmaCells < 0.05) {
			return new double[] {1.0};
		}
		int radius = Math.max(1, (int) Math.ceil(3.0 * sigmaCells));
		double[] k = new double[2 * radius + 1];
		double sum = 0.0;
		for (int i = -radius; i <= radius; i++) {
			k[i + radius] = Math.exp(-0.5 * (i / sigmaCells) * (i / sigmaCells));
			sum += k[i + radius];
		}
		for (int i = 0; i < k.length; i++) {
			k[i] /= sum;
		}
		return k;
	}

	private static double[][] blurColumns(double[][] g, double[] k) {
		if (k.length == 1) {
			return g;
		}
		int radius = k.length / 2;
		double[][] out = new double[g.length][g[0].length];
		for (int r = 0; r < g.length; r++) {
			double[] row = g[r];
			for (int c = 0; c < row.length; c++) {
				double v = row[c];
				if (v == 0.0) {
					continue;
				}
				int from = Math.max(0, c - radius), to = Math.min(row.length - 1, c + radius);
				for (int j = from; j <= to; j++) {
					out[r][j] += v * k[j - c + radius];
				}
			}
		}
		return out;
	}

	private static double[][] blurRows(double[][] g, double[] k) {
		if (k.length == 1) {
			return g;
		}
		int radius = k.length / 2;
		double[][] out = new double[g.length][g[0].length];
		for (int r = 0; r < g.length; r++) {
			for (int c = 0; c < g[r].length; c++) {
				double v = g[r][c];
				if (v == 0.0) {
					continue;
				}
				int from = Math.max(0, r - radius), to = Math.min(g.length - 1, r + radius);
				for (int j = from; j <= to; j++) {
					out[j][c] += v * k[j - r + radius];
				}
			}
		}
		return out;
	}

	// ---- searches (generic, via the SDK's internal model) ---------------------------------------------

	@Override
	public DistOrMap calcDistributionWithSearches(MotionModelResult result, long time) {
		ExampleInternalModel internalModel = this.lpiData.get(result.getLpiId());
		return new DistOrMap(internalModel.updateDistributionWithSearches(Collections.singleton(time)).get(time));
	}

	@Override
	public MotionModelResult updateProbabilityDistributionForSearch(MotionModelResult latestResult, LandsarSearch search,
			SearchUtilities searchUtilities) {
		ExampleInternalModel internalModel = this.lpiData.get(latestResult.getLpiId());
		List<Double> pathPds = searchUtilities.getSampleProbabilityDistributions(internalModel.getSamplePaths(), search);
		Map<Long, DistributionBySamplePoints> samplePointDist = internalModel.addSearch(latestResult, search, pathPds);
		MotionModelResult newResult = latestResult.copy(convertToProbabilityDistMap(latestResult, samplePointDist));
		newResult.setGeneratedTimestamp(System.currentTimeMillis());
		return newResult;
	}

	@Override
	public MotionModelResult cancelSearch(MotionModelResult latestResult, LandsarSearch search) {
		ExampleInternalModel internalModel = this.lpiData.get(latestResult.getLpiId());
		Map<Long, DistributionBySamplePoints> samplePointDist = internalModel.removeSearch(latestResult, search);
		MotionModelResult newResult = latestResult.copy(convertToProbabilityDistMap(latestResult, samplePointDist));
		newResult.setGeneratedTimestamp(System.currentTimeMillis());
		return newResult;
	}

	private Map<Long, ProbabilityDistribution> convertToProbabilityDistMap(MotionModelResult latestResult,
			Map<Long, DistributionBySamplePoints> samplePointDist) {
		long aValidTime = latestResult.getInitialDistribution().keySet().iterator().next();
		BoundingBox bbox = latestResult.getInitialDistribution().get(aValidTime).getBoundingBox();
		Map<Long, ProbabilityDistribution> probDistMap = new HashMap<>();
		for (Entry<Long, DistributionBySamplePoints> entry : samplePointDist.entrySet()) {
			DistributionBySamplePoints pointDist = entry.getValue();
			probDistMap.put(entry.getKey(), distribution(latestResult.getLpiId(), entry.getKey(), bbox,
					pointDist.getPoints(), pointDist.getWeights()));
		}
		return probDistMap;
	}

	@Override
	public double calculateProbabilityOfSuccess(UUID lostPersonId, Set<? extends LandsarSearch> searchesToInclude) {
		return this.lpiData.get(lostPersonId).calcCumulativePd(searchesToInclude);
	}

	// ---- saved state ----------------------------------------------------------------------------------

	@Override
	public void deleteModelState(List<UUID> lpisToDelete) {
		for (UUID id : lpisToDelete) {
			lpiData.remove(id);
			smoothingSettings.remove(id);
		}
	}

	@Override
	public void stop() {
		ObjectMapper mapper = new ObjectMapper();
		for (Entry<UUID, ExampleInternalModel> entry : lpiData.entrySet()) {
			try {
				File dataFile = motionModelManager.getOrCreateFileForModelData(entry.getKey(), this.getName(), INTERNAL_MODEL_FILENAME);
				LOGGER.info("writing data to file: {}", dataFile);
				mapper.writeValue(dataFile, entry.getValue());
			} catch (IOException e) {
				LOGGER.error("Error saving {} for LPI ID: {}", INTERNAL_MODEL_FILENAME, entry.getKey(), e);
			}
		}
		this.started = false;
	}

	@Override
	public void restoreModelState(UUID lostPersonId, Map<String, File> tagToDataFile) {
		LOGGER.info("Restoring state for LPI ID={}, file info={}", lostPersonId, tagToDataFile);
		if (tagToDataFile.containsKey(INTERNAL_MODEL_FILENAME)) {
			try {
				lpiData.put(lostPersonId, new ObjectMapper().readValue(tagToDataFile.get(INTERNAL_MODEL_FILENAME), ExampleInternalModel.class));
			} catch (IOException e) {
				LOGGER.error("Error restoring internal model data for LPI {}", lostPersonId, e);
			}
		}
	}
}
