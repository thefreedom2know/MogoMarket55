package com.mogomarket.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/** Small trend line whose slope follows the change percent of the position. */
public class PortfolioTrendView extends View {
    private static final int POINTS = 24;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path();
    private final Path fill = new Path();
    private float changePercent;
    private int color = 0xFFFFB84D;

    public PortfolioTrendView(Context context) {
        this(context, null);
    }

    public PortfolioTrendView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PortfolioTrendView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.5f * getResources().getDisplayMetrics().density);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    public void setChangePercent(float percent, int color) {
        this.changePercent = percent;
        this.color = color;
        rebuild();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuild();
    }

    private void rebuild() {
        line.reset();
        fill.reset();
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float pad = h * 0.12f;
        float usable = h - 2 * pad;
        // The slope follows the change, capped so the line always stays inside the view.
        float strength = Math.max(-1f, Math.min(1f, changePercent / 20f));
        for (int i = 0; i < POINTS; i++) {
            float t = i / (float) (POINTS - 1);
            float wave = (float) Math.sin(t * 9.0) * 0.06f;
            float value = 0.5f + strength * (t - 0.5f) * 0.9f + wave;
            float px = t * w;
            float py = pad + (1f - Math.max(0f, Math.min(1f, value))) * usable;
            if (i == 0) line.moveTo(px, py); else line.lineTo(px, py);
        }
        fill.addPath(line);
        fill.lineTo(w, h);
        fill.lineTo(0, h);
        fill.close();
        linePaint.setColor(color);
        fillPaint.setShader(new LinearGradient(0, 0, 0, h,
                (color & 0x00FFFFFF) | 0x44000000, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawPath(fill, fillPaint);
        canvas.drawPath(line, linePaint);
    }
}
