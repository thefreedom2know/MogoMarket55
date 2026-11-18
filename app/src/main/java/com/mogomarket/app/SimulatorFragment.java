package com.mogomarket.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Paper-trading simulator: market / limit / stop orders, take-profit and stop-loss,
 * live portfolio, order book, trade history, performance stats with an equity curve,
 * plus the original "what if" simulation over the user's real closed trades.
 */
public class SimulatorFragment extends Fragment {

    // ---- plain state objects (parsed from Firebase by hand, no model classes) ----
    private static final class Pos {
        String symbol;
        double shares, avg, tp, sl;
    }

    private static final class Ord {
        String key, symbol, side, type;
        double shares, price, tp, sl;
        long createdAt;
    }

    private static final class Trd {
        String symbol, side, type, note;
        double shares, price, realized, fee;
        long at;
    }

    private static final String[] TAB_NAMES = {
            "Trade", "Portfolio", "Orders", "History", "Stats", "What-if"};
    private static final long POLL_MS = 8000;

    private DatabaseReference acct;
    private DatabaseReference equityRef;
    private Query equityQuery;
    private ValueEventListener acctListener;
    private ValueEventListener equityListener;
    private DatabaseReference closedTradesRef;

    private double cash = PaperEngine.DEFAULT_CASH;
    private double startingCash = PaperEngine.DEFAULT_CASH;
    private double fee = 0;
    private final List<Pos> positions = new ArrayList<>();
    private final List<Ord> orders = new ArrayList<>();
    private final List<Trd> trades = new ArrayList<>();
    private final List<double[]> equityPts = new ArrayList<>();
    private final Map<String, PaperEngine.Quote> quotes = new HashMap<>();
    private final Set<String> inFlight = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean polling;
    private boolean pollBusy;
    private long lastEquityWrite;
    private double lastEquityValue = -1;

    // ---- colors ----
    private int cText, cSub, cSurface, cAccent, cGain, cLoss, cChip, cStroke;

    // ---- views ----
    private LinearLayout root;
    private TextView tvEquity, tvDay, tvTotal, tvCash;
    private final MaterialButton[] tabButtons = new MaterialButton[TAB_NAMES.length];
    private final LinearLayout[] tabBodies = new LinearLayout[TAB_NAMES.length];
    private int currentTab = 0;

    // trade tab
    private TextInputEditText etSymbol, etShares, etAmount, etPrice, etTp, etSl;
    private TextInputLayout tilPrice;
    private LinearLayout bracketBox;
    private TextView tvQuote, tvEstimate, tvTradeMsg;
    private MaterialButton btnBuy, btnSell, btnMarket, btnLimit, btnStop, btnSubmit;
    private boolean buySide = true;
    private String orderType = "MARKET";
    private PaperEngine.Quote tradeQuote;

    // stats tab
    private LineChart equityChart;
    private LinearLayout statsBox;

    // what-if tab
    private TextInputEditText etWhatIf;
    private RadioButton rbSequential;
    private ProgressBar progress;
    private TextView tvResult;
    private MaterialButton btnRun;

    private enum Mode { SEQUENTIAL, PARALLEL }

    // ====================================================================================
    // Lifecycle
    // ====================================================================================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_simulator, container, false);
        root = v.findViewById(R.id.simContent);
        Context ctx = requireContext();

