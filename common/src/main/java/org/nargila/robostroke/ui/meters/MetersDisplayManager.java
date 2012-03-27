/*
 * Copyright (c) 2011 Tal Shalif
 * 
 * This file is part of Talos-Rowing.
 * 
 * Talos-Rowing is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Talos-Rowing is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with Talos-Rowing.  If not, see <http://www.gnu.org/licenses/>.
 */


package org.nargila.robostroke.ui.meters;

import java.util.concurrent.TimeUnit;

import org.nargila.robostroke.BusEvent;
import org.nargila.robostroke.BusEvent.Type;
import org.nargila.robostroke.BusEventListener;
import org.nargila.robostroke.ParamKeys;
import org.nargila.robostroke.RoboStroke;
import org.nargila.robostroke.RoboStrokeEventBus;
import org.nargila.robostroke.common.ClockTime;
import org.nargila.robostroke.input.SensorDataSink;
import org.nargila.robostroke.stroke.RowingSplitMode;
import org.nargila.robostroke.ui.RSClickListener;
import org.nargila.robostroke.ui.RSLongClickListener;
import org.nargila.robostroke.ui.RSView;
import org.nargila.robostroke.way.GPSDataFilter;
import org.nargila.robostroke.way.WayListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Checks and updates rowing timer.
 * This class implements SensorDataSink so it can
 * update itself upon sensor events and does not have to
 * create a timer thread.
 */
public class MetersDisplayManager implements SensorDataSink {

	private static final Logger logger = LoggerFactory.getLogger(MetersDisplayManager.class);
	
	private static final int[] GPS_ACCURACY_BAD_COLOUR = {150, 255, 0, 0};//Color.RED;

	private static final int[] GPS_ACCURACY_FAIR_COLOUR = {170, 255, 255, 0};//Color.YELLOW;

	private static final int[] GPS_ACCURACY_GOOD_COLOUR = {150, 0, 255, 0};//Color.GREEN;

	private static final int[] ROWING_OFF_COLOUR = {0xff, 0xa9, 0xa9, 0xa9};

	private static final int[] ROWING_ON_COLOUR = {0xff, 0, 0, 0};

	private static final int[] ROWING_START_PENDING_COLOUR = {170, 255, 165, 0};
	
	private boolean splitTimerOn;

	/**
	 * timestamp origin for global time
	 */
	private long startTime;
	/**
	 * timestamp start for split time
	 */
	private long splitTimeStart;
	private int startStrokeCount;
	private int lastStrokeCount;
	private int spmAccum;
	private int spmCount;
	
	private long lastTime;
	
	private double accumulatedDistance;
	private double splitDistance;
	
	private boolean hasPower;

	private final RoboStrokeEventBus bus;

	private boolean triggered;

	private boolean resetOnStart = true;

	private long lastStopTime = -1;

	private Long startTimestamp;

	private long baseDistanceTime;

	private double baseDistance;

	private long splitDistanceTime;

	private final MeterView meters;

	private final RoboStroke rs;

	
	public MetersDisplayManager(RoboStroke rs, final MeterView meters) {
		this.rs = rs;
		this.meters = meters;

		bus = rs.getBus();
		

		meters.getSplitTimeTxt().setOnLongClickListener(new RSLongClickListener() {
			
			@Override
			public void onLongClick() {
				resetSplit();

				updateCount();
				updateSplitDistance();
				resetAvgSpm();
				updateTime(lastTime, true);
			}
		});
		
		meters.getSplitTimeTxt().setOnClickListener(new RSClickListener() {

			@Override
			public void onClick() {
				bus.fireEvent(Type.ROWING_START_TRIGGERED, true);
			}

		});
				
		bus.addBusListener(new BusEventListener() {
			
			@Override
			public void onBusEvent(BusEvent event) {
				switch (event.type) {
				case ROWING_START_TRIGGERED:
					triggered = (Boolean)event.data;
					break;
				case ROWING_START:
					logger.info("ROWING_START {}", event.timestamp);
					triggered = false;
					splitTimerOn = true;
					startTimestamp = (Long) event.data;
					splitDistanceTime = 0;
					splitDistance = 0;
					
					if (resetOnStart) {
						resetSplit();
						splitTimeStart = TimeUnit.NANOSECONDS.toSeconds(startTimestamp - startTime);						
					} else {
						if (lastStopTime != -1) {
							splitTimeStart += TimeUnit.NANOSECONDS.toSeconds(startTimestamp - lastStopTime);
						}												
					}
					
					updateCount();
					updateSplitDistance();
					
					break;
				case ROWING_STOP:
					logger.info("ROWING_STOP {}", event.timestamp);
					Object[] vals = (Object[]) event.data;
					triggered = false;
					splitTimerOn = false;
					long stopTime = (Long) vals[0];
					updateTime(TimeUnit.NANOSECONDS.toSeconds(stopTime - startTime), true);
					lastStopTime = stopTime;

					baseDistance += splitDistance;
					baseDistanceTime += splitDistanceTime;
					
					break;
				case ROWING_COUNT:
					lastStrokeCount++;
					updateCount();
					
					break;
				case STROKE_POWER_END:
					hasPower = (Float)event.data > 0;
					
					logger.info("STROKE_POWER_END (has power: {})", hasPower);					
					
					break;
				case STROKE_RATE:
					if (hasPower) {
						int spm = (Integer) event.data;
						spmAccum += spm;
						spmCount++;
						
						updateSpm(spm);

						hasPower = false;
					}
					break;

				case BOOKMARKED_DISTANCE:
					Object[] values = (Object[]) event.data;

					long travelTime = (Long)values[0];
					splitDistance = (Float)values[1];

					logger.info("BOOKMARKED_DISTANCE: elapsedDistance = {}", splitDistance);					
					
					splitDistanceTime = TimeUnit.MILLISECONDS.toSeconds(travelTime);
							
					updateSplitDistance();
					
					break;
				}
			}
		});
		
		rs.getGpsFilter().setWayListener(new WayListener() {

			@Override
			public void onWayUpdate(long timestamp, final double distance,
					final long speed, final double accuracy) {
				
				updateWay(distance, speed, accuracy);
			}

		});	
		
		rs.getAccelerationFilter().addSensorDataSink(this);
	}
	
	
	private void updateWay(final double distance, final long speed,
			final double accuracy) {
		
		double validDistance = speed > 0 ? distance : 0;
		
		updateDistance(validDistance);
		
		updateSpeed(speed, accuracy);
	}
	
