package com.mogomarket.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Rising chart line drawn progressively on the splash screen (progress 0..1).
 * The line fades from cyan to gold, has a soft area under it and a glowing head.
 */
public class SplashGraphView extends View {
    // Relative heights, 0 = low, 1 = high. Mapped into the lower part of the screen.
    private static final float[] POINTS = {0.10f, 0.30f, 0.22f, 0.48f, 0.40f, 0.66f, 0.58f, 0.82f, 0.95f};

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path fullPath = new Path();
    private final Path visiblePath = new Path();
    private final Path fillPath = new Path();
    private final float[] headPos = new float[2];
    private float progress;

    public SplashGraphView(Context context) {
        this(context, null);
    }

    public SplashGraphView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SplashGraphView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = getResources().getDisplayMetrics().density;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(3.5f * density);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(0xFFFFFFFF);
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
        float top = h * 0.64f;
        float bottom = h * 0.92f;
        float step = (float) w / (POINTS.length - 1);
        float prevX = 0f;
        float prevY = bottom - POINTS[0] * (bottom - top);
        fullPath.moveTo(prevX, prevY);
        for (int i = 1; i < POINTS.length; i++) {
            float x = i * step;
            float y = bottom - POINTS[i] * (bottom - top);
            float mid = (prevX + x) / 2f;
            fullPath.cubicTo(mid, prevY, mid, y, x, y); // smooth curve
            prevX = x;
            prevY = y;
        }
        linePaint.setShader(new LinearGradient(0, 0, w, 0,
                new int[]{0x0022D3EE, 0xFF22D3EE, 0xFFFFB84D}, new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
        fillPaint.setShader(new LinearGradient(0, top, 0, h,
                0x4022D3EE, 0x0022D3EE, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (progress <= 0f) return;
        PathMeasure measure = new PathMeasure(fullPath, false);
        float length = measure.getLength();
        visiblePath.reset();
        measure.getSegment(0f, length * progress, visiblePath, true);

        // soft area under the visible part of the line
        fillPath.reset();
        fillPath.addPath(visiblePath);
        measure.getPosTan(length * progress, headPos, null);
        fillPath.lineTo(headPos[0], getHeight());
        fillPath.lineTo(0f, getHeight());
        fillPath.close();
        canvas.drawPath(fillPath, fillPaint);

        canvas.drawPath(visiblePath, linePaint);

        // glowing head
        float density = getResources().getDisplayMetrics().density;
        float glowRadius = 18f * density;
        glowPaint.setShader(new RadialGradient(headPos[0], headPos[1], glowRadius,
                0xAAFFB84D, 0x00FFB84D, Shader.TileMode.CLAMP));
        canvas.drawCircle(headPos[0], headPos[1], glowRadius, glowPaint);
        canvas.drawCircle(headPos[0], headPos[1], 4f * density, dotPaint);
    }
}
