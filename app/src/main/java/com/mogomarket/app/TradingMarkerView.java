package com.mogomarket.app;

import android.content.Context;
import android.widget.TextView;

import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.data.CandleEntry;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.utils.MPPointF;

import java.util.List;
import java.util.Locale;

/**
 * Invisible marker – the actual info is shown in the fixed top bar inside ChartFragment.
 * We keep this class so existing setMarker() calls compile, but it draws nothing.
 */
public class TradingMarkerView extends MarkerView {

    private List<String> dateLabels;

    // Callback so ChartFragment can update the top-bar
    public interface OnHighlightListener {
        /** price = the candle's close (line value for line charts), index = bar index. */
        void onHighlight(float price, int index);
    }

    private OnHighlightListener listener;

    public TradingMarkerView(Context context) {
        super(context, R.layout.view_chart_marker);
    }

    public void setDateLabels(List<String> labels) {
        this.dateLabels = labels;
    }

    public void setOnHighlightListener(OnHighlightListener l) {
        this.listener = l;
    }

    @Override
    public void refreshContent(Entry e, Highlight highlight) {
        if (e != null && listener != null) {
            int index = Math.round(e.getX());
            // CandleEntry.getY() is the middle of high/low, not a real price: use the close.
            float price = e instanceof CandleEntry ? ((CandleEntry) e).getClose() : e.getY();
            listener.onHighlight(price, index);
        }
        super.refreshContent(e, highlight);
    }

    /** Push the marker completely off-screen so it is invisible */
    @Override
    public MPPointF getOffset() {
        return new MPPointF(-9999f, -9999f);
    }
}