	private void updateSpm(final int spm) {
		meters.getSpmTxt().setText(spm + "");

		if (splitTimerOn) {
			float avgSpm = spmAccum / (float)spmCount;

			meters.getAvgSpmTxt().setText(String.format("%.01f", avgSpm));
		}
	}
	
	private void resetAvgSpm() {
		meters.getAvgSpmTxt().setText("0");
	}
	
	/**
	 * format and display distance in the 'distanceTxt' text view
	 * 
	 * @param distance
	 *            in meters
	 */
	private void updateDistance(final double distance) {
		if (distance > 0) {
			accumulatedDistance += distance;
			meters.getTotalDistanceTxt().setText((int)accumulatedDistance + "");
		}
	}

	/**
	 * Update split distance, avg speed meters
	 * 
	 */
	private void updateSplitDistance() {
		meters.getSplitDistanceTxt().setText((int)(splitDistance + baseDistance) + "");
		setAvgSpeed();
	}

	private void setAvgSpeed() {

		long speed500ms = 0;

		if (splitDistance + baseDistance > 20) { // ni bother avg calculation for less than 2 strokes' worth..

			float avgSpeed = (float)(splitDistance + baseDistance) / (splitDistanceTime + baseDistanceTime);

			speed500ms = GPSDataFilter.calcMilisecondsPer500m(avgSpeed);
		}

		meters.getAvgSpeedTxt().setText(formatSpeed(speed500ms));					

	}

	private void updateSpeed(long speed, double accuracy) {
		
		final String display = formatSpeed(speed);
		
		final int[] color = (accuracy <= 2.0 ? GPS_ACCURACY_GOOD_COLOUR
				: (accuracy <= 4.0 ? GPS_ACCURACY_FAIR_COLOUR : GPS_ACCURACY_BAD_COLOUR));
		
		meters.getSpeedTxt().setText(display);
		meters.getAccuracyHighlighter().setBackgroundColor(color);
	}

	private String formatSpeed(long speed) {
		
		String display;
		
		ClockTime speedTime = ClockTime.fromMillis(speed);

		display = String.format("%d:%02d", speedTime.getMinutes(), speedTime.getSeconds());

		return display;
	}
	
	private void updateTime(final long seconds, final boolean splitOnly) { 
		
		if (!splitOnly) {
			lastTime = seconds;
		}

		if (splitTimeStart > seconds) { // can happen in replay mode + skip-back
			splitTimeStart = seconds;
		}
		

		if (!splitOnly) {
			meters.getTotalTimeTxt().setText(formatTime(seconds, false));
		}

		if (splitOnly || splitTimerOn) {
			meters.getSplitTimeTxt().setText(formatTime(seconds - splitTimeStart, true));

		} 

		int[] color;

		if (splitTimerOn) {
			color = ROWING_ON_COLOUR;
		} else if (triggered) {
			color = ROWING_START_PENDING_COLOUR;					 
		} else {
			color = ROWING_OFF_COLOUR;
		}

		highlightTimeMeter(color);

	}
	
	private void highlightTimeMeter(int[] color) {
		RSView highlightBar = meters.getStrokeModeHighlighter();

		highlightBar.setBackgroundColor(color);
	}
	
	void resetSplit() {
		lastStopTime = -1;
		startStrokeCount = lastStrokeCount;
		splitTimeStart = lastTime;
		splitDistance = 0;
		spmAccum = spmCount = 0;
		baseDistance = 0;
		baseDistanceTime = 0;
	}
	
	public void reset() {
		resetSplit();
		
		lastStrokeCount = startStrokeCount = 0; 
		lastTime = startTime = splitTimeStart = 0;
		accumulatedDistance = 0;
		
		splitTimerOn = RowingSplitMode.CONTINUOUS ==  queryRowingMode() ? true : false;

		updateTime(lastTime, true);
		updateCount();
	}
	
	private RowingSplitMode queryRowingMode() {
		Object val = rs.getParameters().getValue(ParamKeys.PARAM_ROWING_MODE);
		return RowingSplitMode.valueOf(val.toString());
	}

	private void updateCount() {
		if (splitTimerOn) {
			int strokeCount = lastStrokeCount - startStrokeCount;
			meters.getStrokeCountTxt().setText(strokeCount + "");
		}
	}

	private String formatTime(long seconds, boolean trimed) {
		long hours = seconds / 3600;
		seconds -= hours * 3600;
		long minutes = seconds / 60;
		seconds -= minutes * 60;

		return (!trimed || hours > 0) ? String.format("%d:%02d:%02d", hours, minutes, seconds) : String.format("%d:%02d", minutes, seconds);			
	}
	
	
	@Override
	public void onSensorData(long timestamp, Object value) {
		
		if (startTime == 0) {
			startTime = timestamp;
		}
		
		final long timeSecs = TimeUnit.NANOSECONDS.toSeconds(timestamp - startTime);

		if (timeSecs != lastTime) {
			updateTime(timeSecs, false);
		}
	}
}