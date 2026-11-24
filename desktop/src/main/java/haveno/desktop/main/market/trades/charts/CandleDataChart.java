/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.desktop.main.market.trades.charts;

import javafx.beans.property.ObjectProperty;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.chart.Axis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.shape.Line;

import java.util.List;

/**
 * A chart of candle data which marks the candle nearest to the cursor with a crosshair and describes it in a
 * legend pinned to the top left corner. Charts sharing the hovered candle track it together.
 */
public abstract class CandleDataChart extends XYChart<Number, Number> {

    private static final double LEGEND_INSET = 8;
    private static final double LEGEND_GAP = 14;

    private final ObjectProperty<CandleData> hoveredCandle;
    private final FlowPane legend = new FlowPane(LEGEND_GAP, 0);
    private final Line crosshairVertical = new Line();
    private final Line crosshairHorizontal = new Line();

    protected CandleDataChart(Axis<Number> xAxis, Axis<Number> yAxis, ObjectProperty<CandleData> hoveredCandle) {
        super(xAxis, yAxis);
        this.hoveredCandle = hoveredCandle;
        legend.getStyleClass().add("chart-hover-legend");
        legend.relocate(LEGEND_INSET, LEGEND_INSET);
        crosshairVertical.getStyleClass().add("chart-crosshair");
        crosshairHorizontal.getStyleClass().add("chart-crosshair");
        for (Node overlay : List.of(crosshairVertical, crosshairHorizontal, legend)) {
            overlay.setManaged(false);
            overlay.setMouseTransparent(true);
            overlay.setVisible(false);
        }
        getPlotChildren().addAll(crosshairVertical, crosshairHorizontal, legend);
        setOnMouseMoved(this::trackCursor);
        setOnMouseExited(e -> hoveredCandle.set(null));
        hoveredCandle.addListener((observable, oldValue, newValue) -> showHoveredCandle(newValue));
    }

    /**
     * Called to update and layout the candles of the plot.
     */
    protected abstract void layoutCandles();

    /**
     * Returns the legend entries which describe the given candle.
     */
    protected abstract List<String> getLegendEntries(CandleData candleData);

    @Override
    protected final void layoutPlotChildren() {
        layoutCandles();
        layoutOverlays();
    }

    // hover the candle nearest to the cursor anywhere in the plot area and mark the cursor's level
    private void trackCursor(MouseEvent e) {
        Point2D cursor = legend.getParent().sceneToLocal(e.getSceneX(), e.getSceneY());
        boolean insidePlotArea = cursor.getX() >= 0 && cursor.getX() <= getXAxis().getWidth() &&
                cursor.getY() >= 0 && cursor.getY() <= getYAxis().getHeight();
        hoveredCandle.set(insidePlotArea ? findNearestCandle(cursor.getX()) : null);
        if (hoveredCandle.get() == null) {
            return;
        }
        crosshairHorizontal.setStartY(cursor.getY());
        crosshairHorizontal.setEndY(cursor.getY());
        crosshairHorizontal.setVisible(true);
    }

    private CandleData findNearestCandle(double plotX) {
        if (getData() == null) {
            return null;
        }
        CandleData nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (XYChart.Series<Number, Number> series : getData()) {
            for (XYChart.Data<Number, Number> item : series.getData()) {
                if (!(item.getExtraValue() instanceof CandleData)) {
                    continue;
                }
                double distance = Math.abs(getXAxis().getDisplayPosition(item.getXValue()) - plotX);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = (CandleData) item.getExtraValue();
                }
            }
        }
        return nearest;
    }

    private void showHoveredCandle(CandleData candleData) {
        crosshairVertical.setVisible(candleData != null);
        legend.setVisible(candleData != null);
        if (candleData == null) {
            crosshairHorizontal.setVisible(false);
            return;
        }
        legend.getChildren().setAll(getLegendEntries(candleData).stream().map(Label::new).toList());
        legend.applyCss();
        layoutOverlays();

        // keep the overlays above candles added since the last hover, with the legend on top
        crosshairVertical.toFront();
        crosshairHorizontal.toFront();
        legend.toFront();
    }

    // align the crosshair and the legend with the hovered candle, which moves when the plot is resized
    private void layoutOverlays() {
        CandleData candleData = hoveredCandle.get();
        if (candleData == null) {
            return;
        }
        double candleX = getXAxis().getDisplayPosition(candleData.tick);
        crosshairVertical.setStartX(candleX);
        crosshairVertical.setEndX(candleX);
        crosshairVertical.setEndY(getYAxis().getHeight());
        crosshairHorizontal.setEndX(getXAxis().getWidth());
        legend.setPrefWrapLength(getXAxis().getWidth() - 2 * LEGEND_INSET);
        legend.autosize();
    }
}
