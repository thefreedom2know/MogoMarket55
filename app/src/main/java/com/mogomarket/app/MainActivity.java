package com.mogomarket.app;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    public static final String PREFS_NAME         = "app_prefs";
    public static final String KEY_THEME          = "dark_mode";
    public static final String KEY_LAST_SYMBOL    = "last_chart_symbol";
    public static final String KEY_DEFAULT_SYMBOL = "default_chart_symbol";
    public static final String KEY_START_PAGE     = "start_page_nav_id";
    private static final String KEY_FIRST_RUN_GUIDE_SHOWN = "first_run_guide_shown";

    private static final int RC_NOTIF = 1002;

    private NavController navController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // הגדרת מצב כהה/בהיר לפני יצירת ה-Activity
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean isDarkMode = prefs.getBoolean(KEY_THEME, true);
        AppCompatDelegate.setDefaultNightMode(
                isDarkMode ? AppCompatDelegate.MODE_NIGHT_YES
                        : AppCompatDelegate.MODE_NIGHT_NO);

        super.onCreate(savedInstanceState);
        EdgeToEdgeHelper.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(systemBars.left, systemBars.top,
                    systemBars.right, systemBars.bottom);
            return insets;
        });

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        requestNotificationPermission();

        // NavHost + NavController
        NavHostFragment navHostFragment =
                (NavHostFragment) getSupportFragmentManager()
                        .findFragmentById(R.id.nav_host);

        if (navHostFragment == null) {
            throw new IllegalStateException("NavHostFragment with id nav_host not found");
        }

        navController = navHostFragment.getNavController();

        setupNavigationDock();
        // עמוד התחלתי לפי ההגדרה השמורה
        if (savedInstanceState == null) {
            int startPageId = prefs.getInt(KEY_START_PAGE, R.id.nav_chart);

            // וידוא שה-ID הוא destination חוקי ב-NavGraph לפני ניווט
            boolean isValidDestination = false;
            try {
                isValidDestination = (navController.getGraph().findNode(startPageId) != null);
            } catch (Exception e) {
                isValidDestination = false;
            }

            if (isValidDestination && startPageId != R.id.nav_chart) {
                navController.navigate(startPageId);
            } else if (!isValidDestination) {
                // נקה ערך פגום מה-SharedPreferences
                prefs.edit().remove(KEY_START_PAGE).apply();
            }
            if (!prefs.getBoolean(KEY_FIRST_RUN_GUIDE_SHOWN, false)) {
                showFirstRunGuide(prefs);
            }
        }

        // שירותי רקע קיימים
        if (prefs.getBoolean(DailySummaryEmailService.KEY_ENABLED, false)) {
            DailySummaryEmailService.scheduleDailySummary(this);
        }
    }

    private void setupNavigationDock() {
        int[] buttonIds = {
                R.id.nav_chart_button,
                R.id.nav_portfolio_button,
                R.id.nav_watchlist_button,
                R.id.nav_trades_button,
                R.id.nav_simulator_button,
                R.id.nav_settings_button
        };
        int[] destinationIds = {
                R.id.nav_chart,
                R.id.nav_portfolio,
                R.id.nav_stocks,
                R.id.nav_closed_trades,
                R.id.nav_simulator,
                R.id.nav_settings
        };
        View[] buttons = new View[buttonIds.length];
        for (int i = 0; i < buttonIds.length; i++) {
            buttons[i] = findViewById(buttonIds[i]);
            final int destinationId = destinationIds[i];
            buttons[i].setOnClickListener(view -> {
                NavOptions options = new NavOptions.Builder()
                        .setLaunchSingleTop(true)
                        .setRestoreState(true)
                        .setEnterAnim(R.anim.nav_fade_in)
                        .setExitAnim(R.anim.nav_fade_out)
                        .setPopEnterAnim(R.anim.nav_fade_in)
                        .setPopExitAnim(R.anim.nav_fade_out)
                        .setPopUpTo(navController.getGraph().getStartDestinationId(), false, true)
                        .build();
                navController.navigate(destinationId, null, options);
            });
        }

        setupDockReordering(buttons);

        navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
            for (int i = 0; i < buttons.length; i++) {
                boolean selected = destination.getId() == destinationIds[i];
                buttons[i].setSelected(selected);
                float scale = 1f; // no enlargement: the selection must stay inside the dock
                buttons[i].animate()
                        .scaleX(scale)
                        .scaleY(scale)
                        .setDuration(160)
                        .start();
            }
        });
    }

    private static final String[][] GUIDE_STEPS = {
            {"Chart", "Live prices as candles or a line, with volume and timeframes."},
            {"Portfolio", "Your open positions with profit/loss. Tap + to add a trade."},
            {"Watchlist", "Follow stocks with a mini chart and price alerts."},
            {"Trades", "Your closed trades and their results."},
            {"Simulator", "Practice trading with no real money."},
            {"Settings", "Theme, chart defaults and notifications. Long-press a bottom icon to move it."}
    };
    private static final String[][] GUIDE_STEPS_HE = {
            {"גרף", "מחירים חיים בנרות או בקו, עם ווליום וטווחי זמן."},
            {"פורטפוליו", "הפוזיציות הפתוחות שלך עם רווח/הפסד. לחץ על + כדי להוסיף עסקה."},
            {"רשימת מעקב", "עקוב אחרי מניות עם גרף קטן והתראות מחיר."},
            {"עסקאות", "העסקאות הסגורות שלך והתוצאות שלהן."},
            {"סימולטור", "תרגל מסחר בלי כסף אמיתי."},
            {"הגדרות", "ערכת נושא, ברירות מחדל לגרף והתראות. לחיצה ארוכה על אייקון בסרגל התחתון מזיזה אותו."}
    };
    private static final int[] GUIDE_BUTTON_IDS = {
            R.id.nav_chart_button, R.id.nav_portfolio_button, R.id.nav_watchlist_button,
            R.id.nav_trades_button, R.id.nav_simulator_button, R.id.nav_settings_button
    };

    /** Walks through every page of the app, explaining each one while it is on screen. */
    private static final String KEY_DOCK_ORDER = "dock_order";

    /** Lets the user long-press a dock icon and drag it to a new place; the order is saved. */
    private void setupDockReordering(View[] buttons) {
        LinearLayout row = findViewById(R.id.navigation_dock_row);
        if (row == null) return;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Restore the saved order.
        String saved = prefs.getString(KEY_DOCK_ORDER, "");
        if (!saved.isEmpty()) {
            String[] parts = saved.split(",");
            if (parts.length == buttons.length) {
                boolean[] used = new boolean[buttons.length];
                List<View> ordered = new ArrayList<>();
                for (String part : parts) {
                    try {
                        int index = Integer.parseInt(part.trim());
                        if (index >= 0 && index < buttons.length && !used[index]) {
                            used[index] = true;
                            ordered.add(buttons[index]);
                        }
                    } catch (NumberFormatException ignored) { }
                }
                if (ordered.size() == buttons.length) {
                    row.removeAllViews();
                    for (View v : ordered) {
                        row.addView(v);
                    }
                }
            }
        }
        updateDockEdgeBackgrounds(row);

        final float[] dragState = new float[3]; // startRawX, grabOffset (px from item left), active
        for (View button : buttons) {
            button.setOnLongClickListener(view -> {
                int[] rowLocation = new int[2];
                row.getLocationOnScreen(rowLocation);
                dragState[1] = dragState[0] - rowLocation[0] - view.getLeft();
                dragState[2] = 1f;
                view.setPressed(false);
                view.setElevation(24f);
                view.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).start();
                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                row.requestDisallowInterceptTouchEvent(true);
                return true;
            });
            button.setOnTouchListener((view, event) -> {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    dragState[0] = event.getRawX();
                    dragState[2] = 0f;
                    return false;
                }
                if (dragState[2] != 1f) return false;
                if (action == MotionEvent.ACTION_MOVE) {
                    int count = row.getChildCount();
                    float itemWidth = (float) row.getWidth() / count;
                    int[] rowLocation = new int[2];
                    row.getLocationOnScreen(rowLocation);
                    float desiredLeft = event.getRawX() - rowLocation[0] - dragState[1];
                    desiredLeft = Math.max(0f, Math.min(desiredLeft, row.getWidth() - itemWidth));
                    int target = Math.max(0, Math.min(count - 1,
                            Math.round(desiredLeft / itemWidth)));
                    int current = row.indexOfChild(view);
                    if (target != current) {
                        row.removeView(view);
                        row.addView(view, target);
                        updateDockEdgeBackgrounds(row);
                        current = target;
                    }
                    view.setTranslationX(desiredLeft - current * itemWidth);
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    dragState[2] = 0f;
                    view.animate().translationX(0f).scaleX(1f).scaleY(1f).setDuration(160)
                            .withEndAction(() -> view.setElevation(0f)).start();
                    StringBuilder order = new StringBuilder();
                    for (int i = 0; i < row.getChildCount(); i++) {
                        for (int b = 0; b < buttons.length; b++) {
                            if (buttons[b] == row.getChildAt(i)) {
                                if (order.length() > 0) order.append(',');
                                order.append(b);
                            }
                        }
                    }
                    prefs.edit().putString(KEY_DOCK_ORDER, order.toString()).apply();
                    return true;
                }
                return true;
            });
        }
    }

    /** The outer items follow the rounded ends of the dock so the selection never leaves it. */
    private void updateDockEdgeBackgrounds(LinearLayout row) {
        int count = row.getChildCount();
        for (int i = 0; i < count; i++) {
            int background = i == 0 ? R.drawable.bg_nav_dock_item_left
                    : i == count - 1 ? R.drawable.bg_nav_dock_item_right
                    : R.drawable.bg_nav_dock_item;
            row.getChildAt(i).setBackgroundResource(background);
        }
    }

    private void showFirstRunGuide(SharedPreferences preferences) {
        new AlertDialog.Builder(this)
                .setTitle("Language / שפה")
                .setMessage("In which language should the tour be? / באיזו שפה להציג את ההסבר?")
                .setPositiveButton("English", (d, w) -> showGuideStep(preferences, 0, false))
                .setNegativeButton("עברית", (d, w) -> showGuideStep(preferences, 0, true))
                .setCancelable(false)
                .show();
    }

    private void showGuideStep(SharedPreferences preferences, int step, boolean hebrew) {
        String[][] steps = hebrew ? GUIDE_STEPS_HE : GUIDE_STEPS;
        View button = findViewById(GUIDE_BUTTON_IDS[step]);
        if (button != null) button.performClick();

        float density = getResources().getDisplayMetrics().density;
        int padding = Math.round(20 * density);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, Math.round(8 * density));

        TextView counter = new TextView(this);
        counter.setText(hebrew ? "שלב " + (step + 1) + " מתוך " + steps.length
                : "Step " + (step + 1) + " of " + steps.length);
        counter.setTextSize(12f);
        counter.setTextColor(0xFFFFB84D);
        content.addView(counter);

        TextView title = new TextView(this);
        title.setText(steps[step][0]);
        title.setTextSize(22f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title);

        TextView body = new TextView(this);
        body.setText(steps[step][1]);
        body.setTextSize(15f);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bodyParams.topMargin = Math.round(8 * density);
        content.addView(body, bodyParams);

        if (hebrew) {
            for (TextView t : new TextView[]{counter, title, body}) {
                t.setTextDirection(View.TEXT_DIRECTION_RTL);
                t.setGravity(android.view.Gravity.RIGHT);
            }
        }
        boolean last = step == steps.length - 1;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(content)
                .setPositiveButton(last ? (hebrew ? "בואו נתחיל" : "Get started") : (hebrew ? "הבא" : "Next"), (d, which) -> {
                    if (last) {
                        finishFirstRunGuide(preferences);
                    } else {
                        showGuideStep(preferences, step + 1, hebrew);
                    }
                })
                .setNegativeButton(last ? null : (hebrew ? "דלג" : "Skip"), (d, which) -> finishFirstRunGuide(preferences))
                .setCancelable(false)
                .create();
        dialog.setOnShowListener(ignored -> {
            android.view.Window window = dialog.getWindow();
            if (window == null) return;
            // Keep the page itself visible: card at the top, light dim.
            window.setGravity(android.view.Gravity.TOP);
            window.setDimAmount(0.2f);
            android.view.WindowManager.LayoutParams lp = window.getAttributes();
            lp.y = Math.round(40 * density);
            window.setAttributes(lp);
        });
        dialog.show();
    }

    private void finishFirstRunGuide(SharedPreferences preferences) {
        preferences.edit().putBoolean(KEY_FIRST_RUN_GUIDE_SHOWN, true).apply();
        View first = findViewById(GUIDE_BUTTON_IDS[0]);
        if (first != null) first.performClick();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        RC_NOTIF
                );
            }
        }
    }

    /**
     * מעבר לגרף עם סימבול מסוים.
     * מעדכן גם SharedViewModel וגם SharedPreferences.
     */
    public void showChartWithSymbol(String symbol) {
        if (symbol == null || symbol.trim().isEmpty()) return;

        String cleanSymbol = symbol.trim().toUpperCase();

        // שמירה ב-SharedPreferences (לשימוש עתידי)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(KEY_LAST_SYMBOL, cleanSymbol)
                .apply();

        // SharedViewModel משותף
        SharedViewModel vm = new ViewModelProvider(this)
                .get(SharedViewModel.class);
        vm.setSelectedSymbol(cleanSymbol);

        // ניווט למסך הגרף
        if (navController != null) {
            navController.navigate(R.id.nav_chart);
        }
    }

    /**
     * פתיחת מחשבון PnL כ-screen בתוך ה-NavGraph.
     */
    public void openPnlCalculator() {
        if (navController != null) {
            navController.navigate(R.id.pnlCalculatorFragment);
        }
    }

    /**
     * פתיחת Settings דרך ה-NavController.
     */
    public void openSettings() {
        if (navController != null) {
            navController.navigate(R.id.nav_settings);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // אפס את ה-session symbol כדי שפתיחה הבאה תתחיל מ-fixed
        SharedViewModel vm = new ViewModelProvider(this).get(SharedViewModel.class);
        vm.clearSessionSymbol();
    }

    public void applyThemeToAllFragments(boolean isDark) {
        // עדכן את כל ה-Fragments הקיימים
        for (Fragment f : getSupportFragmentManager().getFragments()) {
            if (f instanceof ChartFragment) {
                ((ChartFragment) f).applyThemeExternal(isDark);
            }
            // ניתן להוסיף כאן WatchlistFragment, PortfolioFragment וכו' לפי הצורך
        }

        View dockBackground = findViewById(R.id.navigation_dock_background);
        if (dockBackground != null) {
            dockBackground.setBackgroundResource(R.drawable.bg_liquid_glass);
        }

        // עדכן את הרקע הכללי
        View root = findViewById(android.R.id.content);
        if (root != null) root.setBackgroundColor(isDark ? 0xFF07090D : 0xFFF0F4F8);
    }

}
