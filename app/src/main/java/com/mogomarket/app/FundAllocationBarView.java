package com.mogomarket.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/** One rounded bar split into colored parts (the funds' allocation by track), drawn right to left. */
public class FundAllocationBarView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private double[] values = new double[0];
    private int[] colors = new int[0];

    public FundAllocationBarView(Context context) {
        super(context);
    }

    public FundAllocationBarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public void setParts(double[] values, int[] colors) {
        this.values = values == null ? new double[0] : values;
        this.colors = colors == null ? new int[0] : colors;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        double total = 0;
        for (double v : values) total += Math.max(0, v);
        rect.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(rect, h / 2f, h / 2f, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        if (total <= 0) {
            paint.setColor(0x33888888);
            canvas.drawRect(rect, paint);
        } else {
            float right = w;
            for (int i = 0; i < values.length; i++) {
                float part = (float) (Math.max(0, values[i]) / total * w);
                paint.setColor(colors.length > 0 ? colors[i % colors.length] : 0xFF22D3EE);
                canvas.drawRect(right - part, 0, right, h, paint);
                right -= part;
            }
        }
        canvas.restore();
    }
}
