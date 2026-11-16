package com.mogomarket.app;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
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

        // Outer buttons follow the rounded ends of the dock so the selection never leaves it.
        buttons[0].setBackgroundResource(R.drawable.bg_nav_dock_item_left);
        buttons[buttons.length - 1].setBackgroundResource(R.drawable.bg_nav_dock_item_right);

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
            {"Chart", "View live prices as candles or a line, pick a timeframe, add volume or the 150-day average, and long-press the chart for a crosshair. Pull down to refresh."},
            {"Portfolio", "Your open positions with profit/loss in % and $, today's change and each position's share of the portfolio. Tap the yellow + to add a trade (or import it from a screenshot) and the calculator to size a position."},
            {"Watchlist", "Follow the stocks you care about with a mini chart (green = up today, red = down), pre/post-market prices and price alerts."},
            {"Trades", "Closed trades with results, analytics and history, so you can learn from past decisions."},
            {"Simulator", "Practice trading with simulated positions - no real money involved."},
            {"Settings", "Customize the theme, chart defaults, notifications and the daily summary email."}
    };
    private static final int[] GUIDE_BUTTON_IDS = {
            R.id.nav_chart_button, R.id.nav_portfolio_button, R.id.nav_watchlist_button,
            R.id.nav_trades_button, R.id.nav_simulator_button, R.id.nav_settings_button
    };

    /** Walks through every page of the app, explaining each one while it is on screen. */
    private void showFirstRunGuide(SharedPreferences preferences) {
        showGuideStep(preferences, 0);
    }

    private void showGuideStep(SharedPreferences preferences, int step) {
        View button = findViewById(GUIDE_BUTTON_IDS[step]);
        if (button != null) button.performClick();

        float density = getResources().getDisplayMetrics().density;
        int padding = Math.round(20 * density);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, Math.round(8 * density));

        TextView counter = new TextView(this);
        counter.setText("Step " + (step + 1) + " of " + GUIDE_STEPS.length);
        counter.setTextSize(12f);
        counter.setTextColor(0xFFFFB84D);
        content.addView(counter);

        TextView title = new TextView(this);
        title.setText(GUIDE_STEPS[step][0]);
        title.setTextSize(22f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title);

        TextView body = new TextView(this);
        body.setText(GUIDE_STEPS[step][1]);
        body.setTextSize(15f);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bodyParams.topMargin = Math.round(8 * density);
        content.addView(body, bodyParams);

        boolean last = step == GUIDE_STEPS.length - 1;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(content)
                .setPositiveButton(last ? "Get started" : "Next", (d, which) -> {
                    if (last) {
                        finishFirstRunGuide(preferences);
                    } else {
                        showGuideStep(preferences, step + 1);
                    }
                })
                .setNegativeButton(last ? null : "Skip", (d, which) -> finishFirstRunGuide(preferences))
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
