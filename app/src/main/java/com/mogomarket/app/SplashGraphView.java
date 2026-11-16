package com.mogomarket.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.util.AttributeSet;
import android.view.View;

/** Rising chart line drawn progressively on the splash screen (progress 0..1). */
public class SplashGraphView extends View {
    private static final float[] POINTS = {0.85f, 0.7f, 0.76f, 0.55f, 0.62f, 0.4f, 0.47f, 0.25f, 0.15f};

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path fullPath = new Path();
    private final Path visiblePath = new Path();
    private float progress;

    public SplashGraphView(Context context) {
        this(context, null);
    }

    public SplashGraphView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SplashGraphView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(4f * getResources().getDisplayMetrics().density);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setColor(0xFF22D3EE);
    }

    public void setProgress(float progress) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fullPath.reset();
        if (w <= 0 || h <= 0) return;
        float step = (float) w / (POINTS.length - 1);
        fullPath.moveTo(0, POINTS[0] * h);
        for (int i = 1; i < POINTS.length; i++) fullPath.lineTo(i * step, POINTS[i] * h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (progress <= 0f) return;
        PathMeasure measure = new PathMeasure(fullPath, false);
        visiblePath.reset();
        measure.getSegment(0f, measure.getLength() * progress, visiblePath, true);
        canvas.drawPath(visiblePath, linePaint);
    }
}
