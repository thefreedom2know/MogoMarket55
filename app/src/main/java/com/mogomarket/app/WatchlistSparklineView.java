package com.mogomarket.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.List;

/** Minimal price sparkline for watchlist rows. */
public class WatchlistSparklineView extends View {
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float[] values = new float[0];

    public WatchlistSparklineView(Context context) {
        this(context, null);
    }

    public WatchlistSparklineView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WatchlistSparklineView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setColor(0xFF087E8B);
    }

    public void setData(@Nullable List<? extends Number> data, int color) {
        linePaint.setColor(color);
        if (data == null) {
            values = new float[0];
        } else {
            values = new float[data.size()];
            for (int i = 0; i < values.length; i++) values[i] = data.get(i).floatValue();
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || values.length < 2) return;
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (float v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        float range = max - min;
        if (range <= 0f) range = 1f;
        float pad = linePaint.getStrokeWidth();
        path.reset();
        for (int i = 0; i < values.length; i++) {
            float px = i * (float) w / (values.length - 1);
            float py = pad + (1f - (values[i] - min) / range) * (h - 2 * pad);
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        canvas.drawPath(path, linePaint);
    }
}
