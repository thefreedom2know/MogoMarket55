package com.mogomarket.app;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

/**
 * Pull-down-to-refresh container used by every data screen.
 * <ul>
 *   <li>{@link #setScrollTarget(View)}: the list that scrolls; refresh only starts at its top.</li>
 *   <li>{@link #addBlockedView(View)}: views (such as charts) that handle their own drags;
 *       a gesture that starts on them never triggers a refresh.</li>
 * </ul>
 */
public class PullRefreshLayout extends SwipeRefreshLayout {
    private static final long MIN_SPIN_MS = 700L;

    private View scrollTarget;
    private final java.util.List<View> blockedViews = new java.util.ArrayList<>();
    private boolean gestureBlocked;

    public PullRefreshLayout(@NonNull Context context) {
        this(context, null);
    }

    public PullRefreshLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setColorSchemeColors(0xFF22D3EE, 0xFFFFB84D, 0xFF00C896);
        setProgressBackgroundColorSchemeColor(0xFF181E29);
    }

    public void setScrollTarget(@Nullable View target) {
        this.scrollTarget = target;
    }

    public void addBlockedView(@Nullable View view) {
        if (view != null) blockedViews.add(view);
    }

    /** Starts the spinner, runs the refresh, and hides the spinner shortly after. */
    public void setRefreshAction(@NonNull Runnable action) {
        setOnRefreshListener(() -> {
            action.run();
            postDelayed(() -> setRefreshing(false), MIN_SPIN_MS + 500L);
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            gestureBlocked = false;
            int[] location = new int[2];
            Rect rect = new Rect();
            for (View view : blockedViews) {
                if (view.getVisibility() != VISIBLE || !view.isShown()) continue;
                view.getLocationOnScreen(location);
                rect.set(location[0], location[1],
                        location[0] + view.getWidth(), location[1] + view.getHeight());
                if (rect.contains((int) ev.getRawX(), (int) ev.getRawY())) {
                    gestureBlocked = true;
                    break;
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean canChildScrollUp() {
        if (gestureBlocked) return true;
        if (scrollTarget != null) return scrollTarget.canScrollVertically(-1);
        return super.canChildScrollUp();
    }
}
