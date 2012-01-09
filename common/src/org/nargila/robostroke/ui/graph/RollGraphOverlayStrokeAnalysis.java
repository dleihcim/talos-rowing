package org.nargila.robostroke.ui.graph;

import org.nargila.robostroke.common.filter.LowpassFilter;
import org.nargila.robostroke.ui.PaintStyle;
import org.nargila.robostroke.ui.RSCanvas;
import org.nargila.robostroke.ui.RSPaint;
import org.nargila.robostroke.ui.RSRect;
import org.nargila.robostroke.ui.UILiaison;

public class RollGraphOverlayStrokeAnalysis  {
	
	private static final float Y_SCALE = 8f;

	private static final double ROLL_PANNEL_DIM_FACTOR = 0.60;
	
	private final int rollAccumSize = 1;
	private int rollAccumCount;
	private float rollAccum;
	
	private final LowpassFilter filter = new LowpassFilter(.5f);
	
	private long rollAccumTimestamp;

	private final UILiaison uiFactory;
	
	private final RollOverlayType rollOverlayType = RollOverlayType.TOP;

	private final CyclicArrayXYSeries rollPanelSeries;

	private final MultiXYSeries multySeries;

	public RollGraphOverlayStrokeAnalysis(UILiaison uiFactory, MultiXYSeries multySeries) {
		this.uiFactory = uiFactory;
		this.multySeries = multySeries;
		
		rollPanelSeries = new CyclicArrayXYSeries(multySeries.xMode, new XYSeries.Renderer(uiFactory.createPaint())) {
				{
					setIndependantYAxis(true);				
				}
			};
		multySeries.addSeries(rollPanelSeries, false);

	}
	
	void drawRollPanels(RSCanvas canvas, RSRect rect, double xAxisSize) {
		XYSeries ser = rollPanelSeries;
		
		final int len = ser.getItemCount();
		
		if (len > 0) {
			final int red = uiFactory.getRedColor();
			final int green = uiFactory.getGreenColor();
			
			RSPaint paint = uiFactory.createPaint();
			paint.setStyle(PaintStyle.FILL);
			paint.setAntiAlias(false);
			paint.setStrokeWidth(0);
			
			final double maxYValue = Y_SCALE / 2;
			final double scaleX = rect.width() / xAxisSize;
			
			final double minX = multySeries.getMinX();
			
			double startX = ser.getX(0);
			double stopX;
			
			for (int i = 1; i < len; ++i, startX = stopX) {
				stopX = ser.getX(i);
				
				double avgY = Math.min(ser.getY(i), maxYValue);
				
				int color = avgY > 0 ? green : red;
				int alpha = (int) ((avgY / maxYValue) * 255 * (rollOverlayType == RollOverlayType.BACKGROUND ? ROLL_PANNEL_DIM_FACTOR : 1));
				
				paint.setColor(color);
				paint.setAlpha(Math.abs(alpha));
				
				float left = (float) ((startX - minX) * scaleX);
				float right = (float) (((stopX - minX) * scaleX));
				
				canvas.drawRect((int)left, rect.top, (int)right, rect.bottom, paint);
			}
		}
	}
	
	void reset() {
		synchronized (multySeries) {
			resetRollAccum();
		}
	}
	
	private void resetRollAccum() {
		rollAccum = 0;
		rollAccumCount = 0;
	}
	
	void updateRoll(long timestamp, float roll) {
		synchronized (multySeries) {

			float y = filter
			.filter(new float[] {roll})[0];

			rollAccum += y;

			if (rollAccumCount++ == 0) {
				rollAccumTimestamp = timestamp;
			}

			if (rollAccumCount == rollAccumSize) {
				rollPanelSeries.add(rollAccumTimestamp, rollAccum
						/ rollAccumSize);
				resetRollAccum();
			}
		}
	}
}
