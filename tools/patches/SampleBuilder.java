/*
LandSAR Motion Model Software Development Kit
Copyright (c) 2023 Raytheon Technologies 

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
https://github.com/atapas/add-copyright.git
*/

/*
 * LANDSAR-BASE LOCAL PATCH (applied by tools/patch_sample_builder.py). Differences from the SDK original:
 *  1. The plain-double constructor takes a COMPASS direction in degrees (0 = north, 90 = east), as the
 *     example plugin's UI label says. The unpatched jar used the raw number as a mathematical angle in
 *     radians, so a direction of 235 went north-west.
 *  2. endTime is set once, when the path first reaches its maximum distance. It used to be reset on every
 *     later step, so whenever rounding left the path length >= the maximum, "time > endTime" never became
 *     true and the model run never finished.
 */
package com.bbn.landsar.motionmodel.path;

import java.util.ArrayList;

import com.bbn.landsar.MovementSchedule;
import com.bbn.landsar.geospatial.AreaData;
import com.bbn.landsar.geospatial.Velocity2d;
import com.bbn.landsar.motionmodel.UserEnteredGeospatialData;
import com.bbn.landsar.utils.DateTimeUtilities;
import com.bbn.landsar.utils.GroundUtilities;
import com.metsci.glimpse.util.geo.LatLonGeo;

public class SampleBuilder {
	

	
	public static class PointAndTime{
		public final LatLonGeo point;
		public final long time;
		
		public PointAndTime(LatLonGeo point, long time) {
			this.point = point;
			this.time = time;
		}
	}

	private final long timeDelta;
	private PathBuilder pathBuilder;
	private long startTime;
	private Long endTime = null;
	private PointAndTime mostRecentPointTime;
	private double direction;
	private double maxDistanceMeters;
	private double speedKph;

	/**
	 * @param compassDegrees direction of travel as a compass bearing in degrees: 0 = north, 90 = east,
	 *        180 = south, 270 = west
	 */
	public SampleBuilder(long startTime, long timeDelta, LatLonGeo point, double compassDegrees, double distanceKm, double speedKph) {
		this(startTime, timeDelta, point, Math.toRadians(90.0 - compassDegrees), distanceKm, speedKph, true);
	}

	// Internal: the heading is already in the radians convention that LatLonGeo.displacedBy uses.
	// The unused 'headingIsRadians' flag only distinguishes this constructor from the public one.
	private SampleBuilder(long startTime, long timeDelta, LatLonGeo point, double headingRadians, double distanceKm, double speedKph, boolean headingIsRadians) {
		// can only be in one place at a time
		this.pathBuilder = new PathBuilder(point);
		this.mostRecentPointTime = new PointAndTime(point, startTime);
		this.startTime = startTime;
		this.timeDelta = timeDelta;
		this.direction = headingRadians;
		this.maxDistanceMeters = distanceKm * 1000.0;
		this.speedKph = speedKph; // kilometers per hour
	}

	public SampleBuilder(long startTime, long timeDelta, LatLonGeo point, Velocity2d directionVector,
			Double distanceKm, Double speed) {
		// a Velocity2d already carries a heading, so it must not go through the compass conversion
		this(startTime, timeDelta, point, directionVector.getHeading(), distanceKm, speed, true);
	}

	void setEndTime(long endTime){
		this.endTime = endTime;
	}
	
	/**
	 * 
	 * @return true iff the endTime has been set and the provided time is equal to or after the endTime
	 */
	public boolean reachedLogicalEnd(long time) {
		if (endTime == null) {
			return false;
		}
		return (time > endTime);
	}

	public void computeNextPoint(long time, MovementSchedule schedule, AreaData areaData, UserEnteredGeospatialData geospatialInputs, boolean stayOutOfWater) {
		PointAndTime previousPointAndTime = this.mostRecentPointTime;
		
		if (previousPointAndTime.time + this.timeDelta != time) {
			//TODO instead of throwing an exception, just add extra points at the same location?
			throw new IllegalArgumentException("method called with unexpected time: " + time + ", expecting :" + (previousPointAndTime.time + this.timeDelta));
		}
		LatLonGeo prevPt = previousPointAndTime.point;
		
		// Movement schedule models when the Lost Person is moving 
		long movingTime = schedule.timeSpentMoving(previousPointAndTime.time, time);
		double movingTimeInHours = movingTime / DateTimeUtilities.millisecInHour;

		double movingDistanceInMeters = 1000 * this.speedKph * movingTimeInHours * areaData.getLandcoverData().getSoaFactor(prevPt.getLatDeg(), prevPt.getLonDeg());
		LatLonGeo newPoint;
		if ((pathBuilder.getPathLength() + movingDistanceInMeters) < maxDistanceMeters) {

			newPoint = prevPt.displacedBy(movingDistanceInMeters, direction);
		} else { // ((pathBuilder.getPathLength() + movingDistanceInMeters) >= maxDistance) {
			// just move up to the max distance
			movingDistanceInMeters = maxDistanceMeters - pathBuilder.getPathLength();
			newPoint = prevPt.displacedBy(movingDistanceInMeters, direction);
			if (isPointValid(newPoint, geospatialInputs, areaData, stayOutOfWater)) {
				this.pathBuilder.appendPoint(newPoint);
				this.mostRecentPointTime = new PointAndTime(newPoint, time);
				if (this.endTime == null) {
					// set once: resetting it on every later step meant "time > endTime" could never become true
					this.endTime = time;
				}
			} else { // the point was not valid
				// no randomness, so don't try to do the same thing again
				this.endTime = previousPointAndTime.time;
			}
			return;
		}
		
		boolean validPoint = isPointValid(newPoint, geospatialInputs, areaData, stayOutOfWater);
		if (validPoint) {
			this.pathBuilder.appendPoint(newPoint);
			this.mostRecentPointTime = new PointAndTime(newPoint, time);
		} else {
			this.endTime = previousPointAndTime.time;
		}

	}

	private boolean isPointValid(LatLonGeo newPoint, UserEnteredGeospatialData geospatialInputs, AreaData areaData, boolean stayOutOfWater) {
		// a more detailed / robust model might handle the point in the exclusion zone by going around it, but we'll assume the lost person just stops if they hit an exclusion zone. 
		if (GroundUtilities.exclusionZonesContainsPoint(geospatialInputs.getExclusionZones(), newPoint)
				// Motion Models can't represent points outside the bounding box, so filter those out
				|| !areaData.getBoundingBox().contains(newPoint)) {
			return false;			
		}
		if (stayOutOfWater) {
			return (!areaData.getLandcoverData().isWater(newPoint.getLatDeg(), newPoint.getLonDeg()));
		}
		return true;
	}

	public Sample build() {
		if (this.endTime == null) {
			throw new IllegalStateException("No end time!");
		}
		//long startTime, long timeDelta, List<LatLonGeo> pts, 	List<Double> resourceLevelChange
		// since we add the points to the pathBuilder with the time delta in mind we should have the correct (time) spacing in the points from pathBuilder.build().getPoints()
		return new Sample(startTime, timeDelta, pathBuilder.build().getPoints(), new ArrayList<Double>());
	}

}
