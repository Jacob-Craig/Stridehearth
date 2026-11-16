/**
 * The fixed geometry of a sensor chart card, shared by the uPlot adapter and the React grid so the
 * reserved layout slot and the drawn canvas cannot drift apart.
 */

/** Height of the uPlot canvas. */
export const CHART_PLOT_HEIGHT_PX = 140;

/** Height of the caption row above the plot: the chart title and its live readout. */
export const CHART_CAPTION_HEIGHT_PX = 28;

/** Height of a whole chart card, also its `contain-intrinsic-size` below the fold. */
export const CHART_CARD_HEIGHT_PX = CHART_CAPTION_HEIGHT_PX + CHART_PLOT_HEIGHT_PX;
