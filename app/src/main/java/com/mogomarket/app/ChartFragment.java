package com.mogomarket.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;

import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.github.mikephil.charting.charts.CombinedChart;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.CandleData;
import com.github.mikephil.charting.data.CandleDataSet;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.CandleEntry;
import com.github.mikephil.charting.data.CombinedData;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.listener.ChartTouchListener;
import com.github.mikephil.charting.listener.OnChartGestureListener;


import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ChartFragment extends Fragment implements TimeFrameFragment.TimeFrameListener {

    private static final String PREFS_NAME = "app_prefs";

    private static final String KEY_THEME       = "dark_mode";
    private static final String KEY_LAST_SYMBOL = "last_chart_symbol";

    public static final String KEY_SYMBOL_MODE  = "chart_symbol_mode";
    public static final String KEY_DEFAULT_TIMEFRAME = "chart_default_timeframe";
    public static final String KEY_CHANGE_PERCENT_DAILY = "chart_change_percent_daily";

    private static final int DARK_BG        = 0xFF07090D;
    private static final int DARK_CARD      = 0xFF121720;
    private static final int DARK_TEXT_PRI  = 0xFFF5F7FA;
    private static final int DARK_TEXT_SEC  = 0xFF96A3B5;
    private static final int LIGHT_BG       = 0xFFF0F4F8;
    private static final int LIGHT_CARD     = 0xFFFFFFFF;
    private static final int LIGHT_TEXT_PRI = 0xFF1A1D23;
    private static final int LIGHT_TEXT_SEC = 0xFF6B7280;
    private static final int COLOR_PRIMARY  = 0xFF22D3EE;
    private static final int COLOR_GAIN     = 0xFF10B981;
    private static final int COLOR_LOSS     = 0xFFFB5A75;
    private static final int COLOR_FILL     = 0xFF0891B2;

    private static final long DOUBLE_TAP_TIMEOUT_MS = 350;
    private long lastTapTime = 0;

    private static final String[][] TIMEFRAMES = {
        {"1D",  "1day", "1d", "1d",  "1d"},
        {"1W",  "1day", "1d", "5d",  "1d"},
        {"1M",  "1day", "1d", "1mo", "1d"},
        {"3M",  "1day", "1d", "3mo", "1d"},
        {"6M",  "1day", "1d", "6mo", "1d"},
        {"1Y",  "1day", "1d", "1y",  "1d"},
        {"2Y",  "1day", "1d", "2y",  "1d"},
        {"5Y",  "1day", "1d", "5y",  "1d"},
        {"MAX", "1day", "1d", "max", "1d"},
    };

    static final Map<String, String> CRYPTO_MAP = new HashMap<>();
    static {
        CRYPTO_MAP.put("BTC",      "BINANCE:BTCUSDT");
        CRYPTO_MAP.put("BTCUSD",   "BINANCE:BTCUSDT");
        CRYPTO_MAP.put("BTCUSDT",  "BINANCE:BTCUSDT");
        CRYPTO_MAP.put("ETH",      "BINANCE:ETHUSDT");
        CRYPTO_MAP.put("ETHUSD",   "BINANCE:ETHUSDT");
        CRYPTO_MAP.put("ETHUSDT",  "BINANCE:ETHUSDT");
        CRYPTO_MAP.put("XRP",      "BINANCE:XRPUSDT");
        CRYPTO_MAP.put("XRPUSD",   "BINANCE:XRPUSDT");
        CRYPTO_MAP.put("SOL",      "BINANCE:SOLUSDT");
        CRYPTO_MAP.put("SOLUSD",   "BINANCE:SOLUSDT");
        CRYPTO_MAP.put("BNB",      "BINANCE:BNBUSDT");
        CRYPTO_MAP.put("BNBUSD",   "BINANCE:BNBUSDT");
        CRYPTO_MAP.put("DOGE",     "BINANCE:DOGEUSDT");
        CRYPTO_MAP.put("DOGEUSD",  "BINANCE:DOGEUSDT");
        CRYPTO_MAP.put("DOGEUSDT", "BINANCE:DOGEUSDT");
        CRYPTO_MAP.put("ADA",      "BINANCE:ADAUSDT");
        CRYPTO_MAP.put("ADAUSD",   "BINANCE:ADAUSDT");
        CRYPTO_MAP.put("AVAX",     "BINANCE:AVAXUSDT");
        CRYPTO_MAP.put("AVAXUSD",  "BINANCE:AVAXUSDT");
        CRYPTO_MAP.put("DOT",      "BINANCE:DOTUSDT");
        CRYPTO_MAP.put("DOTUSD",   "BINANCE:DOTUSDT");
        CRYPTO_MAP.put("LINK",     "BINANCE:LINKUSDT");
        CRYPTO_MAP.put("LINKUSD",  "BINANCE:LINKUSDT");
        CRYPTO_MAP.put("LTC",      "BINANCE:LTCUSDT");
        CRYPTO_MAP.put("LTCUSD",   "BINANCE:LTCUSDT");
        CRYPTO_MAP.put("MATIC",    "BINANCE:MATICUSDT");
        CRYPTO_MAP.put("MATICUSD", "BINANCE:MATICUSDT");
        CRYPTO_MAP.put("UNI",      "BINANCE:UNIUSDT");
        CRYPTO_MAP.put("UNIUSD",   "BINANCE:UNIUSDT");
    }

    // ── מפת פורקס: סימבול → Yahoo Finance ticker ────────────────────────────
    static final Map<String, String> FOREX_MAP = new HashMap<>();
    static {
        FOREX_MAP.put("EURUSD",  "EURUSD=X");
        FOREX_MAP.put("USDILS",  "USDILS=X");
        FOREX_MAP.put("GBPUSD",  "GBPUSD=X");
        FOREX_MAP.put("USDJPY",  "USDJPY=X");
        FOREX_MAP.put("AUDUSD",  "AUDUSD=X");
        FOREX_MAP.put("USDCAD",  "USDCAD=X");
        FOREX_MAP.put("USDCHF",  "USDCHF=X");
        FOREX_MAP.put("NZDUSD",  "NZDUSD=X");
        FOREX_MAP.put("EURGBP",  "EURGBP=X");
        FOREX_MAP.put("EURJPY",  "EURJPY=X");
        FOREX_MAP.put("GBPJPY",  "GBPJPY=X");
        FOREX_MAP.put("USDINR",  "USDINR=X");
        FOREX_MAP.put("USDCNY",  "USDCNY=X");
        FOREX_MAP.put("USDBRL",  "USDBRL=X");
        FOREX_MAP.put("USDMXN",  "USDMXN=X");
        FOREX_MAP.put("EURILS",  "EURILS=X");
        FOREX_MAP.put("GBPILS",  "GBPILS=X");
        FOREX_MAP.put("XAUUSD",  "GC=F");
        FOREX_MAP.put("GOLD",    "GC=F");
        FOREX_MAP.put("XAGUSD",  "SI=F");
        FOREX_MAP.put("SILVER",  "SI=F");
        FOREX_MAP.put("XBRUSD",  "BZ=F");
        FOREX_MAP.put("OIL",     "CL=F");
        FOREX_MAP.put("CRUDE",   "CL=F");
        FOREX_MAP.put("WTI",     "CL=F");
        FOREX_MAP.put("USOIL",   "CL=F");
        FOREX_MAP.put("CRUDEOIL","CL=F");
        FOREX_MAP.put("BRENT",   "BZ=F");
        FOREX_MAP.put("UKOIL",   "BZ=F");
        FOREX_MAP.put("VIX",     "^VIX");
        FOREX_MAP.put("^VIX",    "^VIX");
        FOREX_MAP.put("VIXINDEX","^VIX");
    }

    private boolean isDarkTheme;
    private boolean isChartDark;
    private boolean hasDisplayedChangeMode;
    private boolean lastDisplayedDailyChange;
    private boolean isFullscreen = false;
    private boolean isCrosshairActive = false;

    private int currentTFIndex = 5;

    private com.google.android.material.button.MaterialButton activeTFButton = null;

    private ViewGroup.LayoutParams chartOriginalParams;

    private CombinedChart candleStickChart;
    private LineChart lineChart;
    private AutoCompleteTextView tickerInput;

    private com.google.android.material.button.MaterialButton btnTickerSelect;
    private com.google.android.material.button.MaterialButton btnTimeframePicker;
    private PullRefreshLayout pullRefreshLayout;
    private com.google.android.material.button.MaterialButton btnChartMenu;
    private View btnChartAi;
    private com.google.android.material.button.MaterialButton btnMagnetMode;
    private Button btnLoad, btnTimeFrame;
    private com.google.android.material.button.MaterialButton btnExpandChart, btnExitFullscreen, btnSettings;
    private Button btnChartThemeToggle;

    private View headerSection;
    private View searchSection;
    private View bottomBar;
    private FrameLayout chartContainer;
    private View chartRootLayout;
    private TextView timeFrameText, tickerText, priceText, changeText, currentPriceDisplay;
    private TextView tickerLabelTop;
    private TextView chartTickerTitle;

    private LinearLayout crosshairInfoBar;
    private TextView crosshairPrice;
    private TextView crosshairDate;
    private FreeCrosshairOverlay freeCrosshairOverlay;
    private float freeCrosshairX;
    private float freeCrosshairY;
    private float crosshairDragStartTouchX;
    private float crosshairDragStartTouchY;
    private float crosshairDragStartX;
    private float crosshairDragStartY;
    private boolean crosshairGestureMoved;
    private boolean crosshairDismissGesture;

    private final OkHttpClient client = new OkHttpClient();
    private final String FINNHUB_KEY = "d918pn9r01qr1uqui560d918pn9r01qr1uqui56g";

    private String symbol = "SPY";
    private String interval = "1day";
    private String visibleChartRange = "1d";
    /** Candle size in minutes: 0 = daily (default), -1 = weekly, otherwise intraday minutes. */
    private int candleMinutes = 0;
    private static final int[] CANDLE_VALUES = {0, 1, 5, 15, 30, 60, 65, 90, 120, 240, -1};
    private static final String[] CANDLE_LABELS = {"1 day (default)", "1 minute", "5 minutes",
            "15 minutes", "30 minutes", "1 hour", "65 minutes", "90 minutes", "2 hours",
            "4 hours", "1 week"};
    private boolean isCandleStick = true;
    private final DecimalFormat df = new DecimalFormat("#.##");
    private String latestQuery = "";

    private final List<CandleEntry> currentEntries = new ArrayList<>();
    private final List<Float> fullCloses = new ArrayList<>();
    private final List<String> dateLabels = new ArrayList<>();
    private final List<Long> currentTimestamps = new ArrayList<>();
    private final List<Entry> movingAverageEntries = new ArrayList<>();
    private boolean showMa150;
    private boolean indMa20, indMa50, indMa200, indBoll;
    private int indPane; // 0 none, 1 RSI, 2 MACD
    private CombinedChart indicatorChart;
    private boolean paneShown;
    private float lastSyncScale = Float.NaN, lastSyncTrans = Float.NaN;
    private android.view.ViewTreeObserver.OnDrawListener syncListener;
    private float crosshairPriceValue;
    private int drawMode; // 0 none, 1 horizontal, 2 trend line, 3 Fibonacci
    private boolean hasPendingPoint;
    private long pendingT;
    private float pendingP;
    private boolean showVolume;
    private boolean volumeColorByOpen; // false: vs previous close (daily), true: open to close
    private final List<Float> currentVolumes = new ArrayList<>();
    private List<Float> pendingVolumes = new ArrayList<>();
    private float lastPrice = 0f;
    private boolean isManualSelection = false;
    private boolean magnetMode;
    private int chartRequestGeneration;
    private com.github.mikephil.charting.charts.BarLineChartBase<?> activeCrosshairChart;
    // מונע טעינה כפולה — onCreateView לא יטען, onViewCreated יטען
    private boolean initialLoadDone = false;
    private ArrayAdapter<StockSuggestion> suggestionAdapter;
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;
    private static final long SEARCH_DEBOUNCE_MS = 300;

    public static class StockSuggestion {
        public final String symbol;
        public final String name;
        public final String exchange;

        // שדות חדשים
        public final boolean isSectionHeader;
        public final String sectionTitle;
        public final float dailyChangePercent;

        public StockSuggestion(String symbol, String name, String exchange) {
            this(symbol, name, exchange, false, null, Float.NaN);
        }

        public StockSuggestion(String symbol,
                               String name,
                               String exchange,
                               boolean isSectionHeader,
                               String sectionTitle,
                               float dailyChangePercent) {
            this.symbol            = symbol;
            this.name              = name;
            this.exchange          = exchange;
            this.isSectionHeader   = isSectionHeader;
            this.sectionTitle      = sectionTitle;
            this.dailyChangePercent = dailyChangePercent;
        }

        // יצירת אובייקט section header
        public static StockSuggestion section(String title) {
            return new StockSuggestion(
                    null,
                    null,
                    null,
                    true,
                    title,
                    0f
            );
        }

        @NonNull
        @Override
        public String toString() {
            // משמש רק במקומות ישנים שמשתמשים ב-simple_list_item_1
            if (isSectionHeader) {
                return sectionTitle != null ? sectionTitle : "";
            }

            String s = symbol == null ? "" : symbol;
            String n = name   == null ? "" : name;

            if (n.isEmpty()) return s;
            if (n.length() > 30) n = n.substring(0, 28) + "...";

            return s + "  \u00b7  " + n;
        }
    }





    private String formatPrice(float price, String sym) {
        if (isForexSymbol(sym)) {
            return String.format(Locale.US, "%.4f", price);
        }
        return String.format(Locale.US, "%.2f", price);
    }



    private boolean isCryptoSymbol(String sym) {
        return sym != null && sym.contains(":");
    }

    private boolean isForexSymbol(String sym) {
        return sym != null && (sym.endsWith("=X") || sym.equals("GC=F")
                || sym.equals("SI=F") || sym.equals("BZ=F") || sym.equals("CL=F"));
    }

    private String cleanDisplaySymbol(String sym) {
        if (sym == null) return "";
        if (isCryptoSymbol(sym)) return sym.substring(sym.indexOf(':') + 1);
        if ("^VIX".equals(sym)) return "VIX";
        if ("CL=F".equals(sym)) return "OIL";
        if ("BZ=F".equals(sym)) return "BRENT";
        return sym.replace("=X", "").replace("=F", "");
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_chart, container, false);

        SharedPreferences prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        isDarkTheme = prefs.getBoolean(KEY_THEME, true);
        isChartDark = isDarkTheme;
        showMa150 = prefs.getBoolean("chart_show_ma150", false);
        showVolume = prefs.getBoolean("chart_show_volume", true);
        volumeColorByOpen = prefs.getBoolean("chart_volume_color_open", false);
        currentTFIndex = getTimeframeIndex(
                prefs.getString(KEY_DEFAULT_TIMEFRAME, "1Y"));
        visibleChartRange = TIMEFRAMES[currentTFIndex][3];
        candleMinutes = prefs.getInt("chart_candle_minutes", 0);
        indMa20 = prefs.getBoolean("chart_ind_ma20", false);
        indMa50 = prefs.getBoolean("chart_ind_ma50", false);
        indMa200 = prefs.getBoolean("chart_ind_ma200", false);
        indBoll = prefs.getBoolean("chart_ind_boll", false);
        indPane = prefs.getInt("chart_ind_pane", 0);

        String mode = prefs.getString(KEY_SYMBOL_MODE, "last");
        if ("fixed".equals(mode)) {
            symbol = prefs.getString(MainActivity.KEY_DEFAULT_SYMBOL, "SPY");
            if (symbol.isEmpty()) symbol = "SPY";
        } else {
            symbol = prefs.getString(KEY_LAST_SYMBOL, "SPY");
            if (symbol.isEmpty()) symbol = "SPY";
        }

        if (getArguments() != null && getArguments().containsKey("symbol")) {
            symbol = getArguments().getString("symbol", symbol);
        }

        chartRootLayout    = v.findViewById(R.id.chartRootLayout);
        candleStickChart   = v.findViewById(R.id.stock_chart);
        lineChart          = v.findViewById(R.id.line_chart);
        tickerInput        = null;
        btnLoad            = null;
        btnTimeFrame       = null;
        btnSettings        = v.findViewById(R.id.btnSettings);
        btnChartThemeToggle= v.findViewById(R.id.btnChartThemeToggle);
        btnTickerSelect    = v.findViewById(R.id.btnTickerSelect);
        btnTimeframePicker = v.findViewById(R.id.btnTimeframePicker);
        pullRefreshLayout = v.findViewById(R.id.pullRefresh);
        btnChartMenu       = v.findViewById(R.id.btnChartMenu);
        btnChartAi         = v.findViewById(R.id.btnChartAi);
        tickerLabelTop     = v.findViewById(R.id.tickerLabelTop);
        chartTickerTitle   = v.findViewById(R.id.chartTickerTitle);

        crosshairInfoBar   = v.findViewById(R.id.crosshairInfoBar);
        crosshairPrice     = v.findViewById(R.id.crosshairPrice);
        crosshairDate      = v.findViewById(R.id.crosshairDate);
        freeCrosshairOverlay = v.findViewById(R.id.freeCrosshairOverlay);
        indicatorChart = v.findViewById(R.id.indicator_chart);
        if (crosshairInfoBar != null) {
            crosshairInfoBar.setOnClickListener(x -> showChartAlertDialog(crosshairPriceValue));
        }

        if (btnSettings != null) {
            btnSettings.setOnClickListener(vv -> {
                if (getActivity() instanceof MainActivity)
                    ((MainActivity) getActivity()).openSettings();
            });
        }

        btnExpandChart     = v.findViewById(R.id.btnExpandChart);
        btnExitFullscreen  = v.findViewById(R.id.btnExitFullscreen);
        priceText          = v.findViewById(R.id.priceText);
        changeText         = v.findViewById(R.id.changeText);
        timeFrameText      = v.findViewById(R.id.timeFrameText);
        tickerText         = v.findViewById(R.id.tickerText);
        currentPriceDisplay= v.findViewById(R.id.currentPriceDisplay);
        headerSection      = v.findViewById(R.id.headerSection);
        searchSection      = v.findViewById(R.id.searchSection);
        bottomBar          = v.findViewById(R.id.bottomBar);
        chartContainer     = v.findViewById(R.id.chartContainer);

        if (currentPriceDisplay != null) currentPriceDisplay.setVisibility(View.GONE);

        updateTickerButtonLabel();
        updateTickerLabelTop();
        updateTimeframePickerLabel();
        if (getActivity() != null) getActivity().setTitle("Chart: " + cleanDisplaySymbol(symbol));

        applyTheme();
        setupCandleChartStyle();
        setupLineChartStyle();
        setupClickListeners();
        setupChartGestures();
        setupIndicatorChartStyle();
        v.post(() -> {
            if (candleStickChart == null) return;
            android.view.ViewTreeObserver vto = candleStickChart.getViewTreeObserver();
            if (vto.isAlive() && syncListener == null) {
                syncListener = this::syncIndicatorViewport;
                vto.addOnDrawListener(syncListener);
            }
        });

        interval = TIMEFRAMES[currentTFIndex][1];

        updateChartThemeToggleLabel();
        return v;
    }

    private void loadChartForSymbol(String newSymbol) {
        if (newSymbol == null || newSymbol.trim().isEmpty()) return;

        String cleanSymbol = newSymbol.trim().toUpperCase(Locale.US);

        String cryptoMapped = CRYPTO_MAP.get(cleanSymbol);
        if (cryptoMapped != null) {
            cleanSymbol = cryptoMapped;
        } else {
            String forexMapped = FOREX_MAP.get(cleanSymbol);
            if (forexMapped != null) {
                cleanSymbol = forexMapped;
            } else if ("SPX".equals(cleanSymbol) || "^SPX".equals(cleanSymbol)
                    || "GSPC".equals(cleanSymbol)) {
                cleanSymbol = "^GSPC";
            }
        }

        symbol = cleanSymbol;

        if (getActivity() != null) {
            SharedPreferences prefs = getActivity()
                    .getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
            String mode = prefs.getString(KEY_SYMBOL_MODE, "last");
            // שמור "last" רק אם לא במצב fixed
            if (!"fixed".equals(mode)) {
                prefs.edit()
                        .putString(MainActivity.KEY_LAST_SYMBOL, cleanSymbol)
                        .apply();
            }
        }

        updateTickerButtonLabel();
        updateTickerLabelTop();
        hideCrosshairInfo();
        updateTimeframePickerLabel();

        if (getActivity() != null) {
            getActivity().setTitle("Chart: " + cleanDisplaySymbol(cleanSymbol));
        }

        fetchStockData(symbol, interval);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        SharedViewModel vm = new ViewModelProvider(requireActivity())
                .get(SharedViewModel.class);

        SharedPreferences prefs = requireActivity()
                .getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);

        String mode = prefs.getString(KEY_SYMBOL_MODE, "last");

        // קבע מה לטעון בפעם הראשונה
        String symbolToLoad;

        if ("fixed".equals(mode)) {
            String sessionSym = vm.getSessionSymbol();
            String fixedSymbol = prefs.getString(MainActivity.KEY_DEFAULT_SYMBOL, "SPY");
            if (fixedSymbol == null || fixedSymbol.trim().isEmpty()) fixedSymbol = "SPY";

            // אם יש session symbol (הגיע מ-Watchlist) — תשתמש בו
            // אחרת — טען fixed
            symbolToLoad = (sessionSym != null && !sessionSym.trim().isEmpty())
                    ? sessionSym.trim()
                    : fixedSymbol.trim();
        } else {
            String lastSymbol = prefs.getString(MainActivity.KEY_LAST_SYMBOL, "SPY");
            if (lastSymbol == null || lastSymbol.trim().isEmpty()) lastSymbol = "SPY";
            symbolToLoad = lastSymbol.trim();
        }

        // עדכן UI וטען גרף ישירות — בלי VM בכלל בשלב הזה
        loadChartForSymbol(symbolToLoad);

        // observe לשינויים עתידיים בלבד (מ-Watchlist לאחר הטעינה הראשונית)
        vm.getSelectedSymbol().observe(getViewLifecycleOwner(), selected -> {
            if (selected != null && !selected.trim().isEmpty()
                    && !selected.trim().equals(symbol)) {
                loadChartForSymbol(selected.trim());
            }
        });
    }

    public void applyThemeExternal(boolean dark) {
        isDarkTheme = dark;
        isChartDark = dark;
        applyTheme();
        applyChartColors();
        updateChartThemeToggleLabel();
    }
    private void updateTickerLabelTop() {
        String displaySymbol = cleanDisplaySymbol(symbol);
        if (tickerLabelTop != null) tickerLabelTop.setText(displaySymbol);
        if (chartTickerTitle != null) chartTickerTitle.setText(displaySymbol);
    }

    private void updateTimeframePickerLabel() {
        if (btnTimeframePicker == null) return;
        btnTimeframePicker.setText(TIMEFRAMES[currentTFIndex][0]);
    }

    public static String[] getTimeframeLabels() {
        String[] labels = new String[TIMEFRAMES.length];
        for (int i = 0; i < TIMEFRAMES.length; i++) labels[i] = TIMEFRAMES[i][0];
        return labels;
    }

    private static int getTimeframeIndex(String label) {
        for (int i = 0; i < TIMEFRAMES.length; i++) {
            if (TIMEFRAMES[i][0].equalsIgnoreCase(label)) return i;
        }
        return 5;
    }

    private void selectTimeframe(int timeframeIndex) {
        if (timeframeIndex < 0 || timeframeIndex >= TIMEFRAMES.length) return;
        currentTFIndex = timeframeIndex;
        interval = TIMEFRAMES[timeframeIndex][1];
        visibleChartRange = TIMEFRAMES[timeframeIndex][3];
        updateTimeframePickerLabel();
        hideCrosshairInfo();
        fetchStockData(symbol, interval);
    }

    private void showTimeframeDialog() {
        if (getContext() == null) return;

        final AlertDialog[] dialogHolder = new AlertDialog[1];

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_dialog_glass);
        int ph = dpToPx(20);
        root.setPadding(ph, dpToPx(20), ph, dpToPx(16));

        TextView title = new TextView(getContext());
        title.setText("Select Timeframe");
        title.setTextSize(16f);
        title.setTextColor(requireContext().getColor(R.color.text_primary));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dpToPx(4), 0, 0, dpToPx(16));
        root.addView(title);

        int cols = 3;
        int tfCounter = 0;

        for (int row = 0; row < Math.ceil((double) TIMEFRAMES.length / cols); row++) {
            LinearLayout rowLayout = new LinearLayout(getContext());
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, 0, 0, dpToPx(8));
            rowLayout.setLayoutParams(rowLp);

            for (int col = 0; col < cols; col++) {
                final int idx = tfCounter;
                tfCounter++;

                if (idx >= TIMEFRAMES.length) {
                    android.widget.Space spacer = new android.widget.Space(getContext());
                    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    sp.setMargins(col == 0 ? 0 : dpToPx(6), 0, 0, 0);
                    rowLayout.addView(spacer, sp);
                    continue;
                }

                boolean isSelected = (idx == currentTFIndex);
                String label = TIMEFRAMES[idx][0];

                android.widget.FrameLayout btnWrapper = new android.widget.FrameLayout(getContext());

                android.graphics.drawable.GradientDrawable btnBg =
                        new android.graphics.drawable.GradientDrawable();
                btnBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                btnBg.setCornerRadius(dpToPx(10));

                if (isSelected) {
                    btnBg.setColor(requireContext().getColor(R.color.primary));
                    btnBg.setStroke(0, android.graphics.Color.TRANSPARENT);
                } else {
                    btnBg.setColor(requireContext().getColor(R.color.bg_secondary));
                    btnBg.setStroke(dpToPx(1),
                            requireContext().getColor(R.color.colorBorder));
                }
                btnWrapper.setBackground(btnBg);

                TextView btnText = new TextView(getContext());
                btnText.setText(label);
                btnText.setTextSize(14f);
                btnText.setTextColor(isSelected
                        ? requireContext().getColor(R.color.white)
                        : requireContext().getColor(R.color.text_secondary));
                btnText.setTypeface(null, isSelected ?
                        android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                btnText.setGravity(android.view.Gravity.CENTER);
                android.widget.FrameLayout.LayoutParams textLp =
                        new android.widget.FrameLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(44));
                btnText.setLayoutParams(textLp);
                btnWrapper.addView(btnText);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(col == 0 ? 0 : dpToPx(6), 0, 0, 0);
                rowLayout.addView(btnWrapper, lp);

                btnWrapper.setOnClickListener(v -> {
                    selectTimeframe(idx);
                    if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                });
            }
            root.addView(rowLayout);
        }

        View divider = new View(getContext());
        divider.setBackgroundColor(requireContext().getColor(R.color.colorBorder));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1));
        divLp.setMargins(0, dpToPx(8), 0, dpToPx(12));
        root.addView(divider, divLp);

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setView(root)
                .setNegativeButton("Cancel", null)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }

        dialogHolder[0] = dialog;
        dialog.show();
    }

    @Override
    public void onTimeFrameSelected(String tf) {
        interval = tf;
        hideCrosshairInfo();
        fetchStockData(symbol, interval);
    }

    private void setActiveTFButton(com.google.android.material.button.MaterialButton selected) {
        activeTFButton = selected;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() == null) return;
        SharedPreferences prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean savedDark = prefs.getBoolean(KEY_THEME, true);
        if (savedDark != isChartDark) {
            isChartDark = savedDark;
            isDarkTheme = savedDark;
            applyTheme();
            applyChartColors();
            if (!currentEntries.isEmpty()) {
                if (isCandleStick) updateCandleChart(currentEntries);
                else               updateLineChart(currentEntries);
            }
            updateChartThemeToggleLabel();
        }
        boolean showDailyChange = prefs.getBoolean(KEY_CHANGE_PERCENT_DAILY, false);
        if (hasDisplayedChangeMode && showDailyChange != lastDisplayedDailyChange) {
            fetchStockData(symbol, interval);
        }
    }

    private void showCrosshairInfo(float price, String date) {
        if (crosshairInfoBar == null) return;
        isCrosshairActive = true;

        crosshairPriceValue = price;
        if (crosshairPrice != null) {
            crosshairPrice.setText("$" + formatPrice(price, symbol) + "  \uD83D\uDD14");
        }

        if (crosshairDate != null) {
            crosshairDate.setText(date);
        }

        crosshairInfoBar.setVisibility(View.VISIBLE);
    }

    private void hideCrosshairInfo() {
        if (crosshairInfoBar == null) return;
        isCrosshairActive = false;
        crosshairInfoBar.setVisibility(View.GONE);
        if (freeCrosshairOverlay != null) freeCrosshairOverlay.hide();
        if (candleStickChart != null) candleStickChart.highlightValue(null);
        if (lineChart        != null) lineChart.highlightValue(null);
    }

    private void setupChartGestures() {
        OnChartGestureListener gestureListener = new OnChartGestureListener() {
            @Override public void onChartGestureStart(MotionEvent me, ChartTouchListener.ChartGesture lastPerformedGesture) {}
            @Override public void onChartGestureEnd(MotionEvent me, ChartTouchListener.ChartGesture lastPerformedGesture) {}
            @Override public void onChartLongPressed(MotionEvent me) {
                if (candleStickChart != null && candleStickChart.getVisibility() == View.VISIBLE) {
                    startCrosshair(candleStickChart, me.getX(), me.getY());
                } else if (lineChart != null && lineChart.getVisibility() == View.VISIBLE) {
                    startCrosshair(lineChart, me.getX(), me.getY());
                }
            }
            @Override public void onChartDoubleTapped(MotionEvent me) {
                if (isFullscreen) exitFullscreen(); else enterFullscreen();
            }
            @Override public void onChartSingleTapped(MotionEvent me) {
                if (drawMode != 0) { handleDrawTap(me); return; }
                if (isCrosshairActive) hideCrosshairInfo();
            }
            @Override public void onChartFling(MotionEvent me1, MotionEvent me2, float velocityX, float velocityY) {}
            @Override public void onChartScale(MotionEvent me, float scaleX, float scaleY) {}
            @Override public void onChartTranslate(MotionEvent me, float dX, float dY) {}
        };
        if (candleStickChart != null) {
            candleStickChart.setOnChartGestureListener(gestureListener);
            attachCrosshairTouchListener(candleStickChart);
        }
        if (lineChart != null) {
            lineChart.setOnChartGestureListener(gestureListener);
            attachCrosshairTouchListener(lineChart);
        }
    }

    private void startCrosshair(com.github.mikephil.charting.charts.BarLineChartBase<?> chart,
                                float touchX, float touchY) {
        if (magnetMode) {
            if (freeCrosshairOverlay != null) freeCrosshairOverlay.hide();
            com.github.mikephil.charting.highlight.Highlight highlight =
                    chart.getHighlightByTouchPoint(touchX, touchY);
            if (highlight != null) {
                isCrosshairActive = true;
                chart.highlightValue(highlight);
            }
            return;
        }

        activeCrosshairChart = chart;
        crosshairDragStartTouchX = touchX;
        crosshairDragStartTouchY = touchY;
        crosshairDragStartX = touchX;
        crosshairDragStartY = touchY;
        crosshairGestureMoved = false;
        crosshairDismissGesture = false;
        chart.setDragEnabled(false);
        chart.setHighlightPerDragEnabled(false);
        updateFreeCrosshair(chart, touchX, touchY);
    }

    private void attachCrosshairTouchListener(
            com.github.mikephil.charting.charts.BarLineChartBase<?> chart) {
        chart.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                crosshairDismissGesture = isCrosshairActive && !magnetMode;
            }
            if (action == MotionEvent.ACTION_DOWN && isCrosshairActive && !magnetMode) {
                activeCrosshairChart = chart;
                crosshairDragStartTouchX = event.getX();
                crosshairDragStartTouchY = event.getY();
                crosshairDragStartX = freeCrosshairX;
                crosshairDragStartY = freeCrosshairY;
                crosshairGestureMoved = false;
                chart.setDragEnabled(false);
                chart.setHighlightPerDragEnabled(false);
                return true;
            }

            if (activeCrosshairChart != chart) return false;

            if (action == MotionEvent.ACTION_MOVE) {
                float dx = event.getX() - crosshairDragStartTouchX;
                float dy = event.getY() - crosshairDragStartTouchY;
                if (Math.hypot(dx, dy) > dpToPx(8)) crosshairGestureMoved = true;
                updateFreeCrosshair(
                        chart,
                        crosshairDragStartX + dx,
                        crosshairDragStartY + dy);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                boolean dismissCrosshair = crosshairDismissGesture
                        && action == MotionEvent.ACTION_UP && !crosshairGestureMoved;
                chart.setDragEnabled(true);
                chart.setHighlightPerDragEnabled(magnetMode);
                activeCrosshairChart = null;
                crosshairDismissGesture = false;
                if (dismissCrosshair) hideCrosshairInfo();
            }
            // Consume the active free-crosshair gesture so the chart cannot snap it to a data point.
            return true;
        });
    }

    private void updateFreeCrosshair(
            com.github.mikephil.charting.charts.BarLineChartBase<?> chart,
            float touchX, float touchY) {
        if (chart.getData() == null || chart.getData().getDataSetCount() == 0 || dateLabels.isEmpty()) return;

        android.graphics.RectF content = chart.getViewPortHandler().getContentRect();
        float x = Math.max(content.left, Math.min(touchX, content.right));
        float y = Math.max(content.top, Math.min(touchY, content.bottom));
        freeCrosshairX = x;
        freeCrosshairY = y;
        com.github.mikephil.charting.utils.MPPointD values =
                chart.getTransformer(com.github.mikephil.charting.components.YAxis.AxisDependency.LEFT)
                        .getValuesByTouchPoint(x, y);

        float dataX = Math.max(0f, Math.min((float) values.x, dateLabels.size() - 1f));
        float price = (float) values.y;
        com.github.mikephil.charting.utils.MPPointD.recycleInstance(values);

        chart.highlightValue(null, false);
        if (freeCrosshairOverlay != null) {
            freeCrosshairOverlay.showAt(x, y, content);
        }
        isCrosshairActive = true;
        int dateIndex = Math.max(0, Math.min(Math.round(dataX), dateLabels.size() - 1));
        showCrosshairInfo(price, dateLabels.get(dateIndex));
    }

    private void updateTickerButtonLabel() {
        if (btnTickerSelect == null) return;
        btnTickerSelect.setText(cleanDisplaySymbol(symbol));
    }

    private void showTickerInputDialog() {
        TickerSearchSheet sheet = new TickerSearchSheet();
        sheet.setOnTickerSelectedListener(sym -> openChartFromInput(sym));
        sheet.show(getParentFragmentManager(), "ticker_search");
    }

    private void enterFullscreen() {
        if (isFullscreen || chartContainer == null) return;
        isFullscreen = true;
        chartOriginalParams = chartContainer.getLayoutParams();
        if (headerSection   != null) headerSection.setVisibility(View.GONE);
        if (searchSection   != null) searchSection.setVisibility(View.GONE);
        if (bottomBar       != null) bottomBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(0, 0, 0, 0);
        chartContainer.setLayoutParams(lp);
        if (btnExitFullscreen != null) btnExitFullscreen.setVisibility(View.VISIBLE);
    }

    private void exitFullscreen() {
        if (!isFullscreen || chartContainer == null) return;
        isFullscreen = false;
        if (chartOriginalParams != null) {
            chartContainer.setLayoutParams(chartOriginalParams);
        } else {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0);
            lp.weight = 1;
            lp.setMargins(dpToPx(12), dpToPx(8), dpToPx(12), 0);
            chartContainer.setLayoutParams(lp);
        }
        if (headerSection   != null) headerSection.setVisibility(View.VISIBLE);
        if (btnExitFullscreen != null) btnExitFullscreen.setVisibility(View.GONE);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void applyTheme() {
        int bgColor   = isDarkTheme ? DARK_BG       : LIGHT_BG;
        int cardColor = isDarkTheme ? DARK_CARD     : LIGHT_CARD;
        int textPri   = isDarkTheme ? DARK_TEXT_PRI : LIGHT_TEXT_PRI;
        int textSec   = isDarkTheme ? DARK_TEXT_SEC : LIGHT_TEXT_SEC;

        if (chartRootLayout != null) chartRootLayout.setBackgroundColor(bgColor);
        if (headerSection instanceof com.google.android.material.card.MaterialCardView) {
            ((com.google.android.material.card.MaterialCardView) headerSection)
                    .setCardBackgroundColor(cardColor);
        }
        if (bottomBar       != null) bottomBar.setBackgroundColor(cardColor);

        if (tickerLabelTop  != null) tickerLabelTop.setTextColor(textPri);
        if (chartTickerTitle != null) chartTickerTitle.setTextColor(textPri);
        if (tickerText      != null) tickerText.setTextColor(textPri);
        if (timeFrameText   != null) timeFrameText.setTextColor(textSec);
        applyChartColors();
    }

    private void applyChartColors() {
        int chartCard = isChartDark ? DARK_CARD     : LIGHT_CARD;
        int chartSec  = isChartDark ? DARK_TEXT_SEC : LIGHT_TEXT_SEC;
        if (chartContainer instanceof com.google.android.material.card.MaterialCardView) {
            ((com.google.android.material.card.MaterialCardView) chartContainer)
                    .setCardBackgroundColor(chartCard);
        }
        if (candleStickChart != null) {
            candleStickChart.setBackgroundColor(chartCard);
            candleStickChart.getXAxis().setTextColor(chartSec);
            candleStickChart.getAxisLeft().setTextColor(chartSec);
            candleStickChart.invalidate();
        }
        if (lineChart != null) {
            lineChart.setBackgroundColor(chartCard);
            lineChart.getXAxis().setTextColor(chartSec);
            lineChart.getAxisLeft().setTextColor(chartSec);
            lineChart.invalidate();
        }
    }

    private void updateChartThemeToggleLabel() {
        if (btnChartThemeToggle != null)
            btnChartThemeToggle.setText(isChartDark ? "\u2600\uFE0F" : "\uD83C\uDF19");
    }

    private void setupCandleChartStyle() {
        if (candleStickChart == null) return;
        int cardColor = isChartDark ? DARK_CARD : LIGHT_CARD;
        int textSec   = isChartDark ? DARK_TEXT_SEC : LIGHT_TEXT_SEC;
        candleStickChart.setBackgroundColor(cardColor);
        candleStickChart.setDrawGridBackground(false);
        candleStickChart.getDescription().setEnabled(false);
        candleStickChart.getLegend().setEnabled(false);
        candleStickChart.setTouchEnabled(true);
        candleStickChart.setDragEnabled(true);
        candleStickChart.setScaleEnabled(true);
        candleStickChart.setScaleXEnabled(true);
        candleStickChart.setScaleYEnabled(true);
        candleStickChart.setPinchZoom(false);
        candleStickChart.setDoubleTapToZoomEnabled(false);
        candleStickChart.setDragDecelerationEnabled(true);
        candleStickChart.setDragDecelerationFrictionCoef(0.92f);
        candleStickChart.setHighlightPerTapEnabled(false);
        candleStickChart.setHighlightPerDragEnabled(magnetMode);
        candleStickChart.setExtraTopOffset(4f);
        XAxis xAxis = candleStickChart.getXAxis();
        xAxis.setDrawGridLines(false);
        xAxis.setDrawAxisLine(false);
        xAxis.setTextColor(textSec);
        xAxis.setTextSize(10f);
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setSpaceMin(0.5f);
        xAxis.setSpaceMax(0.5f);
        xAxis.setLabelCount(5, true);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                int i = (int) value;
                return (i >= 0 && i < dateLabels.size()) ? dateLabels.get(i) : "";
            }
        });
        YAxis leftAxis = candleStickChart.getAxisLeft();
        leftAxis.setDrawGridLines(false);
        leftAxis.setDrawAxisLine(false);
        leftAxis.setTextColor(textSec);
        leftAxis.setTextSize(10f);
        leftAxis.setLabelCount(5, false);
        candleStickChart.getAxisRight().setEnabled(false);
    }

    private void setupLineChartStyle() {
        if (lineChart == null) return;
        int cardColor = isChartDark ? DARK_CARD : LIGHT_CARD;
        int textSec   = isChartDark ? DARK_TEXT_SEC : LIGHT_TEXT_SEC;
        lineChart.setBackgroundColor(cardColor);
        lineChart.setDrawGridBackground(false);
        lineChart.getDescription().setEnabled(false);
        lineChart.getLegend().setEnabled(false);
        lineChart.setTouchEnabled(true);
        lineChart.setDragEnabled(true);
        lineChart.setScaleEnabled(true);
        lineChart.setScaleXEnabled(true);
        lineChart.setScaleYEnabled(true);
        lineChart.setPinchZoom(false);
        lineChart.setDoubleTapToZoomEnabled(false);
        lineChart.setDragDecelerationEnabled(true);
        lineChart.setDragDecelerationFrictionCoef(0.92f);
        lineChart.setHighlightPerTapEnabled(false);
        lineChart.setHighlightPerDragEnabled(magnetMode);
        lineChart.setExtraTopOffset(4f);
        XAxis xAxis = lineChart.getXAxis();
        xAxis.setDrawGridLines(false);
        xAxis.setDrawAxisLine(false);
        xAxis.setTextColor(textSec);
        xAxis.setTextSize(10f);
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setSpaceMin(0.5f);
        xAxis.setSpaceMax(0.5f);
        xAxis.setLabelCount(5, true);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                int i = (int) value;
                return (i >= 0 && i < dateLabels.size()) ? dateLabels.get(i) : "";
            }
        });
        YAxis leftAxis = lineChart.getAxisLeft();
        leftAxis.setDrawGridLines(false);
        leftAxis.setDrawAxisLine(false);
        leftAxis.setTextColor(textSec);
        leftAxis.setTextSize(10f);
        leftAxis.setLabelCount(5, false);
        lineChart.getAxisRight().setEnabled(false);
    }

    private void setupClickListeners() {
        if (btnTickerSelect != null) {
            btnTickerSelect.setOnClickListener(v -> showTickerInputDialog());
        }

        if (btnTimeframePicker != null) {
            btnTimeframePicker.setOnClickListener(v -> showTimeframeDialog());
        }

        PullRefreshLayout pullRefresh = pullRefreshLayout;
        if (pullRefresh != null) {
            // The charts handle their own drags (crosshair, pan), so a gesture that starts on a
            // chart never refreshes the page.
            pullRefresh.addBlockedView(candleStickChart);
            pullRefresh.addBlockedView(lineChart);
            pullRefresh.setRefreshAction(() -> fetchStockData(symbol, interval));
        }

        if (btnChartAi != null) {
            btnChartAi.setOnClickListener(x -> AiChatDialog.showForChart(
                    requireContext(), cleanDisplaySymbol(symbol), buildChartAiContext()));
        }
        if (btnChartMenu != null) {
            btnChartMenu.setOnClickListener(this::showChartMenu);
        }

        if (btnMagnetMode != null) {
            updateMagnetModeLabel();
            btnMagnetMode.setOnClickListener(v -> {
                magnetMode = !magnetMode;
                if (candleStickChart != null) candleStickChart.setHighlightPerDragEnabled(magnetMode);
                if (lineChart != null) lineChart.setHighlightPerDragEnabled(magnetMode);
                if (!magnetMode) hideCrosshairInfo();
                updateMagnetModeLabel();
            });
        }

        if (btnChartThemeToggle != null) {
            btnChartThemeToggle.setOnClickListener(v -> {
                isChartDark = !isChartDark;
                isDarkTheme = isChartDark;
                SharedPreferences prefs = requireActivity()
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putBoolean(KEY_THEME, isChartDark).apply();
                AppCompatDelegate.setDefaultNightMode(
                        isChartDark ? AppCompatDelegate.MODE_NIGHT_YES
                                    : AppCompatDelegate.MODE_NIGHT_NO);
                applyTheme();
                applyChartColors();
                if (!currentEntries.isEmpty()) {
                    if (isCandleStick) updateCandleChart(currentEntries);
                    else               updateLineChart(currentEntries);
                }
                updateChartThemeToggleLabel();
            });
        }

        if (btnExitFullscreen != null) btnExitFullscreen.setOnClickListener(v -> exitFullscreen());
    }

    private void showChartMenu(View anchor) {
        Context context = requireContext();
        int surface = isChartDark ? DARK_CARD : LIGHT_CARD;
        int primaryText = isChartDark ? DARK_TEXT_PRI : LIGHT_TEXT_PRI;
        int secondaryText = isChartDark ? DARK_TEXT_SEC : LIGHT_TEXT_SEC;

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dpToPx(18);
        content.setPadding(padding, padding, padding, padding);
        android.graphics.drawable.GradientDrawable background =
                new android.graphics.drawable.GradientDrawable();
        background.setColor(surface);
        background.setCornerRadius(dpToPx(20));
        background.setStroke(dpToPx(1), context.getColor(R.color.colorBorder));
        content.setBackground(background);

        TextView title = new TextView(context);
        title.setText("Chart options");
        title.setTextColor(primaryText);
        title.setTextSize(18f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title);

        TextView subtitle = new TextView(context);
        subtitle.setText("Choose an action");
        subtitle.setTextColor(secondaryText);
        subtitle.setTextSize(13f);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.bottomMargin = dpToPx(14);
        content.addView(subtitle, subtitleParams);

        TextView timeframeTitle = new TextView(context);
        timeframeTitle.setText("TIMEFRAME");
        timeframeTitle.setTextColor(secondaryText);
        timeframeTitle.setTextSize(11f);
        timeframeTitle.setLetterSpacing(0.08f);
        timeframeTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams timeframeTitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeframeTitleParams.bottomMargin = dpToPx(8);
        content.addView(timeframeTitle, timeframeTitleParams);

        LinearLayout timeframeRow = new LinearLayout(context);
        timeframeRow.setOrientation(LinearLayout.VERTICAL);
        timeframeRow.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams timeframeRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeframeRowParams.bottomMargin = dpToPx(14);
        content.addView(timeframeRow, timeframeRowParams);

        String[] timeframeLabels = getTimeframeLabels();
        int[] timeframeIndices = new int[timeframeLabels.length];
        for (int i = 0; i < timeframeIndices.length; i++) timeframeIndices[i] = i;
        AlertDialog[] dialogHolder = new AlertDialog[1];
        com.google.android.material.button.MaterialButton[] timeframeButtons =
                new com.google.android.material.button.MaterialButton[timeframeLabels.length];
        LinearLayout timeframeGridRow = null;
        for (int i = 0; i < timeframeLabels.length; i++) {
            if (i % 3 == 0) {
                timeframeGridRow = new LinearLayout(context);
                timeframeGridRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rowParams.topMargin = dpToPx(6);
                timeframeRow.addView(timeframeGridRow, rowParams);
            }
            final int timeframeIndex = timeframeIndices[i];
            com.google.android.material.button.MaterialButton timeframeButton =
                    new com.google.android.material.button.MaterialButton(context);
            timeframeButton.setText(timeframeLabels[i]);
            timeframeButton.setTextSize(12f);
            timeframeButton.setMinWidth(0);
            timeframeButton.setMinHeight(0);
            timeframeButton.setInsetTop(0);
            timeframeButton.setInsetBottom(0);
            timeframeButton.setPadding(dpToPx(8), 0, dpToPx(8), 0);
            timeframeButton.setCornerRadius(dpToPx(16));
            timeframeButton.setStrokeWidth(dpToPx(1));
            timeframeButton.setStrokeColor(android.content.res.ColorStateList.valueOf(
                    context.getColor(R.color.chart_gold)));
            timeframeButtons[i] = timeframeButton;
            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                    0, dpToPx(38), 1f);
            if (i % 3 > 0) buttonParams.leftMargin = dpToPx(5);
            timeframeGridRow.addView(timeframeButton, buttonParams);
            timeframeButton.setOnClickListener(view -> {
                selectTimeframe(timeframeIndex);
                updateChartMenuTimeframeButtons(timeframeButtons, timeframeIndices);
                if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            });
        }
        updateChartMenuTimeframeButtons(timeframeButtons, timeframeIndices);

        TextView displayTitle = new TextView(context);
        displayTitle.setText("DISPLAY");
        displayTitle.setTextColor(secondaryText);
        displayTitle.setTextSize(11f);
        displayTitle.setLetterSpacing(0.08f);
        displayTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams displayTitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        displayTitleParams.bottomMargin = dpToPx(8);
        content.addView(displayTitle, displayTitleParams);

        List<Object[]> tiles = new ArrayList<>();
        tiles.add(new Object[]{isCandleStick ? "\u25A4" : "\u2301",
                isCandleStick ? "Line chart" : "Candlesticks",
                isCandleStick ? "Switch to line" : "Switch to candles", false,
                (Runnable) () -> toggleChartType()});
        tiles.add(new Object[]{"\u25F7", "Visible range",
                visibleChartRange.toUpperCase(Locale.US), false,
                (Runnable) this::showVisibleRangeDialog});
        tiles.add(new Object[]{"\u25A5", "Candle size", candleLabelShort(),
                candleMinutes != 0, (Runnable) this::showCandleSizeDialog});
        tiles.add(new Object[]{"\u2248", "Indicators", indicatorSummary(),
                !"None".equals(indicatorSummary()), (Runnable) this::showIndicatorsDialog});
        tiles.add(new Object[]{"\u270E", "Drawing tools", drawingsSummary(), drawMode != 0,
                (Runnable) this::showDrawingDialog});
        tiles.add(new Object[]{"\uD83D\uDD14", "Price alert",
                isCrosshairActive ? "At crosshair" : "Set alert", false,
                (Runnable) () -> showChartAlertDialog(
                        isCrosshairActive ? crosshairPriceValue : lastPrice)});
        tiles.add(new Object[]{"150", "150-day MA", showMa150 ? "On" : "Off", showMa150,
                (Runnable) this::toggleMa150});
        tiles.add(new Object[]{"\u25AE", "Volume", showVolume ? "On" : "Off", showVolume,
                (Runnable) this::toggleVolume});
        if (showVolume) {
            tiles.add(new Object[]{"\u25D0", "Volume colors",
                    volumeColorByOpen ? "By candle" : "By daily change", false,
                    (Runnable) this::toggleVolumeColorMode});
        }
        for (int i = 0; i < tiles.size(); i += 2) {
            LinearLayout tileRow = new LinearLayout(context);
            tileRow.setOrientation(LinearLayout.HORIZONTAL);
            addChartMenuTile(tileRow, tiles.get(i), primaryText, secondaryText, dialogHolder, false);
            if (i + 1 < tiles.size()) {
                addChartMenuTile(tileRow, tiles.get(i + 1), primaryText, secondaryText,
                        dialogHolder, true);
            } else {
                View filler = new View(context);
                LinearLayout.LayoutParams fillerParams = new LinearLayout.LayoutParams(0, 1, 1f);
                fillerParams.leftMargin = dpToPx(8);
                tileRow.addView(filler, fillerParams);
            }
            LinearLayout.LayoutParams tileRowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tileRowParams.bottomMargin = dpToPx(6);
            content.addView(tileRow, tileRowParams);
        }

        dialogHolder[0] = new AlertDialog.Builder(context)
                .setView(content)
                .setNegativeButton("Close", null)
                .create();
        dialogHolder[0].setOnShowListener(ignored -> {
            if (dialogHolder[0].getWindow() != null) {
                dialogHolder[0].getWindow().setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            }
        });
        dialogHolder[0].show();
    }

    private void updateChartMenuTimeframeButtons(
            com.google.android.material.button.MaterialButton[] buttons, int[] timeframeIndices) {
        if (getContext() == null) return;
        for (int i = 0; i < buttons.length; i++) {
            boolean selected = currentTFIndex == timeframeIndices[i];
            buttons[i].setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(selected
                            ? R.color.chart_tf_selected : R.color.chart_tf_unselected)));
            buttons[i].setTextColor(requireContext().getColor(selected
                    ? R.color.primary : R.color.chart_tf_text));
        }
    }

    private void toggleVolumeColorMode() {
        volumeColorByOpen = !volumeColorByOpen;
        requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean("chart_volume_color_open", volumeColorByOpen).apply();
        refreshCandlesSafely();
    }

    private void toggleVolume() {
        showVolume = !showVolume;
        requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean("chart_show_volume", showVolume).apply();
        refreshCandlesSafely();
    }

    private void refreshCandlesSafely() {
        if (currentEntries.isEmpty() || !isCandleStick) return;
        try {
            updateCandleChart(currentEntries);
        } catch (Exception e) {
            Log.e("ChartFragment", "Volume refresh failed", e);
            if (getContext() != null) {
                Toast.makeText(getContext(), "Volume error: " + e, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void toggleMa150() {
        showMa150 = !showMa150;
        requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean("chart_show_ma150", showMa150).apply();
        movingAverageEntries.clear();
        if (!currentEntries.isEmpty()) {
            if (showMa150) {
                fetchMovingAverage(symbol, currentEntries, currentTimestamps,
                        chartRequestGeneration);
            } else if (isCandleStick) {
                updateCandleChart(currentEntries);
            } else {
                updateLineChart(currentEntries);
            }
        }
    }

    private String buildChartAiContext() {
        StringBuilder context = new StringBuilder()
                .append("סימול: ").append(symbol)
                .append("\nטווח גרף: ").append(visibleChartRange)
                .append("\nמחיר אחרון: ").append(lastPrice);
        if (fullCloses.size() >= 2) {
            float first = fullCloses.get(0);
            float high = first;
            float low = first;
            for (float c : fullCloses) {
                if (c > high) high = c;
                if (c < low) low = c;
            }
            float last = fullCloses.get(fullCloses.size() - 1);
            context.append("\nשינוי לאורך כל הטווח: ")
                    .append(String.format(java.util.Locale.US, "%.2f%%",
                            first != 0f ? (last - first) / first * 100f : 0f))
                    .append("\nשיא בטווח: ").append(high)
                    .append("\nשפל בטווח: ").append(low);
        }
        context.append("\nמחירי סגירה אחרונים (מהישן לחדש): ");
        int start = Math.max(0, fullCloses.size() - 10);
        for (int i = start; i < fullCloses.size(); i++) {
            if (i > start) context.append(", ");
            context.append(fullCloses.get(i));
        }
        return context.toString();
    }

    // ---------------------------------------------------------------------------------
    // Indicators, drawing tools and chart price alerts
    // ---------------------------------------------------------------------------------

    private static final class Drawing {
        int type;           // 1 = horizontal line, 2 = trend line, 3 = Fibonacci
        long t1, t2;
        float p1, p2;
    }

    private static final String DRAWINGS_PREFS = "chart_drawings";

    private List<Drawing> loadDrawings() {
        List<Drawing> out = new ArrayList<>();
        if (getContext() == null) return out;
        String raw = requireContext().getSharedPreferences(DRAWINGS_PREFS, Context.MODE_PRIVATE)
                .getString(symbol, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Drawing d = new Drawing();
                d.type = o.getInt("type");
                d.t1 = o.getLong("t1");
                d.t2 = o.getLong("t2");
                d.p1 = (float) o.getDouble("p1");
                d.p2 = (float) o.getDouble("p2");
                out.add(d);
            }
        } catch (Exception e) {
            Log.w("ChartFragment", "Bad drawings data", e);
        }
        return out;
    }

    private void saveDrawings(List<Drawing> list) {
        if (getContext() == null) return;
        JSONArray arr = new JSONArray();
        try {
            for (Drawing d : list) {
                JSONObject o = new JSONObject();
                o.put("type", d.type);
                o.put("t1", d.t1);
                o.put("t2", d.t2);
                o.put("p1", d.p1);
                o.put("p2", d.p2);
                arr.put(o);
            }
        } catch (Exception e) {
            return;
        }
        requireContext().getSharedPreferences(DRAWINGS_PREFS, Context.MODE_PRIVATE)
                .edit().putString(symbol, arr.toString()).apply();
    }

    private int timeToIndex(long t) {
        int n = currentTimestamps.size();
        if (n == 0) return -1;
        long first = currentTimestamps.get(0);
        long last = currentTimestamps.get(n - 1);
        long span = n > 1 ? Math.max(1, (last - first) / (n - 1)) : 86_400_000L;
        if (t < first - span || t > last + span) return -1;
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (currentTimestamps.get(mid) < t) lo = mid + 1; else hi = mid;
        }
        if (lo > 0 && Math.abs(currentTimestamps.get(lo - 1) - t) < Math.abs(currentTimestamps.get(lo) - t)) {
            lo--;
        }
        return lo;
    }

    private float[] closesOf(List<CandleEntry> entries) {
        float[] c = new float[entries.size()];
        for (int i = 0; i < c.length; i++) c[i] = entries.get(i).getClose();
        return c;
    }

    private LineDataSet lineSet(float[] v, String label, int color) {
        List<Entry> pts = new ArrayList<>();
        for (int i = 0; i < v.length; i++) {
            if (!Float.isNaN(v[i])) pts.add(new Entry(i, v[i]));
        }
        if (pts.isEmpty()) return null;
        LineDataSet ds = new LineDataSet(pts, label);
        ds.setColor(color);
        ds.setLineWidth(1.4f);
        ds.setDrawCircles(false);
        ds.setDrawValues(false);
        ds.setHighlightEnabled(false);
        return ds;
    }

    /** Indicator overlays (moving averages, Bollinger) and trend lines as extra line series. */
    private List<LineDataSet> buildOverlaySets(List<CandleEntry> entries) {
        List<LineDataSet> out = new ArrayList<>();
        if (entries == null || entries.isEmpty()) return out;
        float[] c = closesOf(entries);
        LineDataSet ds;
        if (indMa20 && (ds = lineSet(Indicators.sma(c, 20), "MA 20", 0xFF4FC3F7)) != null) out.add(ds);
        if (indMa50 && (ds = lineSet(Indicators.sma(c, 50), "MA 50", 0xFFBA68C8)) != null) out.add(ds);
        if (indMa200 && (ds = lineSet(Indicators.sma(c, 200), "MA 200", 0xFFF06292)) != null) out.add(ds);
        if (indBoll) {
            float[][] b = Indicators.bollinger(c, 20, 2.0);
            if ((ds = lineSet(b[1], "Bollinger 20,2", 0xFF90A4AE)) != null) out.add(ds);
            if ((ds = lineSet(b[2], "", 0xFF90A4AE)) != null) out.add(ds);
            if ((ds = lineSet(b[0], "", 0x9990A4AE)) != null) {
                ds.enableDashedLine(8f, 6f, 0f);
                out.add(ds);
            }
        }
        int n = entries.size();
        for (Drawing d : loadDrawings()) {
            if (d.type != 2) continue;
            int i1 = timeToIndex(d.t1), i2 = timeToIndex(d.t2);
            if (i1 < 0 || i2 < 0 || i1 == i2) continue;
            float pa = d.p1, pb = d.p2;
            int ia = i1, ib = i2;
            if (ia > ib) { ia = i2; ib = i1; pa = d.p2; pb = d.p1; }
            float slope = (pb - pa) / (ib - ia);
            List<Entry> pts = new ArrayList<>();
            pts.add(new Entry(ia, pa));
            pts.add(new Entry(n - 1, pa + slope * (n - 1 - ia)));
            LineDataSet t = new LineDataSet(pts, "");
            t.setColor(0xFFFFD54F);
            t.setLineWidth(1.8f);
            t.setDrawCircles(false);
            t.setDrawValues(false);
            t.setHighlightEnabled(false);
            out.add(t);
        }
        return out;
    }

    private void addLimit(YAxis axis, float price, String label, int color, float width) {
        com.github.mikephil.charting.components.LimitLine ll =
                new com.github.mikephil.charting.components.LimitLine(price, label);
        ll.setLineColor(color);
        ll.setLineWidth(width);
        ll.setTextColor(color);
        ll.setTextSize(9f);
        ll.setLabelPosition(com.github.mikephil.charting.components.LimitLine.LimitLabelPosition.RIGHT_TOP);
        axis.addLimitLine(ll);
    }

    /** Horizontal lines and Fibonacci levels, legend and the lower indicator pane. */
    private void applyOverlayExtras(com.github.mikephil.charting.charts.BarLineChartBase<?> chart,
                                    List<CandleEntry> entries) {
        YAxis left = chart.getAxisLeft();
        left.removeAllLimitLines();
        left.setDrawLimitLinesBehindData(true);
        float[] fibLevels = {0f, 0.236f, 0.382f, 0.5f, 0.618f, 0.786f, 1f};
        for (Drawing d : loadDrawings()) {
            if (d.type == 1) {
                addLimit(left, d.p1, formatPrice(d.p1, symbol), 0xFFFFD54F, 1.2f);
            } else if (d.type == 3) {
                float hi = Math.max(d.p1, d.p2), lo = Math.min(d.p1, d.p2);
                for (float r : fibLevels) {
                    float price = hi - (hi - lo) * r;
                    addLimit(left, price, String.format(Locale.US, "%.1f%%  %s", r * 100f,
                            formatPrice(price, symbol)), 0xFFFFA726, 0.9f);
                }
            }
        }
        // legend: every named line series
        List<com.github.mikephil.charting.components.LegendEntry> items = new ArrayList<>();
        if (chart.getData() != null) {
            com.github.mikephil.charting.data.ChartData<?> cd = chart.getData();
            for (Object o : cd.getDataSets()) {
                if (!(o instanceof LineDataSet)) continue;
                LineDataSet s = (LineDataSet) o;
                if (s.getLabel() != null && !s.getLabel().isEmpty()
                        && !"Price".equals(s.getLabel())) {
                    items.add(new com.github.mikephil.charting.components.LegendEntry(s.getLabel(),
                            com.github.mikephil.charting.components.Legend.LegendForm.LINE,
                            10f, 2f, null, s.getColor()));
                }
            }
        }
        com.github.mikephil.charting.components.Legend legend = chart.getLegend();
        if (items.isEmpty()) {
            legend.setEnabled(false);
        } else {
            legend.setEnabled(true);
            legend.setCustom(items);
            legend.setTextColor(isChartDark ? DARK_TEXT_SEC : LIGHT_TEXT_SEC);
            legend.setTextSize(10f);
            legend.setWordWrapEnabled(true);
        }
        updateIndicatorPane(entries);
    }

    private void setupIndicatorChartStyle() {
        if (indicatorChart == null) return;
        indicatorChart.setTouchEnabled(false);
        indicatorChart.getDescription().setEnabled(false);
        indicatorChart.setDrawGridBackground(false);
        indicatorChart.getAxisRight().setEnabled(false);
        XAxis x = indicatorChart.getXAxis();
        x.setDrawLabels(false);
        x.setDrawGridLines(false);
        x.setDrawAxisLine(false);
        YAxis l = indicatorChart.getAxisLeft();
        l.setDrawGridLines(false);
        l.setDrawAxisLine(false);
        l.setTextSize(9f);
        l.setDrawLimitLinesBehindData(true);
        com.github.mikephil.charting.components.Legend lg = indicatorChart.getLegend();
        lg.setVerticalAlignment(com.github.mikephil.charting.components.Legend.LegendVerticalAlignment.TOP);
        lg.setHorizontalAlignment(com.github.mikephil.charting.components.Legend.LegendHorizontalAlignment.LEFT);
        lg.setOrientation(com.github.mikephil.charting.components.Legend.LegendOrientation.HORIZONTAL);
        lg.setDrawInside(true);
        lg.setTextSize(10f);
    }

    private void setIndicatorPaneVisible(boolean on) {
        if (indicatorChart == null || on == paneShown) return;
        paneShown = on;
        int bottom = on ? dpToPx(118) : dpToPx(4);
        for (View chart : new View[]{candleStickChart, lineChart}) {
            if (chart == null) continue;
            ViewGroup.LayoutParams lp = chart.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) lp).bottomMargin = bottom;
                chart.setLayoutParams(lp);
            }
        }
        indicatorChart.setVisibility(on ? View.VISIBLE : View.GONE);
        float l = dpToPx(48), r = dpToPx(8);
        if (on) {
            if (candleStickChart != null) candleStickChart.setViewPortOffsets(l, dpToPx(8), r, dpToPx(22));
            if (lineChart != null) lineChart.setViewPortOffsets(l, dpToPx(8), r, dpToPx(22));
            indicatorChart.setViewPortOffsets(l, dpToPx(14), r, dpToPx(4));
        } else {
            if (candleStickChart != null) candleStickChart.resetViewPortOffsets();
            if (lineChart != null) lineChart.resetViewPortOffsets();
        }
        lastSyncScale = Float.NaN;
    }

    private void updateIndicatorPane(List<CandleEntry> entries) {
        if (indicatorChart == null) return;
        boolean on = indPane != 0 && entries != null && entries.size() >= 5;
        if (!on) {
            setIndicatorPaneVisible(false);
            return;
        }
        int n = entries.size();
        float[] c = closesOf(entries);
        int textSec = isChartDark ? DARK_TEXT_SEC : LIGHT_TEXT_SEC;
        CombinedData data = new CombinedData();
        YAxis left = indicatorChart.getAxisLeft();
        left.removeAllLimitLines();
        left.setTextColor(textSec);
        List<com.github.mikephil.charting.components.LegendEntry> legend = new ArrayList<>();
        com.github.mikephil.charting.components.Legend.LegendForm line =
                com.github.mikephil.charting.components.Legend.LegendForm.LINE;
        if (indPane == 1) {
            LineDataSet ds = lineSet(Indicators.rsi(c, 14), "RSI 14", 0xFFAB47BC);
            if (ds == null) { setIndicatorPaneVisible(false); return; }
            ds.setLineWidth(1.6f);
            data.setData(new LineData(ds));
            left.setAxisMinimum(0f);
            left.setAxisMaximum(100f);
            left.setLabelCount(3, true);
            addLimit(left, 30f, "", 0x66FFFFFF, 0.8f);
            addLimit(left, 70f, "", 0x66FFFFFF, 0.8f);
            legend.add(new com.github.mikephil.charting.components.LegendEntry("RSI 14", line,
                    10f, 2f, null, 0xFFAB47BC));
        } else {
            float[][] m = Indicators.macd(c, 12, 26, 9);
            LineData ld = new LineData();
            LineDataSet macdSet = lineSet(m[0], "MACD", 0xFF29B6F6);
            LineDataSet sigSet = lineSet(m[1], "Signal", 0xFFFFA726);
            if (macdSet != null) ld.addDataSet(macdSet);
            if (sigSet != null) ld.addDataSet(sigSet);
            List<BarEntry> bars = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (Float.isNaN(m[2][i])) continue;
                bars.add(new BarEntry(i, m[2][i]));
                colors.add(((m[2][i] >= 0 ? COLOR_GAIN : COLOR_LOSS) & 0x00FFFFFF) | 0x99000000);
            }
            if (ld.getDataSetCount() == 0 && bars.isEmpty()) {
                setIndicatorPaneVisible(false);
                return;
            }
            if (ld.getDataSetCount() > 0) data.setData(ld);
            if (!bars.isEmpty()) {
                BarDataSet bs = new BarDataSet(bars, "");
                bs.setColors(colors);
                bs.setDrawValues(false);
                bs.setHighlightEnabled(false);
                BarData bd = new BarData(bs);
                bd.setBarWidth(0.8f);
                data.setData(bd);
            }
            left.resetAxisMinimum();
            left.resetAxisMaximum();
            left.setLabelCount(3, false);
            addLimit(left, 0f, "", 0x66FFFFFF, 0.8f);
            legend.add(new com.github.mikephil.charting.components.LegendEntry("MACD 12,26,9", line,
                    10f, 2f, null, 0xFF29B6F6));
            legend.add(new com.github.mikephil.charting.components.LegendEntry("Signal", line,
                    10f, 2f, null, 0xFFFFA726));
        }
        setIndicatorPaneVisible(true);
        indicatorChart.getXAxis().setAxisMinimum(-0.5f);
        indicatorChart.getXAxis().setAxisMaximum(n - 0.5f);
        indicatorChart.setData(data);
        com.github.mikephil.charting.components.Legend lg = indicatorChart.getLegend();
        lg.setEnabled(true);
        lg.setCustom(legend);
        lg.setTextColor(textSec);
        lastSyncScale = Float.NaN;
        indicatorChart.invalidate();
    }

    /** Keeps the lower pane zoomed/scrolled exactly like the price chart above it. */
    private void syncIndicatorViewport() {
        if (indicatorChart == null || !paneShown || indicatorChart.getData() == null) return;
        com.github.mikephil.charting.charts.BarLineChartBase<?> src =
                lineChart != null && lineChart.getVisibility() == View.VISIBLE
                        ? lineChart : candleStickChart;
        if (src == null) return;
        android.graphics.Matrix touch = src.getViewPortHandler().getMatrixTouch();
        float[] v = new float[9];
        touch.getValues(v);
        float sx = v[android.graphics.Matrix.MSCALE_X];
        float tx = v[android.graphics.Matrix.MTRANS_X];
        if (sx == lastSyncScale && tx == lastSyncTrans) return;
        lastSyncScale = sx;
        lastSyncTrans = tx;
        v[android.graphics.Matrix.MSCALE_Y] = 1f;
        v[android.graphics.Matrix.MTRANS_Y] = 0f;
        android.graphics.Matrix copy = new android.graphics.Matrix();
        copy.setValues(v);
        indicatorChart.getViewPortHandler().refresh(copy, indicatorChart, true);
    }

    private void refreshCurrentChart() {
        if (currentEntries.isEmpty()) return;
        if (isCandleStick) updateCandleChart(currentEntries);
        else updateLineChart(currentEntries);
    }

    private String indicatorSummary() {
        int n = (indMa20 ? 1 : 0) + (indMa50 ? 1 : 0) + (indMa200 ? 1 : 0) + (indBoll ? 1 : 0)
                + (indPane != 0 ? 1 : 0);
        return n == 0 ? "None" : n + " on";
    }

    private void showIndicatorsDialog() {
        String[] items = {"MA 20", "MA 50", "MA 200", "Bollinger Bands (20, 2)",
                "RSI (14) - lower pane", "MACD (12, 26, 9) - lower pane"};
        boolean[] checked = {indMa20, indMa50, indMa200, indBoll, indPane == 1, indPane == 2};
        new AlertDialog.Builder(requireContext())
                .setTitle("Indicators")
                .setMultiChoiceItems(items, checked, (dlg, which, isChecked) -> {
                    checked[which] = isChecked;
                    if (isChecked && (which == 4 || which == 5)) {
                        int other = which == 4 ? 5 : 4;
                        checked[other] = false;
                        ((AlertDialog) dlg).getListView().setItemChecked(other, false);
                    }
                })
                .setPositiveButton("Apply", (dlg, w) -> {
                    indMa20 = checked[0];
                    indMa50 = checked[1];
                    indMa200 = checked[2];
                    indBoll = checked[3];
                    indPane = checked[4] ? 1 : (checked[5] ? 2 : 0);
                    requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                            .putBoolean("chart_ind_ma20", indMa20)
                            .putBoolean("chart_ind_ma50", indMa50)
                            .putBoolean("chart_ind_ma200", indMa200)
                            .putBoolean("chart_ind_boll", indBoll)
                            .putInt("chart_ind_pane", indPane).apply();
                    refreshCurrentChart();
                    if ((indMa50 || indMa200) && currentEntries.size() < 60) {
                        Toast.makeText(requireContext(), "Long averages need more candles - "
                                + "pick a longer visible range or smaller candles",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- drawing tools ----

    private String drawingsSummary() {
        int n = loadDrawings().size();
        return drawMode != 0 ? "Tap chart..." : (n == 0 ? "None" : n + " saved");
    }

    private void showDrawingDialog() {
        List<String> items = new ArrayList<>();
        items.add("Horizontal line (support / resistance)");
        items.add("Trend line (2 points)");
        items.add("Fibonacci retracement (2 points)");
        items.add("Manage / delete drawings");
        if (drawMode != 0) items.add("Cancel current drawing");
        new AlertDialog.Builder(requireContext())
                .setTitle("Drawing tools")
                .setItems(items.toArray(new String[0]), (dlg, which) -> {
                    switch (which) {
                        case 0: startDraw(1); break;
                        case 1: startDraw(2); break;
                        case 2: startDraw(3); break;
                        case 3: showManageDrawings(); break;
                        default: drawMode = 0; hasPendingPoint = false; break;
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void startDraw(int mode) {
        if (currentEntries.isEmpty()) return;
        drawMode = mode;
        hasPendingPoint = false;
        hideCrosshairInfo();
        Toast.makeText(requireContext(), mode == 1
                ? "Tap the chart where the line should go"
                : "Tap the chart to place the first point", Toast.LENGTH_LONG).show();
    }

    private void handleDrawTap(MotionEvent me) {
        com.github.mikephil.charting.charts.BarLineChartBase<?> chart =
                lineChart != null && lineChart.getVisibility() == View.VISIBLE ? lineChart
                        : candleStickChart;
        if (chart == null || currentTimestamps.isEmpty()) return;
        com.github.mikephil.charting.utils.MPPointD v = chart.getTransformer(
                YAxis.AxisDependency.LEFT).getValuesByTouchPoint(me.getX(), me.getY());
        int idx = Math.max(0, Math.min((int) Math.round(v.x), currentTimestamps.size() - 1));
        float price = (float) v.y;
        com.github.mikephil.charting.utils.MPPointD.recycleInstance(v);
        long t = currentTimestamps.get(idx);
        if (drawMode == 1) {
            addDrawing(1, t, price, t, price);
            return;
        }
        if (!hasPendingPoint) {
            hasPendingPoint = true;
            pendingT = t;
            pendingP = price;
            Toast.makeText(requireContext(), "Now tap the second point", Toast.LENGTH_SHORT).show();
            return;
        }
        addDrawing(drawMode, pendingT, pendingP, t, price);
    }

    private void addDrawing(int type, long t1, float p1, long t2, float p2) {
        List<Drawing> list = loadDrawings();
        Drawing d = new Drawing();
        d.type = type; d.t1 = t1; d.p1 = p1; d.t2 = t2; d.p2 = p2;
        list.add(d);
        saveDrawings(list);
        drawMode = 0;
        hasPendingPoint = false;
        refreshCurrentChart();
        Toast.makeText(requireContext(), "Drawing saved for " + cleanDisplaySymbol(symbol),
                Toast.LENGTH_SHORT).show();
    }

    private void showManageDrawings() {
        List<Drawing> list = loadDrawings();
        if (list.isEmpty()) {
            Toast.makeText(requireContext(), "No drawings on this symbol yet", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> items = new ArrayList<>();
        items.add("Delete ALL drawings");
        for (Drawing d : list) {
            String what = d.type == 1 ? "Horizontal line @ " + formatPrice(d.p1, symbol)
                    : d.type == 2 ? "Trend line " + formatPrice(d.p1, symbol) + " \u2192 "
                            + formatPrice(d.p2, symbol)
                    : "Fibonacci " + formatPrice(d.p1, symbol) + " \u2192 " + formatPrice(d.p2, symbol);
            items.add("Delete: " + what);
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Drawings - " + cleanDisplaySymbol(symbol))
                .setItems(items.toArray(new String[0]), (dlg, which) -> {
                    if (which == 0) list.clear(); else list.remove(which - 1);
                    saveDrawings(list);
                    refreshCurrentChart();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    // ---- price alert from the chart ----

    private void showChartAlertDialog(float price) {
        if (!isAdded() || getContext() == null) return;
        com.google.firebase.auth.FirebaseUser user =
                com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(requireContext(), "Sign in to set price alerts", Toast.LENGTH_SHORT).show();
            return;
        }
        final android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setHint("Target price");
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (price > 0f) input.setText(String.format(Locale.US, "%.4f", price).replaceAll("0+$", "")
                .replaceAll("\\.$", ""));
        final android.widget.RadioButton above = new android.widget.RadioButton(requireContext());
        above.setText("Alert when the price rises to or above");
        final android.widget.RadioButton below = new android.widget.RadioButton(requireContext());
        below.setText("Alert when the price falls to or below");
        android.widget.RadioGroup group = new android.widget.RadioGroup(requireContext());
        above.setId(View.generateViewId());
        below.setId(View.generateViewId());
        group.addView(above);
        group.addView(below);
        if (price > 0f && lastPrice > 0f && price < lastPrice) below.setChecked(true);
        else above.setChecked(true);
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(20);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        box.addView(group);
        new AlertDialog.Builder(requireContext())
                .setTitle("Price alert - " + cleanDisplaySymbol(symbol))
                .setView(box)
                .setPositiveButton("Set alert", (dlg, w) -> {
                    float target;
                    try {
                        target = Float.parseFloat(input.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        target = 0f;
                    }
                    if (!Float.isFinite(target) || target <= 0f) {
                        Toast.makeText(requireContext(), "Enter a price greater than 0",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Map<String, Object> update = new HashMap<>();
                    update.put("symbol", symbol);
                    update.put("alertTargetPrice", target);
                    update.put("alertDirection", below.isChecked() ? "BELOW" : "ABOVE");
                    update.put("alertEnabled", true);
                    update.put("alertTriggered", false);
                    com.google.firebase.database.FirebaseDatabase.getInstance()
                            .getReference("users").child(user.getUid())
                            .child("watchlist-stocks").child(symbol.replace(":", "_"))
                            .updateChildren(update)
                            .addOnSuccessListener(unused -> {
                                if (!isAdded()) return;
                                SharedPreferences prefs = requireContext()
                                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                                if (!prefs.getBoolean(PriceAlertScheduler.KEY_ENABLED, false)) {
                                    prefs.edit().putBoolean(PriceAlertScheduler.KEY_ENABLED, true).apply();
                                    PriceAlertScheduler.schedule(requireContext());
                                }
                                Toast.makeText(requireContext(), "Alert set (the stock is also on your watchlist)",
                                        Toast.LENGTH_LONG).show();
                            })
                            .addOnFailureListener(e -> {
                                if (isAdded()) Toast.makeText(requireContext(),
                                        "Could not save alert: " + e.getMessage(),
                                        Toast.LENGTH_LONG).show();
                            });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onDestroyView() {
        if (candleStickChart != null && syncListener != null) {
            android.view.ViewTreeObserver vto = candleStickChart.getViewTreeObserver();
            if (vto.isAlive()) vto.removeOnDrawListener(syncListener);
        }
        syncListener = null;
        super.onDestroyView();
    }

    private String candleLabelShort() {
        for (int i = 0; i < CANDLE_VALUES.length; i++) {
            if (CANDLE_VALUES[i] == candleMinutes) {
                return i == 0 ? "1 day" : CANDLE_LABELS[i];
            }
        }
        return "1 day";
    }

    private void showCandleSizeDialog() {
        int selected = 0;
        for (int i = 0; i < CANDLE_VALUES.length; i++) {
            if (CANDLE_VALUES[i] == candleMinutes) selected = i;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Candle size")
                .setSingleChoiceItems(CANDLE_LABELS, selected, (dialog, which) -> {
                    candleMinutes = CANDLE_VALUES[which];
                    requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putInt("chart_candle_minutes", candleMinutes).apply();
                    hideCrosshairInfo();
                    dialog.dismiss();
                    if (candleMinutes > 0 && !intradayRangeAllowed()) {
                        Toast.makeText(requireContext(), "Intraday data is limited to "
                                + effectiveRange(visibleChartRange) + " of history",
                                Toast.LENGTH_LONG).show();
                    }
                    fetchStockData(symbol, interval);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean intradayRangeAllowed() {
        return effectiveRange(visibleChartRange).equals(visibleChartRange);
    }

    /** Intraday candles only exist for a limited history; clamp the range accordingly. */
    private String effectiveRange(String range) {
        if (candleMinutes <= 0) return range;
        String[] order = {"1d", "5d", "1mo", "3mo", "6mo", "1y", "2y", "5y", "max"};
        String cap;
        int base = baseMinutes();
        if (base == 1) cap = "5d";
        else if (base <= 30) cap = "1mo";
        else cap = "1y";
        int ri = -1, ci = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i].equals(range)) ri = i;
            if (order[i].equals(cap)) ci = i;
        }
        return ri < 0 || ri > ci ? cap : range;
    }

    /** Native Yahoo/Binance bar size used to build the requested candle size. */
    private int baseMinutes() {
        int m = candleMinutes;
        if (m <= 0) return 0;
        if (m % 60 == 0) return 60;
        if (m % 30 == 0) return 30;
        if (m % 15 == 0) return 15;
        if (m % 5 == 0) return 5;
        return 1;
    }

    private static final class Bar {
        long t; float o, h, l, c, v;
    }

    /** Merges base bars into buckets of {@code minutes}, aligned to each trading day's first bar. */
    private static List<Bar> aggregateBars(List<Bar> src, int minutes, long gmtOffsetSec) {
        List<Bar> out = new ArrayList<>();
        long span = minutes * 60_000L;
        long curDay = Long.MIN_VALUE, dayFirst = 0, curBucket = Long.MIN_VALUE;
        Bar cur = null;
        for (Bar b : src) {
            long day = Math.floorDiv(b.t / 1000L + gmtOffsetSec, 86400L);
            if (day != curDay) { curDay = day; dayFirst = b.t; curBucket = Long.MIN_VALUE; }
            long bucket = (b.t - dayFirst) / span;
            if (cur == null || bucket != curBucket || day != Math.floorDiv(cur.t / 1000L + gmtOffsetSec, 86400L)) {
                cur = new Bar();
                cur.t = b.t; cur.o = b.o; cur.h = b.h; cur.l = b.l; cur.c = b.c; cur.v = b.v;
                out.add(cur);
                curBucket = bucket;
            } else {
                cur.h = Math.max(cur.h, b.h);
                cur.l = Math.min(cur.l, b.l);
                cur.c = b.c;
                cur.v += b.v;
            }
        }
        return out;
    }

    private void showVisibleRangeDialog() {
        String[] ranges = {"1 day", "5 days", "1 month", "3 months", "6 months",
                "1 year", "5 years", "Maximum"};
        String[] rangeValues = {"1d", "5d", "1mo", "3mo", "6mo", "1y", "5y", "max"};
        int selected = 5;
        for (int i = 0; i < rangeValues.length; i++) {
            if (rangeValues[i].equals(visibleChartRange)) {
                selected = i;
                break;
            }
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Visible chart range")
                .setSingleChoiceItems(ranges, selected, (dialog, which) -> {
                    visibleChartRange = rangeValues[which];
                    hideCrosshairInfo();
                    dialog.dismiss();
                    fetchStockData(symbol, interval);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** One square option tile: icon on top, name, and its current state underneath. */
    private void addChartMenuTile(LinearLayout row, Object[] tile, int primaryText,
                                  int secondaryText, AlertDialog[] dialogHolder,
                                  boolean withLeftGap) {
        Context context = row.getContext();
        String icon = (String) tile[0];
        String label = (String) tile[1];
        String status = (String) tile[2];
        boolean active = (Boolean) tile[3];
        Runnable action = (Runnable) tile[4];

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        card.setPadding(dpToPx(6), dpToPx(8), dpToPx(6), dpToPx(8));
        card.setClickable(true);
        card.setFocusable(true);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(active ? (isChartDark ? 0xFF1E2A3A : 0xFFE1EEFC)
                : (isChartDark ? 0xFF0B0F14 : 0xFFF0F4F8));
        bg.setCornerRadius(dpToPx(14));
        bg.setStroke(dpToPx(1), active ? context.getColor(R.color.chart_gold)
                : context.getColor(R.color.colorBorder));
        card.setBackground(bg);

        TextView iconView = new TextView(context);
        iconView.setText(icon);
        iconView.setTextSize(17f);
        iconView.setTextColor(COLOR_PRIMARY);
        iconView.setGravity(android.view.Gravity.CENTER);
        card.addView(iconView);

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextColor(primaryText);
        labelView.setTextSize(12f);
        labelView.setTypeface(null, android.graphics.Typeface.BOLD);
        labelView.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dpToPx(2);
        card.addView(labelView, labelParams);

        TextView statusView = new TextView(context);
        statusView.setText(status);
        statusView.setTextColor(active ? context.getColor(R.color.chart_gold) : secondaryText);
        statusView.setTextSize(10f);
        statusView.setGravity(android.view.Gravity.CENTER);
        card.addView(statusView);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (withLeftGap) cardParams.leftMargin = dpToPx(8);
        row.addView(card, cardParams);

        card.setOnClickListener(view -> {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            action.run();
        });
    }

    private void addChartMenuOption(LinearLayout parent, String icon, String label, String description,
                                    int primaryText, int secondaryText, int surface,
                                    Runnable action, AlertDialog[] dialogHolder) {
        Context context = parent.getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));
        row.setClickable(true);
        row.setFocusable(true);

        android.graphics.drawable.GradientDrawable rowBackground =
                new android.graphics.drawable.GradientDrawable();
        rowBackground.setColor(isChartDark ? 0xFF0B0F14 : 0xFFF0F4F8);
        rowBackground.setCornerRadius(dpToPx(14));
        row.setBackground(rowBackground);

        TextView iconView = new TextView(context);
        iconView.setText(icon);
        iconView.setTextSize(20f);
        iconView.setTextColor(COLOR_PRIMARY);
        iconView.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable iconBackground =
                new android.graphics.drawable.GradientDrawable();
        iconBackground.setColor(isChartDark ? 0xFF1E2A3A : 0xFFE1EEFC);
        iconBackground.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        iconView.setBackground(iconBackground);
        row.addView(iconView, new LinearLayout.LayoutParams(dpToPx(42), dpToPx(42)));

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelsParams.setMargins(dpToPx(12), 0, 0, 0);
        labels.setLayoutParams(labelsParams);

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextColor(primaryText);
        labelView.setTextSize(14f);
        labelView.setTypeface(null, android.graphics.Typeface.BOLD);
        labels.addView(labelView);

        TextView descriptionView = new TextView(context);
        descriptionView.setText(description);
        descriptionView.setTextColor(secondaryText);
        descriptionView.setTextSize(12f);
        labels.addView(descriptionView);
        row.addView(labels);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dpToPx(8);
        parent.addView(row, rowParams);
        row.setOnClickListener(view -> {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            action.run();
        });
    }

    private void toggleChartType() {
        isCandleStick = !isCandleStick;
        if (isCandleStick) {
            candleStickChart.setVisibility(View.VISIBLE);
            lineChart.setVisibility(View.GONE);
        } else {
            candleStickChart.setVisibility(View.GONE);
            lineChart.setVisibility(View.VISIBLE);
        }
        fetchStockData(symbol, interval);
    }

    private void updateMagnetModeLabel() {
        if (btnMagnetMode != null) {
            btnMagnetMode.setText(magnetMode ? "Magnet: On" : "Magnet: Off");
        }
    }

    private void attachMarker(com.github.mikephil.charting.charts.BarLineChartBase<?> chart) {
        TradingMarkerView mv = new TradingMarkerView(requireContext());
        mv.setDateLabels(dateLabels);
        mv.setChartView(chart);
        mv.setOnHighlightListener((price, date) -> {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> showCrosshairInfo(price, date));
            }
        });
        chart.setMarker(mv);
    }

    private void updateCandleChart(List<CandleEntry> entries) {
        CandleDataSet dataSet = new CandleDataSet(entries, "");
        dataSet.setIncreasingColor(COLOR_GAIN);
        dataSet.setDecreasingColor(COLOR_LOSS);
        dataSet.setIncreasingPaintStyle(Paint.Style.FILL);
        dataSet.setDecreasingPaintStyle(Paint.Style.FILL);
        dataSet.setShadowColor(isChartDark ? DARK_TEXT_SEC : LIGHT_TEXT_SEC);
        dataSet.setShadowWidth(1f);
        dataSet.setDrawValues(false);
        dataSet.setHighlightEnabled(true);
        dataSet.setHighLightColor(COLOR_PRIMARY);
        dataSet.enableDashedHighlightLine(10f, 5f, 0f);
        candleStickChart.highlightValues(null);
        CandleData candleData = new CandleData(dataSet);
        CombinedData combinedData = new CombinedData();
        combinedData.setData(candleData);
        List<LineDataSet> lineSets = new ArrayList<>();
        if (!movingAverageEntries.isEmpty()) lineSets.add(createMovingAverageDataSet());
        lineSets.addAll(buildOverlaySets(entries));
        boolean hasLines = !lineSets.isEmpty();
        if (hasLines) {
            LineData overlayData = new LineData();
            for (LineDataSet s : lineSets) overlayData.addDataSet(s);
            combinedData.setData(overlayData);
        }
        // The volume series is always part of the data and only hidden when switched off,
        // so toggling it never changes the chart's structure (renderers / draw order).
        boolean hasVolume = currentVolumes.size() == entries.size() && !entries.isEmpty();
        List<BarEntry> bars = new ArrayList<>();
        int[] barColors = new int[Math.max(1, entries.size())];
        float maxVolume = 0f;
        for (int i = 0; i < entries.size(); i++) {
            float v = hasVolume ? Math.max(0f, currentVolumes.get(i)) : 0f;
            maxVolume = Math.max(maxVolume, v);
            bars.add(new BarEntry(entries.get(i).getX(), v));
            float reference = volumeColorByOpen || i == 0
                    ? entries.get(i).getOpen() : entries.get(i - 1).getClose();
            boolean up = entries.get(i).getClose() >= reference;
            barColors[i] = ((up ? COLOR_GAIN : COLOR_LOSS) & 0x00FFFFFF) | 0x66000000;
        }
        if (bars.isEmpty()) bars.add(new BarEntry(0f, 0f));
        BarDataSet volumeSet = new BarDataSet(bars, "Volume");
        volumeSet.setColors(barColors);
        volumeSet.setDrawValues(false);
        volumeSet.setHighlightEnabled(false);
        volumeSet.setVisible(showVolume && maxVolume > 0f);
        volumeSet.setAxisDependency(com.github.mikephil.charting.components.YAxis.AxisDependency.RIGHT);
        BarData barData = new BarData(volumeSet);
        barData.setBarWidth(0.8f);
        combinedData.setData(barData);
        com.github.mikephil.charting.components.YAxis right = candleStickChart.getAxisRight();
        right.setAxisMinimum(0f);
        right.setAxisMaximum(maxVolume > 0f ? maxVolume * 4.5f : 1f); // bars use the bottom ~22%
        candleStickChart.setDrawOrder(!hasLines
                ? new CombinedChart.DrawOrder[]{CombinedChart.DrawOrder.BAR, CombinedChart.DrawOrder.CANDLE}
                : new CombinedChart.DrawOrder[]{CombinedChart.DrawOrder.BAR, CombinedChart.DrawOrder.CANDLE, CombinedChart.DrawOrder.LINE});
        candleStickChart.setData(combinedData);
        applyOverlayExtras(candleStickChart, entries);
        attachMarker(candleStickChart);
        candleStickChart.animateX(400);
        candleStickChart.invalidate();
    }

    private void updateLineChart(List<CandleEntry> candleEntries) {
        List<Entry> lineEntries = new ArrayList<>();
        for (CandleEntry c : candleEntries) lineEntries.add(new Entry(c.getX(), c.getClose()));
        LineDataSet ds = new LineDataSet(lineEntries, showMa150 ? "Price" : "");
        ds.setColor(COLOR_PRIMARY);
        ds.setLineWidth(2.5f);
        ds.setDrawCircles(false);
        ds.setDrawValues(false);
        ds.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        ds.setHighLightColor(COLOR_PRIMARY);
        ds.setHighlightEnabled(true);
        ds.enableDashedHighlightLine(10f, 5f, 0f);
        ds.setDrawFilled(true);
        ds.setFillColor(COLOR_FILL);
        ds.setFillAlpha(isChartDark ? 90 : 50);
        lineChart.highlightValues(null);
        LineData lineData = new LineData(ds);
        if (!movingAverageEntries.isEmpty()) {
            lineData.addDataSet(createMovingAverageDataSet());
        }
        for (LineDataSet o : buildOverlaySets(candleEntries)) lineData.addDataSet(o);
        lineChart.setData(lineData);
        applyOverlayExtras(lineChart, candleEntries);
        attachMarker(lineChart);
        lineChart.animateX(400);
        lineChart.invalidate();
    }

    private LineDataSet createMovingAverageDataSet() {
        LineDataSet averageSet = new LineDataSet(movingAverageEntries, "150-day MA");
        averageSet.setColor(0xFFFFB74D);
        averageSet.setLineWidth(2f);
        averageSet.setDrawCircles(false);
        averageSet.setDrawValues(false);
        averageSet.setHighlightEnabled(false);
        return averageSet;
    }

    private void fetchStockData(String symbol, String interval) {
        int requestGeneration = ++chartRequestGeneration;
        if (isCryptoSymbol(symbol)) {
            fetchCryptoData(symbol, interval, requestGeneration);
        } else {
            fetchYahooData(symbol, interval, requestGeneration);
        }
    }

    private String[] getYahooParams() {
        if (candleMinutes == -1) return new String[]{"1wk", visibleChartRange};
        if (candleMinutes > 0) {
            int base = baseMinutes();
            return new String[]{base == 60 ? "60m" : base + "m", effectiveRange(visibleChartRange)};
        }
        return new String[]{"1d", visibleChartRange};
    }

    private SimpleDateFormat dateFormatFor(String yahooInterval) {
        switch (yahooInterval) {
            case "1m": case "5m": case "15m": case "30m": case "60m": case "1h":
                return new SimpleDateFormat("MM/dd HH:mm", Locale.US);
            case "1mo":
                return new SimpleDateFormat("yyyy-MM", Locale.US);
            default:
                return new SimpleDateFormat("MM/dd/yy", Locale.US);
        }
    }

    private void fetchYahooData(String sym, String ignoredInterval, int requestGeneration) {
        String[] p = getYahooParams();
        String encodedSymbol = URLEncoder.encode(sym, StandardCharsets.UTF_8)
                .replace("+", "%20");
        String url = ApiConfig.YAHOO_CHART + encodedSymbol
                + "?interval=" + p[0] + "&range=" + p[1] + "&includePrePost=false";

        Request req = new Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (getActivity() != null) getActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), "Network error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (requestGeneration != chartRequestGeneration) return;
                if (!response.isSuccessful() || response.body() == null) return;
                try {
                    JSONObject root   = new JSONObject(response.body().string());
                    JSONArray  result = root.getJSONObject("chart").optJSONArray("result");
                    if (result == null || result.length() == 0) {
                        if (getActivity() != null) getActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(), "No data for: " + sym, Toast.LENGTH_SHORT).show());
                        return;
                    }
                    JSONObject item  = result.getJSONObject(0);
                    JSONArray  ts    = item.getJSONArray("timestamp");
                    JSONObject quote = item.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0);
                    JSONArray  opens = quote.getJSONArray("open");
                    JSONArray  highs = quote.getJSONArray("high");
                    JSONArray  lows  = quote.getJSONArray("low");
                    JSONArray  cls   = quote.getJSONArray("close");
                    int size = ts.length();
                    if (size == 0) return;
                    List<Float> closes = new ArrayList<>();
                    List<String> labels = new ArrayList<>();
                    List<Long> timestamps = new ArrayList<>();
                    List<CandleEntry> entries = new ArrayList<>();
                    JSONArray vols = quote.optJSONArray("volume");
                    List<Float> volumes = new ArrayList<>();
                    SimpleDateFormat sdf = dateFormatFor(p[0]);
                    float lc = 0f; int vc = 0;
                    List<Bar> raw = new ArrayList<>();
                    for (int i = 0; i < size; i++) {
                        if (cls.isNull(i)||opens.isNull(i)||highs.isNull(i)||lows.isNull(i)) continue;
                        Bar b = new Bar();
                        b.o = (float) opens.getDouble(i); b.h = (float) highs.getDouble(i);
                        b.l = (float) lows.getDouble(i);  b.c = (float) cls.getDouble(i);
                        b.t = ts.getLong(i) * 1000L;
                        b.v = vols != null && !vols.isNull(i) ? (float) vols.getDouble(i) : 0f;
                        raw.add(b);
                    }
                    if (candleMinutes > 0 && candleMinutes != baseMinutes()) {
                        long off = item.optJSONObject("meta") != null
                                ? item.getJSONObject("meta").optLong("gmtoffset", 0) : 0;
                        raw = aggregateBars(raw, candleMinutes, off);
                    }
                    for (Bar b : raw) {
                        labels.add(sdf.format(new Date(b.t)));
                        timestamps.add(b.t);
                        closes.add(b.c);
                        volumes.add(b.v);
                        entries.add(new CandleEntry(vc, b.h, b.l, b.o, b.c));
                        lc = b.c; vc++;
                    }
                    if (vc == 0) return;
                    YahooQuoteMath.Quote marketQuote = YahooQuoteMath.fromChartItem(item);
                    float quotePrice = (float) marketQuote.currentPrice;
                    if (!Float.isFinite(quotePrice) || quotePrice <= 0f) quotePrice = lc;
                    float periodStartPrice = entries.get(0).getOpen();
                    float dailyPreviousClose = (float) marketQuote.previousClose;
                    if (!Float.isFinite(dailyPreviousClose) || dailyPreviousClose <= 0f) {
                        dailyPreviousClose = closes.size() > 1
                                ? closes.get(closes.size() - 2) : periodStartPrice;
                    }
                    pendingVolumes = volumes;
                    postChartUpdate(sym, entries, labels, timestamps, closes, quotePrice,
                            periodStartPrice, dailyPreviousClose, requestGeneration);
                } catch (Exception e) { Log.e("ChartFragment", "Yahoo parse error", e); }
            }
        });
    }

    private String getBinanceInterval() {
        if (candleMinutes == -1) return "1w";
        if (candleMinutes <= 0) return "1d";
        int base = baseMinutes();
        return base == 60 ? "1h" : base + "m";
    }

    private void fetchCryptoData(String sym, String ignoredInterval, int requestGeneration) {
        String pair = sym.contains(":") ? sym.substring(sym.indexOf(':') + 1) : sym;
        String binanceInterval = getBinanceInterval();
        int limit = getCryptoCandleLimit(binanceInterval, visibleChartRange);
        if ("1m".equals(binanceInterval) && limit > 1000) limit = 1000;
        String url = ApiConfig.BINANCE_KLINES + pair
                + "&interval=" + binanceInterval + "&limit=" + limit;

        Request req = new Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0").build();

        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (getActivity() != null) getActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), "Crypto error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (requestGeneration != chartRequestGeneration) return;
                if (!response.isSuccessful() || response.body() == null) return;
                try {
                    JSONArray arr = new JSONArray(response.body().string());
                    if (arr.length() == 0) return;
                    List<Float> closes = new ArrayList<>();
                    List<String> labels = new ArrayList<>();
                    List<Long> timestamps = new ArrayList<>();
                    List<CandleEntry> entries = new ArrayList<>();
                    List<Float> volumes = new ArrayList<>();
                    String[] yahooP = getYahooParams();
                    SimpleDateFormat sdf = dateFormatFor(yahooP[0]);
                    float lc = 0f; int vc = 0;
                    List<Bar> raw = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONArray bar = arr.getJSONArray(i);
                        Bar b = new Bar();
                        b.o = (float) Double.parseDouble(bar.getString(1));
                        b.h = (float) Double.parseDouble(bar.getString(2));
                        b.l = (float) Double.parseDouble(bar.getString(3));
                        b.c = (float) Double.parseDouble(bar.getString(4));
                        b.t = bar.getLong(0);
                        b.v = (float) Double.parseDouble(bar.getString(5));
                        raw.add(b);
                    }
                    if (candleMinutes > 0 && candleMinutes != baseMinutes()) {
                        raw = aggregateBars(raw, candleMinutes, 0);
                    }
                    for (Bar b : raw) {
                        labels.add(sdf.format(new Date(b.t)));
                        timestamps.add(b.t);
                        closes.add(b.c);
                        volumes.add(b.v);
                        entries.add(new CandleEntry(vc, b.h, b.l, b.o, b.c));
                        lc = b.c; vc++;
                    }
                    if (vc == 0) return;
                    pendingVolumes = volumes;
                    float periodStartPrice = entries.get(0).getOpen();
                    float dailyPreviousClose = entries.size() > 1
                            ? entries.get(entries.size() - 2).getClose() : periodStartPrice;
                    postChartUpdate(sym, entries, labels, timestamps, closes, lc, periodStartPrice,
                            dailyPreviousClose, requestGeneration);
                } catch (Exception e) { Log.e("ChartFragment", "Crypto parse error", e); }
            }
        });
    }

    private int getCryptoCandleLimit(String binanceInterval, String range) {
        long rangeSeconds;
        switch (range) {
            case "1d": rangeSeconds = 24L * 60 * 60; break;
            case "5d": rangeSeconds = 7L * 24 * 60 * 60; break;
            case "1mo": rangeSeconds = 30L * 24 * 60 * 60; break;
            case "3mo": rangeSeconds = 90L * 24 * 60 * 60; break;
            case "6mo": rangeSeconds = 180L * 24 * 60 * 60; break;
            case "2y": rangeSeconds = 730L * 24 * 60 * 60; break;
            case "5y": rangeSeconds = 1825L * 24 * 60 * 60; break;
            case "max": return 1000;
            case "1y":
            default: rangeSeconds = 365L * 24 * 60 * 60; break;
        }

        long candleSeconds;
        switch (binanceInterval) {
            case "1m": candleSeconds = 60; break;
            case "5m": candleSeconds = 5L * 60; break;
            case "15m": candleSeconds = 15L * 60; break;
            case "30m": candleSeconds = 30L * 60; break;
            case "1h": candleSeconds = 60L * 60; break;
            case "4h": candleSeconds = 4L * 60 * 60; break;
            case "1w": candleSeconds = 7L * 24 * 60 * 60; break;
            case "1M": candleSeconds = 30L * 24 * 60 * 60; break;
            case "1d":
            default: candleSeconds = 24L * 60 * 60; break;
        }
        return (int) Math.max(1, Math.min(1000, (rangeSeconds + candleSeconds - 1) / candleSeconds));
    }

    private void postChartUpdate(String sym, List<CandleEntry> entries, List<String> labels,
                                 List<Long> timestamps, List<Float> closes,
                                 float lastClose, float periodStartPrice, float dailyPreviousClose,
                                 int requestGeneration) {
        if (!Float.isFinite(periodStartPrice) || periodStartPrice <= 0f) {
            periodStartPrice = lastClose;
        }
        if (!Float.isFinite(dailyPreviousClose) || dailyPreviousClose <= 0f) {
            dailyPreviousClose = periodStartPrice;
        }
        boolean showDailyChange = getActivity() != null
                && getActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_CHANGE_PERCENT_DAILY, false);
        float changeBasePrice = showDailyChange ? dailyPreviousClose : periodStartPrice;
        float changePct = (float) DailyQuoteMath.percentChange(lastClose, changeBasePrice);
        if (!Float.isFinite(changePct)) changePct = 0f;
        boolean gain = changePct >= 0;

        String priceFormatted = formatPrice(lastClose, sym);
        float change = lastClose - changeBasePrice;
        String changeFormatted = formatPrice(Math.abs(change), sym);
        String changeStr = String.format(Locale.US, "%+.2f%% (%s$%s)",
                changePct, gain ? "+" : "-", changeFormatted);

        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (requestGeneration != chartRequestGeneration) return;
                currentEntries.clear();
                currentEntries.addAll(entries);
                dateLabels.clear();
                dateLabels.addAll(labels);
                currentTimestamps.clear();
                currentTimestamps.addAll(timestamps);
                fullCloses.clear();
                fullCloses.addAll(closes);
                currentVolumes.clear();
                currentVolumes.addAll(pendingVolumes);
                lastPrice = lastClose;
                lastDisplayedDailyChange = showDailyChange;
                hasDisplayedChangeMode = true;
                if (showMa150) movingAverageEntries.clear();

                if (priceText != null) {
                    priceText.setText("$" + priceFormatted);
                    priceText.setTextColor(gain ? COLOR_GAIN : COLOR_LOSS);
                }

                if (changeText != null) {
                    changeText.setText(changeStr);
                    changeText.setTextColor(gain ? COLOR_GAIN : COLOR_LOSS);
                    android.graphics.drawable.Drawable background =
                            changeText.getBackground().mutate();
                    if (background instanceof android.graphics.drawable.GradientDrawable) {
                        ((android.graphics.drawable.GradientDrawable) background).setStroke(
                                dpToPx(1), gain ? COLOR_GAIN : COLOR_LOSS);
                    }
                }

                if (tickerText != null) {
                    tickerText.setText(cleanDisplaySymbol(sym));
                }

                if (tickerLabelTop != null) {
                    tickerLabelTop.setText(cleanDisplaySymbol(sym));
                }
                if (chartTickerTitle != null) {
                    chartTickerTitle.setText(cleanDisplaySymbol(sym));
                }

                if (getActivity() != null) {
                    getActivity().setTitle("Chart: " + cleanDisplaySymbol(sym));
                }

                hideCrosshairInfo();

                if (isCandleStick) {
                    updateCandleChart(entries);
                } else {
                    updateLineChart(entries);
                }
                if (showMa150) {
                    fetchMovingAverage(sym, entries, timestamps, requestGeneration);
                } else {
                    movingAverageEntries.clear();
                }
            });
        }
    }

    private void fetchMovingAverage(String sym, List<CandleEntry> chartEntries,
                                    List<Long> timestamps, int requestGeneration) {
        if (sym == null || sym.contains(":") || timestamps.isEmpty()) {
            movingAverageEntries.clear();
            if (isCandleStick) updateCandleChart(chartEntries);
            else updateLineChart(chartEntries);
            return;
        }
        String encoded = URLEncoder.encode(sym, StandardCharsets.UTF_8)
                .replace("+", "%20");
        String url = ApiConfig.YAHOO_CHART + encoded
                + "?interval=1d&range=5y&includePrePost=false";
        Request request = new Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0").build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException error) {
                Log.w("ChartFragment", "Could not load 150-day moving average for " + sym, error);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response)
                    throws IOException {
                if (!response.isSuccessful() || response.body() == null) {
                    Log.w("ChartFragment", "Moving-average request failed: HTTP "
                            + response.code());
                    response.close();
                    return;
                }
                try {
                    JSONObject result = new JSONObject(response.body().string())
                            .getJSONObject("chart").getJSONArray("result").getJSONObject(0);
                    JSONArray dailyTimes = result.getJSONArray("timestamp");
                    JSONArray dailyCloses = result.getJSONObject("indicators")
                            .getJSONArray("quote").getJSONObject(0).getJSONArray("close");
                    TreeMap<Long, Float> averagesByTime = new TreeMap<>();
                    ArrayList<Float> window = new ArrayList<>();
                    double sum = 0;
                    for (int i = 0; i < dailyTimes.length(); i++) {
                        if (dailyCloses.isNull(i)) continue;
                        float close = (float) dailyCloses.optDouble(i, Double.NaN);
                        if (!Float.isFinite(close) || close <= 0f) continue;
                        window.add(close);
                        sum += close;
                        if (window.size() > 150) sum -= window.remove(0);
                        if (window.size() == 150) {
                            averagesByTime.put(dailyTimes.getLong(i) * 1000L,
                                    (float) (sum / 150.0));
                        }
                    }
                    ArrayList<Entry> mappedEntries = new ArrayList<>();
                    for (int i = 0; i < timestamps.size(); i++) {
                        Map.Entry<Long, Float> average =
                                averagesByTime.floorEntry(timestamps.get(i));
                        if (average != null) {
                            mappedEntries.add(new Entry(i, average.getValue()));
                        }
                    }
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        if (requestGeneration != chartRequestGeneration || !showMa150) return;
                        movingAverageEntries.clear();
                        movingAverageEntries.addAll(mappedEntries);
                        if (isCandleStick) updateCandleChart(chartEntries);
                        else updateLineChart(chartEntries);
                    });
                } catch (Exception error) {
                    Log.w("ChartFragment", "Could not parse 150-day moving average for " + sym,
                            error);
                } finally {
                    response.close();
                }
            }
        });
    }

    private void openChartFromInput(String raw) {
        String upper = raw.toUpperCase(Locale.US).trim();

        String cryptoSym = CRYPTO_MAP.get(upper);
        if (cryptoSym != null) {
            symbol = cryptoSym;
        } else {
            String forexSym = FOREX_MAP.get(upper);
            symbol = (forexSym != null) ? forexSym : upper;
        }

        SharedPreferences prefs = requireActivity()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String mode = prefs.getString(KEY_SYMBOL_MODE, "last");
        // שמור "last" רק אם לא במצב fixed
        if (!"fixed".equals(mode)) {
            prefs.edit()
                    .putString(KEY_LAST_SYMBOL, symbol)
                    .apply();
        }

        updateTickerButtonLabel();
        updateTickerLabelTop();
        hideCrosshairInfo();
        fetchStockData(symbol, interval);
    }

}