        cText = MaterialColors.getColor(ctx,
                com.google.android.material.R.attr.colorOnSurface, Color.BLACK);
        cSub = MaterialColors.getColor(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY);
        cSurface = MaterialColors.getColor(ctx,
                com.google.android.material.R.attr.colorSurface, Color.WHITE);
        cAccent = ContextCompat.getColor(ctx, R.color.pf_22d3ee);
        cStroke = ContextCompat.getColor(ctx, R.color.pf_3322d3ee);
        cGain = ContextCompat.getColor(ctx, R.color.gain);
        cLoss = ContextCompat.getColor(ctx, R.color.loss);
        cChip = MaterialColors.getColor(ctx,
                com.google.android.material.R.attr.colorSurfaceVariant, 0xFFE2E8F0);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            root.addView(text("Please log in to use the simulator.", 15, cSub, false));
            return v;
        }
        DatabaseReference base = FirebaseDatabase.getInstance().getReference("users")
                .child(user.getUid());
        acct = base.child("paper-trading");
        equityRef = base.child("paper-equity");
        closedTradesRef = base.child("closed-trades");

        buildUi();
        PaperEngine.ensureAccount(acct);
        listen();
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (acct != null) {
            polling = true;
            handler.removeCallbacks(pollRunnable);
            handler.post(pollRunnable);
        }
    }

    @Override
    public void onPause() {
        polling = false;
        handler.removeCallbacks(pollRunnable);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        polling = false;
        handler.removeCallbacksAndMessages(null);
        if (acct != null && acctListener != null) acct.removeEventListener(acctListener);
        if (equityQuery != null && equityListener != null) {
            equityQuery.removeEventListener(equityListener);
        }
        acctListener = null;
        equityListener = null;
        super.onDestroyView();
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!polling || !isAdded()) return;
            pollQuotes();
            handler.postDelayed(this, POLL_MS);
        }
    };

    // ====================================================================================
    // Firebase listeners
    // ====================================================================================

    private static double dbl(DataSnapshot s, String key) {
        Double v = s.child(key).getValue(Double.class);
        return v == null ? 0 : v;
    }

    private static String str(DataSnapshot s, String key) {
        String v = s.child(key).getValue(String.class);
        return v == null ? "" : v;
    }

    private void listen() {
        acctListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                if (!isAdded()) return;
                Double c = snap.child("cash").getValue(Double.class);
                cash = c == null ? PaperEngine.DEFAULT_CASH : c;
                Double sc = snap.child("startingCash").getValue(Double.class);
                startingCash = sc == null ? PaperEngine.DEFAULT_CASH : sc;
                fee = dbl(snap, "fee");

                positions.clear();
                for (DataSnapshot item : snap.child("positions").getChildren()) {
                    Pos p = new Pos();
                    p.symbol = str(item, "symbol");
                    p.shares = dbl(item, "shares");
                    p.avg = dbl(item, "averagePrice");
                    p.tp = dbl(item, "tp");
                    p.sl = dbl(item, "sl");
                    if (p.symbol.isEmpty() || p.shares <= 0) continue;
                    positions.add(p);
                }
                orders.clear();
                for (DataSnapshot item : snap.child("orders").getChildren()) {
                    Ord o = new Ord();
                    o.key = item.getKey();
                    o.symbol = str(item, "symbol");
                    o.side = str(item, "side");
                    o.type = str(item, "type");
                    o.shares = dbl(item, "shares");
                    o.price = dbl(item, "price");
                    o.tp = dbl(item, "tp");
                    o.sl = dbl(item, "sl");
                    Long created = item.child("createdAt").getValue(Long.class);
                    o.createdAt = created == null ? 0 : created;
                    if (o.key == null || o.symbol.isEmpty() || o.shares <= 0) continue;
                    orders.add(o);
                }
                trades.clear();
                for (DataSnapshot item : snap.child("trades").getChildren()) {
                    Trd t = new Trd();
                    t.symbol = str(item, "symbol");
                    t.side = str(item, "side");
                    if (t.side.isEmpty()) t.side = str(item, "action");
                    t.type = str(item, "type");
                    t.note = str(item, "note");
                    t.shares = dbl(item, "shares");
                    t.price = dbl(item, "price");
                    t.realized = dbl(item, "realizedPnl");
                    t.fee = dbl(item, "fee");
                    Long at = item.child("executedAt").getValue(Long.class);
                    t.at = at == null ? 0 : at;
                    trades.add(t);
                }
                trades.sort((a, b) -> Long.compare(a.at, b.at));
                renderAll();
                pollQuotes();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                if (isAdded() && tvTradeMsg != null) {
                    tvTradeMsg.setText("Could not load the account: " + error.getMessage());
                }
            }
        };
        acct.addValueEventListener(acctListener);

        equityQuery = equityRef.limitToLast(500);
        equityListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                if (!isAdded()) return;
                equityPts.clear();
                for (DataSnapshot item : snap.getChildren()) {
                    double t = dbl(item, "t");
                    double val = dbl(item, "v");
                    if (t > 0 && val > 0) equityPts.add(new double[]{t, val});
                }
                equityPts.sort((a, b) -> Double.compare(a[0], b[0]));
                renderStats();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) { }
        };
        equityQuery.addValueEventListener(equityListener);
    }

    // ====================================================================================
    // Quotes, triggers, equity
    // ====================================================================================

    private String tradeSymbol() {
        if (etSymbol == null || etSymbol.getText() == null) return "";
        String s = etSymbol.getText().toString().trim().toUpperCase(Locale.US);
        return s.matches("[A-Z0-9.^=_-]{1,20}") ? s : "";
    }

    private void pollQuotes() {
        if (pollBusy || !isAdded()) return;
        Set<String> symbols = new HashSet<>();
        for (Pos p : positions) symbols.add(p.symbol);
        for (Ord o : orders) symbols.add(o.symbol);
        String typed = tradeSymbol();
        if (!typed.isEmpty()) symbols.add(typed);
        if (symbols.isEmpty()) return;
        pollBusy = true;
        PaperEngine.fetchMany(symbols, result -> {
            pollBusy = false;
            if (!isAdded() || getView() == null) return;
            quotes.putAll(result);
            onQuotesUpdated();
        });
    }

    private void onQuotesUpdated() {
        String typed = tradeSymbol();
        if (!typed.isEmpty() && quotes.containsKey(typed)) {
            tradeQuote = quotes.get(typed);
            showTradeQuote();
        }
        checkTriggers();
        maybeWriteEquity();
        renderAll();
    }

    private void checkTriggers() {
        for (Ord o : new ArrayList<>(orders)) {
            PaperEngine.Quote q = quotes.get(o.symbol);
            if (q == null) continue;
            boolean buy = "BUY".equals(o.side);
            boolean limit = "LIMIT".equals(o.type);
            boolean fire;
            if (buy) fire = limit ? q.price <= o.price : q.price >= o.price;
            else fire = limit ? q.price >= o.price : q.price <= o.price;
            final String flight = "O" + o.key;
            if (!fire || !inFlight.add(flight)) continue;
            PaperEngine.trade(acct, o.symbol, o.side, o.shares, q.price, o.type,
                    "Pending " + o.type + " order filled", o.tp, o.sl, o.key, null,
                    (ok, msg) -> {
                        inFlight.remove(flight);
                        toast(msg);
                    });
        }
        for (Pos p : new ArrayList<>(positions)) {
            PaperEngine.Quote q = quotes.get(p.symbol);
            if (q == null) continue;
            String exit = null;
            if (p.tp > 0 && q.price >= p.tp) exit = "TP";
            else if (p.sl > 0 && q.price <= p.sl) exit = "SL";
            final String flight = "P" + p.symbol;
            if (exit == null || !inFlight.add(flight)) continue;
            final boolean tpHit = "TP".equals(exit);
            PaperEngine.trade(acct, p.symbol, "SELL", p.shares, q.price,
                    tpHit ? "TAKE_PROFIT" : "STOP_LOSS",
                    tpHit ? "Take profit hit" : "Stop loss hit", 0, 0, null, exit,
                    (ok, msg) -> {
                        inFlight.remove(flight);
                        toast((tpHit ? "Take profit: " : "Stop loss: ") + msg);
                    });
        }
    }

    private double priceOf(Pos p) {
        PaperEngine.Quote q = quotes.get(p.symbol);
        return q != null ? q.price : p.avg;
    }

    private double equity() {
        double total = cash;
        for (Pos p : positions) total += p.shares * priceOf(p);
        return total;
    }

    private void maybeWriteEquity() {
        if (equityRef == null || positions.isEmpty() && equityPts.size() > 0
                && Math.abs(equity() - lastEquityValue) < 0.005) {
            return;
        }
        double value = equity();
        long now = System.currentTimeMillis();
        boolean changed = Math.abs(value - lastEquityValue) > 0.005;
        if (now - lastEquityWrite < 60_000) return;
        if (!changed && now - lastEquityWrite < 15 * 60_000) return;
        // do not record before every position has a live quote (would draw fake spikes)
        for (Pos p : positions) if (!quotes.containsKey(p.symbol)) return;
        lastEquityWrite = now;
        lastEquityValue = value;
        Map<String, Object> point = new HashMap<>();
        point.put("t", now);
        point.put("v", value);
        equityRef.push().setValue(point);
    }

    // ====================================================================================
    // UI building
    // ====================================================================================

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(requireContext());
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(top);
        return p;
    }

    /** Rounded card added to parent; returns its inner vertical box. */
    private LinearLayout card(LinearLayout parent, int topMargin) {
        MaterialCardView card = new MaterialCardView(requireContext());
        card.setRadius(dp(20));
        card.setCardElevation(0);
        card.setCardBackgroundColor(cSurface);
        card.setStrokeColor(cStroke);
        card.setStrokeWidth(dp(1));
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(card, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, topMargin));
        return box;
    }

    private MaterialButton button(String label, int bg, int fg, View.OnClickListener l) {
        MaterialButton b = new MaterialButton(requireContext());
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setCornerRadius(dp(14));
        b.setBackgroundTintList(ColorStateList.valueOf(bg));
        b.setTextColor(fg);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setMinHeight(dp(42));
        b.setOnClickListener(l);
        return b;
    }

    private void styleToggle(MaterialButton b, boolean on, int onColor) {
        b.setBackgroundTintList(ColorStateList.valueOf(on ? onColor : cChip));
        b.setTextColor(on ? Color.WHITE : cText);
    }

    private TextInputLayout field(String hint, int inputType) {
        TextInputLayout til = new TextInputLayout(requireContext(), null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        til.setHint(hint);
        TextInputEditText et = new TextInputEditText(til.getContext());
        et.setInputType(inputType);
        et.setTextColor(cText);
        et.setSingleLine(true);
        til.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return til;
    }

    private TextInputEditText edit(TextInputLayout til) {
        return (TextInputEditText) til.getEditText();
    }

    private static final int DECIMAL = InputType.TYPE_CLASS_NUMBER
            | InputType.TYPE_NUMBER_FLAG_DECIMAL;

    private void buildUi() {
        buildHeader();
        buildTabBar();
        for (int i = 0; i < TAB_NAMES.length; i++) {
            LinearLayout body = new LinearLayout(requireContext());
            body.setOrientation(LinearLayout.VERTICAL);
            body.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            root.addView(body, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 10));
            tabBodies[i] = body;
        }
        buildTradeTab(tabBodies[0]);
        buildStatsTab(tabBodies[4]);
        buildWhatIfTab(tabBodies[5]);
        renderAll();
    }

    private void buildHeader() {
        LinearLayout titleRow = new LinearLayout(requireContext());
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Trading Simulator", 24, cText, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        MaterialButton settings = button("⚙ Account", cChip, cText,
                x -> showAccountDialog());
        titleRow.addView(settings, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(titleRow);

        TextView sub = text("Practice with virtual money and live prices. "
                + "Nothing here is real.", 12, cSub, false);
        root.addView(sub, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2));

        LinearLayout box = card(root, 12);
        box.addView(text("Total equity", 12, cSub, false));
        tvEquity = text("$0.00", 30, cText, true);
        box.addView(tvEquity);
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        tvDay = text("Today: --", 13, cSub, true);
        tvTotal = text("Total: --", 13, cSub, true);
        row.addView(tvDay, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvTotal, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 6));
        tvCash = text("Cash / buying power: --", 13, cSub, false);
        box.addView(tvCash, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 4));
    }

    private void buildTabBar() {
        HorizontalScrollView scroller = new HorizontalScrollView(requireContext());
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int index = i;
            MaterialButton b = button(TAB_NAMES[i], cChip, cText, x -> selectTab(index));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.rightMargin = dp(8);
            row.addView(b, p);
            tabButtons[i] = b;
        }
        scroller.addView(row);
        root.addView(scroller, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        selectTab(0);
    }

    private void selectTab(int index) {
        currentTab = index;
        for (int i = 0; i < TAB_NAMES.length; i++) {
            styleToggle(tabButtons[i], i == index, cAccent);
            if (tabBodies[i] != null) {
                tabBodies[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            }
        }
        if (index == 4) renderStats();
    }

    // ---- Trade tab ----------------------------------------------------------------------

    private void buildTradeTab(LinearLayout body) {
        LinearLayout box = card(body, 0);

        TextInputLayout tilSymbol = field("Ticker (AAPL, SPY, BTC, GOLD...)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        etSymbol = edit(tilSymbol);
        etSymbol.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        etSymbol.setOnEditorActionListener((v, actionId, event) -> {
            loadTradeQuote();
            return true;
        });
        etSymbol.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) loadTradeQuote();
        });
        box.addView(tilSymbol);

        tvQuote = text("Enter a ticker to see the live price", 13, cSub, false);
        box.addView(tvQuote, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 6));

        // side toggle
        LinearLayout sideRow = new LinearLayout(requireContext());
        sideRow.setOrientation(LinearLayout.HORIZONTAL);
        btnBuy = button("Buy", cChip, cText, x -> setSide(true));
        btnSell = button("Sell", cChip, cText, x -> setSide(false));
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        l1.rightMargin = dp(6);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        l2.leftMargin = dp(6);
        sideRow.addView(btnBuy, l1);
        sideRow.addView(btnSell, l2);
        box.addView(sideRow, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 12));

        // order type
        LinearLayout typeRow = new LinearLayout(requireContext());
        typeRow.setOrientation(LinearLayout.HORIZONTAL);
        btnMarket = button("Market", cChip, cText, x -> setType("MARKET"));
        btnLimit = button("Limit", cChip, cText, x -> setType("LIMIT"));
        btnStop = button("Stop", cChip, cText, x -> setType("STOP"));
        MaterialButton[] typeBtns = {btnMarket, btnLimit, btnStop};
        for (int i = 0; i < typeBtns.length; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) p.leftMargin = dp(6);
            typeRow.addView(typeBtns[i], p);
        }
        box.addView(typeRow, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8));

        TextView hint = text("Market fills at the live price. Limit waits for a better price, "
                + "Stop waits for a breakout. Pending orders are checked every few seconds "
                + "while this screen is open.", 11, cSub, false);
        box.addView(hint, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 6));

        tilPrice = field("Limit price", DECIMAL);
        etPrice = edit(tilPrice);
        box.addView(tilPrice, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 10));

        TextInputLayout tilShares = field("Number of shares", DECIMAL);
        etShares = edit(tilShares);
        box.addView(tilShares, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 10));

        LinearLayout pctRow = new LinearLayout(requireContext());
        pctRow.setOrientation(LinearLayout.HORIZONTAL);
        int[] pcts = {25, 50, 75, 100};
        for (int i = 0; i < pcts.length; i++) {
            final int pct = pcts[i];
            MaterialButton b = button(pct + "%", cChip, cText, x -> fillPercent(pct));
            b.setMinHeight(dp(36));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) p.leftMargin = dp(6);
            pctRow.addView(b, p);
        }
        box.addView(pctRow, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 6));

        TextInputLayout tilAmount = field("...or amount in $ (used when shares is empty)",
                DECIMAL);
        etAmount = edit(tilAmount);
        box.addView(tilAmount, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 10));

        bracketBox = new LinearLayout(requireContext());
        bracketBox.setOrientation(LinearLayout.VERTICAL);
        bracketBox.addView(text("Optional exits (sell automatically)", 12, cSub, true));
        LinearLayout exitRow = new LinearLayout(requireContext());
        exitRow.setOrientation(LinearLayout.HORIZONTAL);
        TextInputLayout tilTp = field("Take profit $", DECIMAL);
        TextInputLayout tilSl = field("Stop loss $", DECIMAL);
        etTp = edit(tilTp);
        etSl = edit(tilSl);
        LinearLayout.LayoutParams e1 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        e1.rightMargin = dp(6);
        LinearLayout.LayoutParams e2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        e2.leftMargin = dp(6);
        exitRow.addView(tilTp, e1);
        exitRow.addView(tilSl, e2);
        bracketBox.addView(exitRow, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 6));
        box.addView(bracketBox, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 10));

        tvEstimate = text("", 13, cText, false);
        box.addView(tvEstimate, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 10));

        btnSubmit = button("Buy", cGain, Color.WHITE, x -> submitOrder());
        btnSubmit.setMinHeight(dp(50));
        box.addView(btnSubmit, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 12));

        tvTradeMsg = text("", 13, cSub, false);
        box.addView(tvTradeMsg, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8));

        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable e) {
                updateEstimate();
            }
        };
        etShares.addTextChangedListener(watcher);
        etAmount.addTextChangedListener(watcher);
        etPrice.addTextChangedListener(watcher);

        setSide(true);
        setType("MARKET");
    }

    private void setSide(boolean buy) {
        buySide = buy;
        styleToggle(btnBuy, buy, cGain);
        styleToggle(btnSell, !buy, cLoss);
        if (bracketBox != null) bracketBox.setVisibility(buy ? View.VISIBLE : View.GONE);
        refreshSubmitLabel();
        updateEstimate();
    }

    private void setType(String type) {
        orderType = type;
        styleToggle(btnMarket, "MARKET".equals(type), cAccent);
        styleToggle(btnLimit, "LIMIT".equals(type), cAccent);
        styleToggle(btnStop, "STOP".equals(type), cAccent);
        tilPrice.setVisibility("MARKET".equals(type) ? View.GONE : View.VISIBLE);
        tilPrice.setHint("LIMIT".equals(type) ? "Limit price" : "Stop (trigger) price");
        refreshSubmitLabel();
        updateEstimate();
    }

    private void refreshSubmitLabel() {
        if (btnSubmit == null) return;
        String sym = tradeSymbol();
        String verb = buySide ? "Buy" : "Sell";
        String kind = "MARKET".equals(orderType) ? "" : orderType.substring(0, 1)
                + orderType.substring(1).toLowerCase(Locale.US) + " order: ";
        btnSubmit.setText(kind + verb + (sym.isEmpty() ? "" : " " + sym));
        btnSubmit.setBackgroundTintList(ColorStateList.valueOf(buySide ? cGain : cLoss));
    }

    private void loadTradeQuote() {
        String sym = tradeSymbol();
        refreshSubmitLabel();
        if (sym.isEmpty()) {
            tradeQuote = null;
            tvQuote.setText("Enter a ticker to see the live price");
            return;
        }
        tvQuote.setText("Loading " + sym + "...");
        PaperEngine.fetchOne(sym, (q, error) -> {
            if (!isAdded() || !sym.equals(tradeSymbol())) return;
            if (q == null) {
                tradeQuote = null;
                tvQuote.setTextColor(cLoss);
                tvQuote.setText(error == null ? "No quote" : error);
                return;
            }
            quotes.put(sym, q);
            tradeQuote = q;
            showTradeQuote();
            updateEstimate();
        });
    }

    private void showTradeQuote() {
        if (tradeQuote == null || tvQuote == null) return;
        int color = tradeQuote.change() >= 0 ? cGain : cLoss;
        tvQuote.setTextColor(color);
        tvQuote.setText(String.format(Locale.US, "%s  $%,.2f   %+.2f (%+.2f%%)",
                tradeQuote.name, tradeQuote.price, tradeQuote.change(),
                tradeQuote.changePct()));
    }

    private double parse(TextInputEditText et) {
        if (et == null || et.getText() == null) return 0;
        String s = et.getText().toString().trim();
        if (s.isEmpty()) return 0;
        try {
            double d = Double.parseDouble(s);
            return Double.isFinite(d) ? d : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Price an order would use right now (limit/stop price, or the live quote). */
    private double referencePrice() {
        if (!"MARKET".equals(orderType)) {
            double p = parse(etPrice);
            if (p > 0) return p;
        }
        return tradeQuote != null ? tradeQuote.price : 0;
    }

    private double resolveShares(double price) {
        double shares = parse(etShares);
        if (shares > 0) return shares;
        double amount = parse(etAmount);
        if (amount > 0 && price > 0) return Math.floor(amount / price * 1e6) / 1e6;
        return 0;
    }

    private void updateEstimate() {
        if (tvEstimate == null) return;
        double price = referencePrice();
        double shares = resolveShares(price);
        if (price <= 0 || shares <= 0) {
            tvEstimate.setText("");
            return;
        }
        double value = shares * price;
        StringBuilder sb = new StringBuilder();
        sb.append(PaperEngine.fmtShares(shares)).append(" x ")
                .append(String.format(Locale.US, "$%,.2f = $%,.2f", price, value));
        if (fee > 0) sb.append(String.format(Locale.US, "  (+ $%.2f fee)", fee));
        if (buySide && value + fee > cash) sb.append("\nMore than your available cash");
        tvEstimate.setText(sb.toString());
    }

    private void fillPercent(int pct) {
        String sym = tradeSymbol();
        double price = referencePrice();
        if (sym.isEmpty() || price <= 0) {
            tvTradeMsg.setText("Enter a ticker and wait for the price first");
            return;
        }
        double shares;
        if (buySide) {
            shares = Math.floor((cash - fee) * pct / 100.0 / price * 1e6) / 1e6;
        } else {
            double held = 0;
            for (Pos p : positions) if (p.symbol.equals(sym)) held = p.shares;
            shares = pct == 100 ? held : Math.floor(held * pct / 100.0 * 1e6) / 1e6;
        }
        if (shares <= 0) {
            tvTradeMsg.setText(buySide ? "No cash available" : "You hold no " + sym);
            return;
        }
        etAmount.setText("");
        etShares.setText(PaperEngine.fmtShares(shares).replace(",", ""));
    }

    private void submitOrder() {
        hideKeyboard(btnSubmit);
        String sym = tradeSymbol();
        if (sym.isEmpty()) {
            etSymbol.setError("Enter a valid ticker");
            return;
        }
        final String side = buySide ? "BUY" : "SELL";
        final double tp = buySide ? parse(etTp) : 0;
        final double sl = buySide ? parse(etSl) : 0;
        if (tp > 0 && sl > 0 && tp <= sl) {
            tvTradeMsg.setTextColor(cLoss);
            tvTradeMsg.setText("Take profit must be above stop loss");
            return;
        }
        final String type = orderType;
        if (!"MARKET".equals(type)) {
            final double trigger = parse(etPrice);
            if (trigger <= 0) {
                etPrice.setError("Enter a price");
                return;
            }
            double shares = resolveShares(trigger);
            if (shares <= 0) {
                tvTradeMsg.setTextColor(cLoss);
                tvTradeMsg.setText("Enter the number of shares (or an amount in $)");
                return;
            }
            setBusy(true);
            PaperEngine.placeOrder(acct, sym, side, type, shares, trigger, tp, sl,
                    (ok, msg) -> {
                        setBusy(false);
                        showTradeMessage(ok, msg);
                        if (ok) {
                            pollQuotes();
                            selectTab(2);
                        }
                    });
            return;
        }
        setBusy(true);
        tvTradeMsg.setTextColor(cSub);
        tvTradeMsg.setText("Getting the live price...");
        PaperEngine.fetchOne(sym, (q, error) -> {
            if (!isAdded()) return;
            if (q == null) {
                setBusy(false);
                showTradeMessage(false, error == null ? "No quote available" : error);
                return;
            }
            quotes.put(sym, q);
            tradeQuote = q;
            showTradeQuote();
            double shares = resolveShares(q.price);
            if (shares <= 0) {
                setBusy(false);
                showTradeMessage(false, "Enter the number of shares (or an amount in $)");
                return;
            }
            PaperEngine.trade(acct, sym, side, shares, q.price, "MARKET", null, tp, sl,
                    null, null, (ok, msg) -> {
                        setBusy(false);
                        showTradeMessage(ok, msg);
                        if (ok) {
                            etShares.setText("");
                            etAmount.setText("");
                        }
                    });
        });
    }

    private void showTradeMessage(boolean ok, String msg) {
        if (tvTradeMsg == null) return;
        tvTradeMsg.setTextColor(ok ? cGain : cLoss);
        tvTradeMsg.setText(msg);
    }

    private void setBusy(boolean busy) {
        if (btnSubmit != null) btnSubmit.setEnabled(!busy);
    }

    // ---- Stats tab (static parts) -------------------------------------------------------

    private void buildStatsTab(LinearLayout body) {
        LinearLayout chartBox = card(body, 0);
        chartBox.addView(text("Equity curve", 15, cText, true));
        equityChart = new LineChart(requireContext());
        equityChart.getDescription().setEnabled(false);
        equityChart.getLegend().setEnabled(false);
        equityChart.setTouchEnabled(false);
        equityChart.setDragEnabled(false);
        equityChart.setScaleEnabled(false);
        equityChart.getAxisRight().setEnabled(false);
        equityChart.getAxisLeft().setTextColor(cSub);
        equityChart.getAxisLeft().setDrawGridLines(true);
        equityChart.getAxisLeft().setGridColor(cStroke);
        XAxis x = equityChart.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setTextColor(cSub);
        x.setDrawGridLines(false);
        x.setLabelCount(4, false);
        chartBox.addView(equityChart, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(220), 8));

        statsBox = card(body, 12);
    }

    // ---- What-if tab ---------------------------------------------------------------------

    private void buildWhatIfTab(LinearLayout body) {
        LinearLayout box = card(body, 0);
        box.addView(text("What if I had invested differently?", 16, cText, true));
        box.addView(text("Replays your real closed trades with an amount you choose.", 12,
                cSub, false), lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2));
        TextInputLayout til = field("Investment amount ($)", DECIMAL);
        etWhatIf = edit(til);
        box.addView(til, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 10));
        RadioGroup group = new RadioGroup(requireContext());
        group.setOrientation(RadioGroup.VERTICAL);
        rbSequential = new RadioButton(requireContext());
        rbSequential.setText("Sequential - reinvest after every trade");
        rbSequential.setTextColor(cText);
        rbSequential.setId(View.generateViewId());
        RadioButton rbParallel = new RadioButton(requireContext());
        rbParallel.setText("Parallel - split equally between all trades");
        rbParallel.setTextColor(cText);
        rbParallel.setId(View.generateViewId());
        group.addView(rbSequential);
        group.addView(rbParallel);
        rbSequential.setChecked(true);
        box.addView(group, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 6));
        btnRun = button("Run simulation", cAccent, Color.WHITE, x -> {
            hideKeyboard(x);
            runSimulation();
        });
        box.addView(btnRun, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8));
        progress = new ProgressBar(requireContext());
        progress.setVisibility(View.GONE);
        box.addView(progress, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8));
        tvResult = text("", 14, cText, false);
        box.addView(tvResult, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8));
    }

    // ====================================================================================
    // Rendering of dynamic tabs
    // ====================================================================================

    private void renderAll() {
        if (!isAdded() || tabBodies[0] == null) return;
        renderHeader();
        renderPortfolio();
        renderOrders();
        renderHistory();
        renderStats();
        updateEstimate();
        refreshTabTitles();
    }

    private void refreshTabTitles() {
        tabButtons[1].setText(positions.isEmpty() ? "Portfolio"
                : "Portfolio (" + positions.size() + ")");
        tabButtons[2].setText(orders.isEmpty() ? "Orders" : "Orders (" + orders.size() + ")");
    }

    private static String money(double v) {
        return String.format(Locale.US, "$%,.2f", v);
    }

    private static String signedMoney(double v) {
        return String.format(Locale.US, "%s$%,.2f", v >= 0 ? "+" : "-", Math.abs(v));
    }

    private void renderHeader() {
        double eq = equity();
        tvEquity.setText(money(eq));
        double day = 0;
        for (Pos p : positions) {
            PaperEngine.Quote q = quotes.get(p.symbol);
            if (q != null && q.prevClose > 0) day += p.shares * (q.price - q.prevClose);
        }
        tvDay.setText("Today: " + signedMoney(day));
        tvDay.setTextColor(day >= 0 ? cGain : cLoss);
        double total = eq - startingCash;
        double totalPct = startingCash > 0 ? total / startingCash * 100.0 : 0;
        tvTotal.setText(String.format(Locale.US, "Total: %s (%+.2f%%)", signedMoney(total),
                totalPct));
        tvTotal.setTextColor(total >= 0 ? cGain : cLoss);
        tvCash.setText("Cash / buying power: " + money(cash));
    }

    private void emptyNote(LinearLayout body, String message) {
        LinearLayout box = card(body, 0);
        box.addView(text(message, 14, cSub, false));
    }

    private void renderPortfolio() {
        LinearLayout body = tabBodies[1];
        body.removeAllViews();
        if (positions.isEmpty()) {
            emptyNote(body, "No open positions. Use the Trade tab to place your first order.");
            return;
        }
        double invested = 0;
        double marketValue = 0;
        for (Pos p : positions) {
            invested += p.shares * p.avg;
            marketValue += p.shares * priceOf(p);
        }
        double totalUnreal = marketValue - invested;
        LinearLayout sum = card(body, 0);
        sum.addView(text(String.format(Locale.US, "Positions value %s  ·  Unrealized %s",
                money(marketValue), signedMoney(totalUnreal)), 14,
                totalUnreal >= 0 ? cGain : cLoss, true));

        for (Pos p : positions) {
            final Pos pos = p;
            PaperEngine.Quote q = quotes.get(p.symbol);
            double price = q != null ? q.price : p.avg;
            double value = p.shares * price;
            double pnl = (price - p.avg) * p.shares;
            double pnlPct = p.avg > 0 ? (price - p.avg) / p.avg * 100.0 : 0;
            double weight = marketValue > 0 ? value / marketValue * 100.0 : 0;

            LinearLayout box = card(body, 10);
            LinearLayout top = new LinearLayout(requireContext());
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.addView(text(p.symbol, 18, cText, true), new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            top.addView(text(String.format(Locale.US, "%s (%+.2f%%)", signedMoney(pnl), pnlPct),
                    15, pnl >= 0 ? cGain : cLoss, true));
            box.addView(top);
            box.addView(text(String.format(Locale.US,
                    "%s shares  ·  avg $%,.2f  ·  last $%,.2f%s",
                    PaperEngine.fmtShares(p.shares), p.avg, price,
                    q == null ? " (waiting for quote)" : ""), 12, cSub, false),
                    lp(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 2));
            box.addView(text(String.format(Locale.US, "Value %s  ·  %.1f%% of positions",
                    money(value), weight), 12, cSub, false),
                    lp(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 2));
            if (p.tp > 0 || p.sl > 0) {
                StringBuilder sb = new StringBuilder();
                if (p.tp > 0) sb.append(String.format(Locale.US, "Take profit $%,.2f", p.tp));
                if (p.tp > 0 && p.sl > 0) sb.append("   ");
                if (p.sl > 0) sb.append(String.format(Locale.US, "Stop loss $%,.2f", p.sl));
                box.addView(text(sb.toString(), 12, cAccent, true),
                        lp(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT, 4));
            }
            LinearLayout actions = new LinearLayout(requireContext());
            actions.setOrientation(LinearLayout.HORIZONTAL);
            MaterialButton close = button("Close all", cLoss, Color.WHITE,
                    x -> closePosition(pos, 1.0));
            MaterialButton half = button("Sell 50%", cChip, cText,
                    x -> closePosition(pos, 0.5));
            MaterialButton exits = button("TP / SL", cChip, cText, x -> showBracketDialog(pos));
            MaterialButton add = button("Trade", cChip, cText, x -> openInTrade(pos.symbol));
            MaterialButton[] bs = {close, half, exits, add};
            for (int i = 0; i < bs.length; i++) {
                bs[i].setMinHeight(dp(38));
                bs[i].setTextSize(12);
                LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (i > 0) ap.leftMargin = dp(4);
                actions.addView(bs[i], ap);
            }
            box.addView(actions, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 10));
        }
    }

    private void openInTrade(String symbol) {
        etSymbol.setText(symbol);
        etShares.setText("");
        etAmount.setText("");
        selectTab(0);
        loadTradeQuote();
    }

    private void closePosition(Pos pos, double fraction) {
        final String symbol = pos.symbol;
        toast("Closing " + symbol + "...");
        PaperEngine.fetchOne(symbol, (q, error) -> {
            if (!isAdded()) return;
            if (q == null) {
                toast(error == null ? "No quote available" : error);
                return;
            }
            double held = 0;
            for (Pos p : positions) if (p.symbol.equals(symbol)) held = p.shares;
            double shares = fraction >= 1.0 ? held
                    : Math.floor(held * fraction * 1e6) / 1e6;
            if (shares <= 0) {
                toast("Nothing to sell");
                return;
            }
            PaperEngine.trade(acct, symbol, "SELL", shares, q.price, "MARKET", null, 0, 0,
                    null, null, (ok, msg) -> toast(msg));
        });
    }

    private void showBracketDialog(Pos pos) {
        Context ctx = requireContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), 0);
        TextInputLayout tilTp = field("Take profit price (empty = none)", DECIMAL);
        TextInputLayout tilSl = field("Stop loss price (empty = none)", DECIMAL);
        TextInputEditText tp = edit(tilTp);
        TextInputEditText sl = edit(tilSl);
        if (pos.tp > 0) tp.setText(String.valueOf(pos.tp));
        if (pos.sl > 0) sl.setText(String.valueOf(pos.sl));
        box.addView(tilTp);
        box.addView(tilSl, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8));
        new AlertDialog.Builder(ctx)
                .setTitle(pos.symbol + " - exits")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    double t = parse(tp);
                    double s = parse(sl);
                    if (t > 0 && s > 0 && t <= s) {
                        toast("Take profit must be above stop loss");
                        return;
                    }
                    PaperEngine.setBracket(acct, pos.symbol, t, s, (ok, msg) -> toast(msg));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void renderOrders() {
        LinearLayout body = tabBodies[2];
        body.removeAllViews();
        if (orders.isEmpty()) {
            emptyNote(body, "No pending orders. Place a Limit or Stop order from the Trade tab "
                    + "- it executes automatically when the price is reached (checked every few "
                    + "seconds while this screen is open).");
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("dd MMM HH:mm", Locale.getDefault());
        boolean first = true;
        for (Ord o : orders) {
            final Ord ord = o;
            LinearLayout box = card(body, first ? 0 : 10);
            first = false;
            boolean buy = "BUY".equals(o.side);
            box.addView(text(String.format(Locale.US, "%s %s  %s %s", o.type, o.side,
                    PaperEngine.fmtShares(o.shares), o.symbol), 16, buy ? cGain : cLoss, true));
            PaperEngine.Quote q = quotes.get(o.symbol);
            String now = q == null ? "" : String.format(Locale.US, "  ·  now $%,.2f", q.price);
            box.addView(text(String.format(Locale.US, "Trigger $%,.2f%s", o.price, now), 13,
                    cText, false), lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 2));
            if (o.tp > 0 || o.sl > 0) {
                box.addView(text(String.format(Locale.US, "Exits: TP %s  SL %s",
                        o.tp > 0 ? money(o.tp) : "-", o.sl > 0 ? money(o.sl) : "-"), 12, cAccent,
                        false), lp(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 2));
            }
            if (o.createdAt > 0) {
                box.addView(text("Placed " + fmt.format(new Date(o.createdAt)), 11, cSub, false));
            }
            MaterialButton cancel = button("Cancel order", cChip, cText,
                    x -> PaperEngine.cancelOrder(acct, ord.key));
            cancel.setMinHeight(dp(38));
            box.addView(cancel, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 8));
        }
    }

    private static String typeLabel(String type, String side) {
        if ("TAKE_PROFIT".equals(type)) return "Take profit";
        if ("STOP_LOSS".equals(type)) return "Stop loss";
        if ("LIMIT".equals(type)) return "Limit";
        if ("STOP".equals(type)) return "Stop";
        return "Market";
    }

    private void renderHistory() {
        LinearLayout body = tabBodies[3];
        body.removeAllViews();
        if (trades.isEmpty()) {
            emptyNote(body, "No trades yet.");
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault());
        LinearLayout box = card(body, 0);
        int shown = 0;
        for (int i = trades.size() - 1; i >= 0 && shown < 100; i--, shown++) {
            Trd t = trades.get(i);
            boolean buy = "BUY".equals(t.side);
            if (shown > 0) {
                View line = new View(requireContext());
                line.setBackgroundColor(cStroke);
                box.addView(line, lp(ViewGroup.LayoutParams.MATCH_PARENT, 1, 8));
            }
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(shown > 0 ? 8 : 0), 0, 0);
            LinearLayout left = new LinearLayout(requireContext());
            left.setOrientation(LinearLayout.VERTICAL);
            left.addView(text(String.format(Locale.US, "%s %s %s", t.side,
                    PaperEngine.fmtShares(t.shares), t.symbol), 14, buy ? cGain : cLoss, true));
            left.addView(text(String.format(Locale.US, "@ $%,.2f  ·  %s%s", t.price,
                    typeLabel(t.type, t.side), t.fee > 0
                            ? String.format(Locale.US, "  ·  fee $%.2f", t.fee) : ""), 12,
                    cSub, false));
            if (t.at > 0) left.addView(text(fmt.format(new Date(t.at)), 11, cSub, false));
            row.addView(left, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (!buy) {
                row.addView(text(signedMoney(t.realized), 14, t.realized >= 0 ? cGain : cLoss,
                        true));
            }
            box.addView(row);
        }
    }

    private void renderStats() {
        if (statsBox == null || equityChart == null) return;
        statsBox.removeAllViews();

        // ---- equity chart ----
        List<Entry> entries = new ArrayList<>();
        final List<Long> times = new ArrayList<>();
        for (int i = 0; i < equityPts.size(); i++) {
            double[] pt = equityPts.get(i);
            entries.add(new Entry(i, (float) pt[1]));
            times.add((long) pt[0]);
        }
        if (entries.size() >= 2) {
            LineDataSet set = new LineDataSet(entries, "Equity");
            set.setColor(cAccent);
            set.setLineWidth(2f);
            set.setDrawCircles(false);
            set.setDrawValues(false);
            set.setDrawFilled(true);
            set.setFillColor(cAccent);
            set.setFillAlpha(40);
            set.setMode(LineDataSet.Mode.LINEAR);
            equityChart.setData(new LineData(set));
            final SimpleDateFormat f = new SimpleDateFormat("dd MMM HH:mm", Locale.getDefault());
            equityChart.getXAxis().setValueFormatter(new ValueFormatter() {
                @Override
                public String getFormattedValue(float value) {
                    int i = Math.round(value);
                    if (i < 0 || i >= times.size()) return "";
                    return f.format(new Date(times.get(i)));
                }
            });
            equityChart.setVisibility(View.VISIBLE);
            equityChart.invalidate();
        } else {
            equityChart.clear();
            equityChart.setNoDataText("The curve builds up as you trade and keep the screen open");
            equityChart.setNoDataTextColor(cSub);
            equityChart.invalidate();
        }

        // ---- metrics ----
        int closed = 0, wins = 0, losses = 0;
        double sumWin = 0, sumLoss = 0, realized = 0, fees = 0;
        double best = Double.NEGATIVE_INFINITY, worst = Double.POSITIVE_INFINITY;
        String bestSym = "", worstSym = "";
        for (Trd t : trades) {
            fees += t.fee;
            if (!"SELL".equals(t.side)) continue;
            closed++;
            realized += t.realized;
            if (t.realized > 0) {
                wins++;
                sumWin += t.realized;
            } else if (t.realized < 0) {
                losses++;
                sumLoss += t.realized;
            }
            if (t.realized > best) {
                best = t.realized;
                bestSym = t.symbol;
            }
            if (t.realized < worst) {
                worst = t.realized;
                worstSym = t.symbol;
            }
        }
        double unrealized = 0;
        for (Pos p : positions) unrealized += (priceOf(p) - p.avg) * p.shares;
        double maxDd = 0;
        double peak = 0;
        for (double[] pt : equityPts) {
            peak = Math.max(peak, pt[1]);
            if (peak > 0) maxDd = Math.max(maxDd, (peak - pt[1]) / peak * 100.0);
        }
        double eq = equity();

        statsBox.addView(text("Performance", 15, cText, true));
        statRow("Starting capital", money(startingCash), cText);
        statRow("Current equity", money(eq), cText);
        double total = eq - startingCash;
        statRow("Total return", String.format(Locale.US, "%s (%+.2f%%)", signedMoney(total),
                startingCash > 0 ? total / startingCash * 100.0 : 0), total >= 0 ? cGain : cLoss);
        statRow("Realized P&L", signedMoney(realized), realized >= 0 ? cGain : cLoss);
        statRow("Unrealized P&L", signedMoney(unrealized), unrealized >= 0 ? cGain : cLoss);
        statRow("Fees paid", money(fees), cText);
        statRow("Closed trades", String.valueOf(closed), cText);
        statRow("Win rate", closed == 0 ? "--" : String.format(Locale.US, "%.0f%% (%d W / %d L)",
                wins * 100.0 / closed, wins, losses), cText);
        statRow("Average win", wins == 0 ? "--" : signedMoney(sumWin / wins), cGain);
        statRow("Average loss", losses == 0 ? "--" : signedMoney(sumLoss / losses), cLoss);
        statRow("Profit factor", sumLoss < 0 ? String.format(Locale.US, "%.2f", sumWin
                / Math.abs(sumLoss)) : (sumWin > 0 ? "∞" : "--"), cText);
        statRow("Best trade", closed == 0 ? "--" : signedMoney(best) + " " + bestSym, cGain);
        statRow("Worst trade", closed == 0 ? "--" : signedMoney(worst) + " " + worstSym, cLoss);
        statRow("Max drawdown", equityPts.size() < 2 ? "--"
                : String.format(Locale.US, "%.2f%%", maxDd), cText);
    }

    private void statRow(String label, String value, int valueColor) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(text(label, 13, cSub, false), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(text(value, 13, valueColor, true));
        statsBox.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8));
    }

    // ====================================================================================
    // Account dialog
    // ====================================================================================

    private void showAccountDialog() {
        Context ctx = requireContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), 0);
        TextInputLayout tilCap = field("Starting capital ($)", DECIMAL);
        TextInputLayout tilFee = field("Commission per trade ($)", DECIMAL);
        TextInputEditText cap = edit(tilCap);
        TextInputEditText feeEt = edit(tilFee);
        cap.setText(String.format(Locale.US, "%.0f", startingCash));
        feeEt.setText(fee > 0 ? String.valueOf(fee) : "0");
        box.addView(text("Saving a new starting capital RESETS the account: positions, orders "
                + "and history are erased. The commission can be changed without a reset.", 12,
                cSub, false));
        box.addView(tilCap, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 10));
        box.addView(tilFee, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8));
        new AlertDialog.Builder(ctx)
                .setTitle("Simulator account")
                .setView(box)
                .setPositiveButton("Reset account", (d, w) -> {
                    double capital = parse(cap);
                    if (capital < 100 || capital > 1_000_000_000d) {
                        toast("Starting capital must be between $100 and $1,000,000,000");
                        return;
                    }
                    final double newFee = Math.max(0, parse(feeEt));
                    new AlertDialog.Builder(ctx)
                            .setTitle("Reset the simulator?")
                            .setMessage("All positions, orders, history and the equity curve "
                                    + "will be deleted. This cannot be undone.")
                            .setPositiveButton("Reset", (d2, w2) ->
                                    PaperEngine.reset(acct, equityRef, capital, newFee,
                                            (ok, msg) -> {
                                                lastEquityValue = -1;
                                                lastEquityWrite = 0;
                                                toast(msg);
                                            }))
                            .setNegativeButton("Cancel", null)
                            .show();
                })
                .setNeutralButton("Save commission", (d, w) -> {
                    PaperEngine.setFee(acct, Math.max(0, parse(feeEt)));
                    toast("Commission saved");
                })
                .setNegativeButton("Close", null)
                .show();
    }

    // ====================================================================================
    // What-if simulation over real closed trades (unchanged logic)
    // ====================================================================================

    private void runSimulation() {
        double amount = parse(etWhatIf);
        if (amount <= 0) {
            etWhatIf.setError("Enter an amount greater than 0");
            return;
        }
        final Mode mode = rbSequential.isChecked() ? Mode.SEQUENTIAL : Mode.PARALLEL;
        setLoading(true);
        tvResult.setText("");
        closedTradesRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<StockData> list = new ArrayList<>();
                for (DataSnapshot ds : snapshot.getChildren()) {
                    StockData data = ds.getValue(StockData.class);
                    if (data != null && data.buyPrice > 0 && data.sellPrice > 0) list.add(data);
                }
                list.sort((a, b) -> Long.compare(tradeDate(a), tradeDate(b)));
                setLoading(false);
                if (!isAdded()) return;
                if (list.isEmpty()) {
                    tvResult.setTextColor(cSub);
                    tvResult.setText("No closed trades with price data found.\n"
                            + "Close a trade to use this tool.");
                    return;
                }
                showResult(simulate(list, amount, mode), mode);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                setLoading(false);
                toast("Error loading trades: " + error.getMessage());
            }
        });
    }

    private long tradeDate(StockData trade) {
        return trade.closedAt > 0 ? trade.closedAt : trade.openedAt;
    }

    private double[] simulate(List<StockData> list, double start, Mode mode) {
        int n = list.size();
        double fin;
        if (mode == Mode.SEQUENTIAL) {
            fin = start;
            for (StockData t : list) fin = fin * (1.0 + safeReturn(returnFraction(t)));
        } else {
            double per = start / n;
            fin = 0;
            for (StockData t : list) fin += per * (1.0 + safeReturn(returnFraction(t)));
        }
        return new double[]{start, fin, n};
    }

    private double returnFraction(StockData t) {
        if (t == null || t.buyPrice <= 0 || t.sellPrice <= 0) return 0.0;
        return (t.sellPrice - t.buyPrice) / t.buyPrice;
    }

    private static double safeReturn(double r) {
        if (Double.isNaN(r) || Double.isInfinite(r)) return 0.0;
        return Math.max(r, -1.0);
    }

    private void showResult(double[] r, Mode mode) {
        double start = r[0];
        double fin = r[1];
        double diff = fin - start;
        boolean profit = diff >= 0;
        double pct = start == 0 ? 0 : diff / start * 100.0;
        tvResult.setText(String.format(Locale.US,
                "%s\nMode: %s\nTrades: %d\nStarting amount: $%,.2f\nFinal amount: $%,.2f\n"
                        + "%s: %s\nReturn: %+.2f%%",
                profit ? "You would have profited!" : "You would have lost!",
                mode == Mode.SEQUENTIAL ? "Sequential" : "Parallel (equal split)",
                (int) r[2], start, fin, profit ? "Profit" : "Loss", signedMoney(diff), pct));
        tvResult.setTextColor(profit ? cGain : cLoss);
    }

    private void setLoading(boolean loading) {
        if (progress != null) progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (btnRun != null) btnRun.setEnabled(!loading);
    }

    // ====================================================================================
    // Helpers
    // ====================================================================================

    private void toast(String msg) {
        if (isAdded() && !TextUtils.isEmpty(msg)) {
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
        }
    }

    private void hideKeyboard(View view) {
        Context ctx = getContext();
        if (ctx == null) return;
        InputMethodManager imm = (InputMethodManager) ctx.getSystemService(
                Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }
}
