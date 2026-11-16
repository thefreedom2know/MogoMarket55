package com.mogomarket.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Horizontal bar chart of portfolio allocation by asset, largest first. */
public class AssetAllocationChart extends View {
    private static final int[] PALETTE = {
            0xFFFFB84D, 0xFF22D3EE, 0xFF00C896, 0xFF4DA3FF, 0xFFFB5A75, 0xFFB794F4, 0xFF8D9AAE
    };

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final List<Map.Entry<String, Double>> rows = new ArrayList<>();
    private double total;

    public AssetAllocationChart(Context context) {
        this(context, null);
    }

    public AssetAllocationChart(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AssetAllocationChart(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = getResources().getDisplayMetrics().density;
        trackPaint.setColor(0x1AFFFFFF);
        textPaint.setTextSize(11f * density);
        textPaint.setColor(0xFFB8C2D1);
    }

    public void setAllocation(@Nullable Map<String, Double> allocation) {
        rows.clear();
        total = 0;
        if (allocation != null) {
            for (Map.Entry<String, Double> e : allocation.entrySet()) {
                if (e.getValue() != null && e.getValue() > 0) {
                    rows.add(e);
                    total += e.getValue();
                }
            }
            Collections.sort(rows, (a, b) -> Double.compare(b.getValue(), a.getValue()));
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (rows.isEmpty() || total <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        float rowHeight = 20f * density;
        float barLeft = 72f * density;
        float barRight = getWidth() - 44f * density;
        float barHeight = 8f * density;
        for (int i = 0; i < rows.size(); i++) {
            Map.Entry<String, Double> row = rows.get(i);
            float top = i * rowHeight + 4f * density;
            float mid = top + rowHeight / 2f;
            float baseline = mid - (textPaint.ascent() + textPaint.descent()) / 2f;
            double pct = row.getValue() / total * 100.0;

            textPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(row.getKey(), 0, baseline, textPaint);

            rect.set(barLeft, mid - barHeight / 2f, barRight, mid + barHeight / 2f);
            canvas.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, trackPaint);
            barPaint.setColor(PALETTE[i % PALETTE.length]);
            rect.right = barLeft + (barRight - barLeft) * (float) (pct / 100.0);
            canvas.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, barPaint);

            textPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(String.format(Locale.US, "%.1f%%", pct), getWidth(), baseline, textPaint);
        }
    }
}
