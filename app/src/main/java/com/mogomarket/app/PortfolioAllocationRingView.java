package com.mogomarket.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Locale;

/** Ring that shows what share of the portfolio a position represents. */
public class PortfolioAllocationRingView extends View {
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private double percent;

    public PortfolioAllocationRingView(Context context) {
        this(context, null);
    }

    public PortfolioAllocationRingView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PortfolioAllocationRingView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = getResources().getDisplayMetrics().density;
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(6f * density);
        trackPaint.setColor((context.getColor(R.color.text_primary) & 0x00FFFFFF) | 0x33000000);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(6f * density);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(0xFFFFB84D);
        textPaint.setColor(context.getColor(R.color.text_primary));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(12f * density);
        textPaint.setFakeBoldText(true);
    }

    public void setPercent(double percent, int color) {
        this.percent = Math.max(0d, Math.min(100d, percent));
        arcPaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float inset = arcPaint.getStrokeWidth() / 2f + 1f;
        oval.set(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawOval(oval, trackPaint);
        canvas.drawArc(oval, -90f, (float) (percent / 100d * 360d), false, arcPaint);
        String label = String.format(Locale.US, percent >= 10 ? "%.0f%%" : "%.1f%%", percent);
        float baseline = getHeight() / 2f - (textPaint.ascent() + textPaint.descent()) / 2f;
        canvas.drawText(label, getWidth() / 2f, baseline, textPaint);
    }
}
