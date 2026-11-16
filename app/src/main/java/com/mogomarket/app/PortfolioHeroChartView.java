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

/** Decorative smooth area chart used as the background of the portfolio header. */
public class PortfolioHeroChartView extends View {
    private static final float[] POINTS = {
            0.62f, 0.55f, 0.60f, 0.48f, 0.52f, 0.40f, 0.45f, 0.33f, 0.38f, 0.26f, 0.30f, 0.20f
    };

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path();
    private final Path fill = new Path();

    public PortfolioHeroChartView(Context context) {
        this(context, null);
    }

    public PortfolioHeroChartView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PortfolioHeroChartView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(3f * getResources().getDisplayMetrics().density);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setColor(0xFFFFB84D);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        line.reset();
        fill.reset();
        if (w <= 0 || h <= 0) return;
        float step = (float) w / (POINTS.length - 1);
        float prevX = 0f;
        float prevY = POINTS[0] * h;
        line.moveTo(prevX, prevY);
        for (int i = 1; i < POINTS.length; i++) {
            float cx = i * step;
            float cy = POINTS[i] * h;
            float mid = (prevX + cx) / 2f;
            line.cubicTo(mid, prevY, mid, cy, cx, cy);
            prevX = cx;
            prevY = cy;
        }
        fill.addPath(line);
        fill.lineTo(w, h);
        fill.lineTo(0, h);
        fill.close();
        fillPaint.setShader(new LinearGradient(0, 0, 0, h,
                0xAAFFB84D, 0x10FFB84D, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawPath(fill, fillPaint);
        canvas.drawPath(line, linePaint);
    }
}
