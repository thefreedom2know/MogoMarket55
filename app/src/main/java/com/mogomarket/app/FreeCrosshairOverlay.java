package com.mogomarket.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/** Draws a free (non-snapping) crosshair on top of the chart. */
public class FreeCrosshairOverlay extends View {
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private float x;
    private float y;
    private boolean active;

    public FreeCrosshairOverlay(Context context) {
        this(context, null);
    }

    public FreeCrosshairOverlay(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FreeCrosshairOverlay(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = getResources().getDisplayMetrics().density;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(density);
        linePaint.setColor(0xCC8D9AAE);
        linePaint.setPathEffect(new DashPathEffect(new float[]{6f * density, 4f * density}, 0f));
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(0xFFFFB84D);
    }

    public void showAt(float x, float y, RectF content) {
        this.x = x;
        this.y = y;
        bounds.set(content);
        active = true;
        setVisibility(VISIBLE);
        invalidate();
    }

    public void hide() {
        active = false;
        setVisibility(GONE);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!active) return;
        canvas.drawLine(x, bounds.top, x, bounds.bottom, linePaint);
        canvas.drawLine(bounds.left, y, bounds.right, y, linePaint);
        canvas.drawCircle(x, y, 4f * getResources().getDisplayMetrics().density, dotPaint);
    }
}
