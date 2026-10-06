package landsar.terrainwalk;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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

		PER_LPI_GEOSPATIAL_ATTRS.add(new GeospatialInputDescriptions(
				UserEnteredGeospatialData.GeospatialDataType.EXCLUSION_ZONE, false));

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

	// ---- plugin plumbing ------------------------------------------------------------------------------

	@Override
	public void configure(Map<String, Object> configurationOptions, PluginContext context)
			throws InsufficientConfigurationException {
		// nothing is configured in config.json
	}

	@Override
	public Set<AttributeDescription> getConfigurationAttributes() {
		return new HashSet<>();
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
		Double smoothing = number(params, SMOOTHING);
		if (smoothing != null && !Double.isNaN(smoothing)) {
			smoothingSettings.put(lpiId, smoothing);
		} else {
			smoothingSettings.remove(lpiId);
		}

		Number seedParam = params.get(MotionModelConstants.RANDOM_SEED_PARAMETER) instanceof Number
				? (Number) params.get(MotionModelConstants.RANDOM_SEED_PARAMETER) : null;
		final long baseSeed = (seedParam == null || seedParam.longValue() == 0L) ? System.nanoTime() : seedParam.longValue();

		motionModelManager.sendInProgressUpdate(this.name + ": simulating " + samples + " people for " + hours + " hours.", status);

		List<Sample> samplePaths = IntStream.range(0, samples).parallel().mapToObj(i -> {
			Random rnd = new Random(baseSeed + i * 0x9E3779B97F4A7C15L);
			LatLonGeo start = starts.get(i % starts.size());
			List<LatLonGeo> hourly = walk(rnd, start, startTime, hours, speedKph, heading, headingSpread, courseChange,
					options, stepM, randomness, maxDistanceM, schedule, areaData, geospatialInputs, stayOutOfWater, null);
			return new Sample(startTime, HOUR_MS, hourly, new ArrayList<Double>());
		}).collect(Collectors.toList());

		ExampleInternalModel model = new ExampleInternalModel();
		model.setSpeed(speedKph);
		model.setDirection(heading == null ? 0.0 : heading);
		model.setDistanceKm(distanceKm == null ? 0.0 : distanceKm);
		model.setSamples(samplePaths);
		lpiData.put(lpiId, model);

		Map<Long, ProbabilityDistribution> distributions = new HashMap<>();
		for (long t = startTime; t <= model.endTime(); t += HOUR_MS) {
			distributions.put(t, distribution(lpiId, t, areaData.getBoundingBox(), model.getLocationsAtTime(t), null));
		}

		motionModelManager.sendInProgressUpdate(this.name + ": finished computing.", status);
		MotionModelResult result = new MotionModelResult();
		result.setLpiId(lpiId);
		result.setInitialDistribution(distributions);
		result.copyInitialDistToDistWithSearches();
		List<Path> paths = samplePaths.stream().limit(PATHS_TO_DISPLAY)
				.map(s -> new Path(s.getPoints())).collect(Collectors.toList());
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
	static List<LatLonGeo> walk(Random rnd, LatLonGeo start, long startTime, int hours, double speedKph, Double headingDeg,
			double headingSpreadDeg, double courseChangeDeg, int options, double stepM, double randomness, double maxDistanceM,
			MovementSchedule schedule, AreaData area, UserEnteredGeospatialData geo, boolean stayOutOfWater,
			List<LatLonGeo> trace) {
		AbstractElevationData elevation = area.getElevationData();
		AbstractLandCoverData landcover = area.getLandcoverData();
		BoundingBox box = area.getBoundingBox();

		List<LatLonGeo> hourly = new ArrayList<>(hours + 1);
		hourly.add(start);
		LatLonGeo pos = start;
		double heading = headingDeg != null ? headingDeg + rnd.nextGaussian() * headingSpreadDeg : rnd.nextDouble() * 360.0;
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
				while (budget > 0.0 && travelled < maxDistanceM) {
					double elevHere = elevation.getElevationMetersForLatLon(pos);
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
						double slope = (elevation.getElevationMetersForLatLon(cand) - elevHere) / stepM;
						if (Math.abs(slope) > MAX_SLOPE) {
							continue;
						}
						double tobler = Math.max(0.1, Math.min(1.25, Math.exp(-3.5 * (Math.abs(slope + 0.05) - 0.05))));
						double soa = landcover.getSoaFactor(cand.getLatDeg(), cand.getLonDeg());
						if (Double.isNaN(soa) || soa <= 0.0) {
							soa = 1.0;
						}
						int lcCost = landcover.getCost(cand.getLatDeg(), cand.getLonDeg());
						double effort = stepM / (soa * tobler);          // metres of flat open ground this step is worth
						candPos[n] = cand;
						candHeading[n] = hd;
						candEffort[n] = effort;
						candCost[n] = effort * Math.max(1, lcCost);      // what the person tries to minimise
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
				}
			}
			hourly.add(pos);
		}
		return hourly;
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
