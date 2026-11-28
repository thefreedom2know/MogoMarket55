package com.mogomarket.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.card.MaterialCardView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.FirebaseFunctionsException;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * "קופות גמל ופנסיה": the user's long-term savings next to the stock portfolio.
 * Funds come from a הר הכסף Excel export or are added by fund number; returns, fees and the
 * comparison with similar funds come from the server (functions/funds.js).
 */
public class FundsFragment extends Fragment {

    public static final String PREF_PORTFOLIO_VALUE_USD = "portfolio_value_usd";
    public static final String PREF_FUNDS_TOTAL_ILS = "funds_total_ils";
    private static final String PREF_USD_ILS = "usd_ils_rate";
    private static final long STALE_MS = 3L * 24 * 60 * 60 * 1000;

    private static final int[] PALETTE = {
            0xFF22D3EE, 0xFF34D399, 0xFFFFB84D, 0xFFA78BFA, 0xFFF472B6,
            0xFF60A5FA, 0xFFFB923C, 0xFF4ADE80, 0xFFE879F9, 0xFF94A3B8
    };

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<SavingsFund> funds = new ArrayList<>();

    private DatabaseReference fundsRef;
    private ValueEventListener fundsListener;
    private boolean autoRefreshDone;
    private boolean refreshing;
    private double usdIls = Double.NaN;

    private TextView tvTotal, tvAsOf, tvStocksIls, tvCombined, tvExposure, tvFeeAlert, tvEmpty;
    private View cardFeeAlert, allocationSection;
    private FundAllocationBarView allocationBar;
    private LinearLayout allocationLegend, list;
    private TextView btnRefresh;

    private final ActivityResultLauncher<String[]> pickFile =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::importFile);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_funds, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tvTotal = view.findViewById(R.id.tvFundsTotal);
        tvAsOf = view.findViewById(R.id.tvFundsAsOf);
        tvStocksIls = view.findViewById(R.id.tvStocksIls);
        tvCombined = view.findViewById(R.id.tvCombinedTotal);
        tvExposure = view.findViewById(R.id.tvWeightedExposure);
        tvFeeAlert = view.findViewById(R.id.tvFeeAlert);
        tvEmpty = view.findViewById(R.id.tvFundsEmpty);
        cardFeeAlert = view.findViewById(R.id.cardFeeAlert);
        allocationSection = view.findViewById(R.id.allocationSection);
        allocationBar = view.findViewById(R.id.fundAllocationBar);
        allocationLegend = view.findViewById(R.id.allocationLegend);
        list = view.findViewById(R.id.fundsList);
        btnRefresh = view.findViewById(R.id.btnFundsRefresh);

        btnRefresh.setOnClickListener(v -> refreshFromServer(false));
        view.findViewById(R.id.btnAddFund).setOnClickListener(v -> showEditDialog(null));
        view.findViewById(R.id.btnImportHarHakesef).setOnClickListener(v -> showImportHelp());

        SharedPreferences prefs = prefs();
        float cachedRate = prefs.getFloat(PREF_USD_ILS, 0f);
        if (cachedRate > 0) usdIls = cachedRate;
        loadUsdIls();

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            tvEmpty.setText("כדי לשמור קופות צריך להתחבר לחשבון.");
            return;
        }
        fundsRef = FirebaseDatabase.getInstance().getReference("users")
                .child(user.getUid()).child("funds");
        fundsListener = new ValueEventListener() {
            @Override
            @SuppressWarnings("unchecked")
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                funds.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    Object value = child.getValue();
                    if (value instanceof Map) {
                        funds.add(SavingsFund.fromMap(child.getKey(), (Map<String, Object>) value));
                    }
                }
                funds.sort((a, b) -> Double.compare(b.currentValue(), a.currentValue()));
                render();
                maybeAutoRefresh();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                toast("לא ניתן לקרוא את הקופות: " + error.getMessage());
            }
        };
        fundsRef.addValueEventListener(fundsListener);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (fundsRef != null && fundsListener != null) fundsRef.removeEventListener(fundsListener);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private SharedPreferences prefs() {
        return requireContext().getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ---- screen -----------------------------------------------------------------------------

    private void render() {
        if (getView() == null) return;
        double total = 0;
        int latestPeriod = 0;
        double exposureWeight = 0, stockSum = 0, foreignSum = 0;
        Map<String, Double> byTrack = new LinkedHashMap<>();
        List<String> feeAlerts = new ArrayList<>();
        for (SavingsFund f : funds) {
            double value = f.currentValue();
            total += value;
            byTrack.merge(f.trackLabel(), value, Double::sum);
            if (f.market != null) {
                latestPeriod = Math.max(latestPeriod, f.market.estimatedPeriod > 0
                        ? f.market.estimatedPeriod : f.market.period);
                if (!Double.isNaN(f.market.stockExposure) && value > 0) {
                    exposureWeight += value;
                    stockSum += value * f.market.stockExposure;
                    foreignSum += value * (Double.isNaN(f.market.foreignExposure) ? 0 : f.market.foreignExposure);
                }
            }
            if (f.feeAboveAverage()) {
                feeAlerts.add(String.format(Locale.US, "• %s: %.2f%% (ממוצע במסלול %.2f%%)",
                        f.displayName(), f.effectiveFee(), f.market.peerAvgFee));
            }
        }
        prefs().edit().putFloat(PREF_FUNDS_TOTAL_ILS, (float) total).apply();

        tvTotal.setText(ils(total));
        tvAsOf.setText(funds.isEmpty() ? "" : latestPeriod > 0
                ? funds.size() + " קופות · הערכה לפי נתוני " + SavingsFund.periodLabel(latestPeriod)
                : funds.size() + " קופות · לפי היתרות שהוזנו");

        double stocksUsd = prefs().getFloat(PREF_PORTFOLIO_VALUE_USD, 0f);
        if (stocksUsd > 0 && !Double.isNaN(usdIls)) {
            double stocksIls = stocksUsd * usdIls;
            tvStocksIls.setText(String.format(Locale.US, "תיק המניות: $%,.0f ≈ %s (שער %.3f)",
                    stocksUsd, ils(stocksIls), usdIls));
            tvCombined.setText("סה\"כ כולל מניות: " + ils(total + stocksIls));
            if (total + stocksIls > 0) {
                tvCombined.append(String.format(Locale.US, "\nחיסכון ארוך טווח %.0f%% · מניות %.0f%%",
                        total / (total + stocksIls) * 100, stocksIls / (total + stocksIls) * 100));
            }
        } else if (stocksUsd > 0) {
            tvStocksIls.setText(String.format(Locale.US, "תיק המניות: $%,.0f (ממתין לשער הדולר)", stocksUsd));
            tvCombined.setText("");
        } else {
            tvStocksIls.setText("תיק המניות יתווסף כאן אחרי פתיחת מסך הפורטפוליו.");
            tvCombined.setText("");
        }
        tvExposure.setText(exposureWeight > 0 ? String.format(Locale.US,
                "חשיפה משוקללת של הקופות: מניות %.0f%% · חו\"ל %.0f%%",
                stockSum / exposureWeight, foreignSum / exposureWeight) : "");

        cardFeeAlert.setVisibility(feeAlerts.isEmpty() ? View.GONE : View.VISIBLE);
        if (!feeAlerts.isEmpty()) {
            tvFeeAlert.setText("⚠ דמי ניהול גבוהים מהממוצע במסלול:\n" + String.join("\n", feeAlerts)
                    + "\nאפשר לבקש מהקופה הפחתה, או לשקול מעבר לקופה זולה יותר באותו מסלול.");
        }

        renderAllocation(byTrack, total);
        tvEmpty.setVisibility(funds.isEmpty() ? View.VISIBLE : View.GONE);

        list.removeAllViews();
        for (SavingsFund f : funds) list.addView(fundCard(f));
    }

    private void renderAllocation(Map<String, Double> byTrack, double total) {
        allocationSection.setVisibility(total > 0 && funds.size() > 0 ? View.VISIBLE : View.GONE);
        if (total <= 0) return;
        List<Map.Entry<String, Double>> entries = new ArrayList<>(byTrack.entrySet());
        entries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        double[] values = new double[entries.size()];
        int[] colors = new int[entries.size()];
        allocationLegend.removeAllViews();
        for (int i = 0; i < entries.size(); i++) {
            values[i] = entries.get(i).getValue();
            colors[i] = PALETTE[i % PALETTE.length];
            TextView row = text(String.format(Locale.US, "●  %s  %.1f%% · %s", entries.get(i).getKey(),
                    values[i] / total * 100, ils(values[i])), 12, R.color.pf_e6ddf4, false);
            row.setTextColor(colors[i]);
            allocationLegend.addView(row);
        }
        allocationBar.setParts(values, colors);
    }

    private View fundCard(SavingsFund f) {
        Context ctx = requireContext();
        MaterialCardView card = new MaterialCardView(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);
        card.setCardBackgroundColor(color(R.color.pf_0c1018));
        card.setRadius(dp(16));
        card.setCardElevation(0);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(f.feeAboveAverage() ? color(R.color.pf_ff7279) : 0x3322D3EE);
        card.setOnClickListener(v -> showEditDialog(f));

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        card.addView(box);

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = text(f.displayName(), 15, R.color.pf_ffffff, true);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(name);
        top.addView(text(ils(f.currentValue()), 15, R.color.pf_ffffff, true));
        box.addView(top);

        StringBuilder sub = new StringBuilder(SavingsFund.kindLabel(f.kind));
        String company = !f.company.isEmpty() ? f.company : f.market != null ? f.market.company : "";
        if (!company.isEmpty()) sub.append(" · ").append(company);
        sub.append(" · ").append(f.trackLabel());
        if (f.fundId > 0) sub.append(" · #").append(f.fundId);
        if (!f.active) sub.append(" · לא פעילה");
        box.addView(text(sub.toString(), 12, R.color.pf_8d9aae, false));

        SavingsFund.Market m = f.market;
        if (f.fundId <= 0) {
            box.addView(text("אין מספר קופה, ולכן אין תשואות והשוואה. לחצו כדי לחפש את הקופה ולקשר אותה.",
                    12, R.color.pf_ffb84d, false));
        } else if (m == null) {
            box.addView(text("ממתין לנתונים מהשרת. לחצו על \"רענן נתונים\".", 12, R.color.pf_ffb84d, false));
        } else if (m.notFound) {
            box.addView(text("מספר הקופה " + f.fundId + " לא נמצא בגמל-נט, פנסיה-נט או ביטוח-נט. בדקו את המספר.",
                    12, R.color.pf_ff7279, false));
        } else if (!m.error.isEmpty() && m.period == 0) {
            box.addView(text("העדכון נכשל: " + m.error, 12, R.color.pf_ff7279, false));
        } else {
            box.addView(text("תשואה: " + joinParts(
                    pct("חודש " + SavingsFund.periodLabel(m.period), m.monthlyYield),
                    pct("מתחילת השנה", m.ytdYield),
                    pct("12 חודשים", m.return12m),
                    pct("ממוצע שנתי 3 שנים", m.avgAnnual3y)), 12, R.color.pf_e6ddf4, false));

            if (m.peerCount > 0) {
                String rank = m.rank3y > 0 && m.ranked3y > 0
                        ? String.format(Locale.US, " · מקום %d מתוך %d", m.rank3y, m.ranked3y) : "";
                TextView peers = text("מול " + m.peerCount + " קופות במסלול: " + joinParts(
                        pct("מתחילת השנה", m.peerAvgYtd), pct("3 שנים", m.peerAvgAnnual3y)) + rank,
                        12, R.color.pf_8d9aae, false);
                if (!Double.isNaN(m.avgAnnual3y) && !Double.isNaN(m.peerAvgAnnual3y)) {
                    peers.setTextColor(m.avgAnnual3y >= m.peerAvgAnnual3y
                            ? color(R.color.pf_34d399) : color(R.color.pf_ff7279));
                }
                box.addView(peers);
            }
            String index = joinParts(pct("ת\"א 125, 12 ח'", m.ta125R12m), pct("S&P 500 ($), 12 ח'", m.sp500R12m));
            if (!index.isEmpty()) box.addView(text("מדדים: " + index, 12, R.color.pf_8d9aae, false));
        }

        double fee = f.effectiveFee();
        if (!Double.isNaN(fee)) {
            String feeText = String.format(Locale.US, "דמי ניהול מצבירה %.2f%%%s", fee,
                    Double.isNaN(f.feeBalance) ? " (ממוצע הקופה)" : "");
            if (!Double.isNaN(f.feeDeposit)) feeText += String.format(Locale.US, " · מהפקדה %.2f%%", f.feeDeposit);
            if (m != null && !Double.isNaN(m.peerAvgFee)) {
                feeText += String.format(Locale.US, " · ממוצע במסלול %.2f%%", m.peerAvgFee);
            }
            if (f.feeAboveAverage()) feeText = "⚠ " + feeText;
            box.addView(text(feeText, 12, f.feeAboveAverage() ? R.color.pf_ff7279 : R.color.pf_8d9aae, false));
        }
        if (f.balancePeriod > 0 && m != null && m.estimatedPeriod > f.balancePeriod) {
            box.addView(text(String.format(Locale.US, "יתרה שהוזנה %s (%s), מוערכת עד %s",
                    ils(f.balance), SavingsFund.periodLabel(f.balancePeriod),
                    SavingsFund.periodLabel(m.estimatedPeriod)), 11, R.color.pf_657184, false));
        }
        return card;
    }

    // ---- server -----------------------------------------------------------------------------

    private void maybeAutoRefresh() {
        if (autoRefreshDone) return;
        long now = System.currentTimeMillis();
        for (SavingsFund f : funds) {
            if (f.fundId <= 0) continue;
            long at = f.market == null ? 0 : Math.max(f.market.updatedAt, f.market.checkedAt);
            if (now - at > STALE_MS) {
                autoRefreshDone = true;
                refreshFromServer(true);
                return;
            }
        }
        autoRefreshDone = true;
    }

    private void refreshFromServer(boolean silent) {
        if (refreshing || fundsRef == null) return;
        refreshing = true;
        btnRefresh.setText("מעדכן…");
        FirebaseFunctions.getInstance().getHttpsCallable("fundsRefresh")
                .withTimeout(300, TimeUnit.SECONDS)
                .call(new HashMap<String, Object>())
                .addOnCompleteListener(task -> {
                    refreshing = false;
                    if (getView() == null) return;
                    btnRefresh.setText("רענן נתונים");
                    if (task.isSuccessful()) {
                        Object data = task.getResult().getData();
                        if (!silent && data instanceof Map) {
                            Map<?, ?> r = (Map<?, ?>) data;
                            toast("עודכנו " + r.get("updated") + " קופות"
                                    + (SavingsFund.num(r.get("notFound"), 0) > 0
                                    ? ", " + r.get("notFound") + " לא נמצאו" : ""));
                        }
                    } else if (!silent) {
                        toast(serverError(task.getException()));
                    }
                });
    }

    private static String serverError(@Nullable Exception e) {
        if (e instanceof FirebaseFunctionsException) {
            FirebaseFunctionsException.Code code = ((FirebaseFunctionsException) e).getCode();
            if (code == FirebaseFunctionsException.Code.NOT_FOUND) {
                return "פונקציות הקופות עוד לא הותקנו בשרת (firebase deploy --only functions).";
            }
            if (code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED) {
                return "הגעתם למגבלת העדכונים להיום. הנתונים מתעדכנים גם אוטומטית.";
            }
            if (code == FirebaseFunctionsException.Code.UNAUTHENTICATED) {
                return "צריך להתחבר לחשבון.";
            }
            return "שגיאה מהשרת: " + e.getMessage();
        }
        return "אין חיבור לשרת" + (e != null && e.getMessage() != null ? ": " + e.getMessage() : "");
    }

    private interface LookupCallback {
        void onResults(List<Map<String, Object>> results);
    }

    @SuppressWarnings("unchecked")
    private void lookup(Map<String, Object> request, LookupCallback callback) {
        FirebaseFunctions.getInstance().getHttpsCallable("fundsLookup")
                .withTimeout(60, TimeUnit.SECONDS)
                .call(request)
                .addOnCompleteListener(task -> {
                    if (getView() == null) return;
                    if (!task.isSuccessful()) {
                        toast(serverError(task.getException()));
                        return;
                    }
                    List<Map<String, Object>> out = new ArrayList<>();
                    Object data = task.getResult().getData();
                    if (data instanceof Map && ((Map<?, ?>) data).get("results") instanceof List) {
                        for (Object o : (List<Object>) ((Map<?, ?>) data).get("results")) {
                            if (o instanceof Map) out.add((Map<String, Object>) o);
                        }
                    }
                    callback.onResults(out);
                });
    }

    private void loadUsdIls() {
        io.execute(() -> {
            try {
                String json = YahooQuoteFallback.finnhubQuoteJson("OANDA:USD_ILS");
                if (json == null) return;
                double rate = new JSONObject(json).optDouble("c", Double.NaN);
                if (!(rate > 1 && rate < 10)) return;
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (getView() == null) return;
                    usdIls = rate;
                    prefs().edit().putFloat(PREF_USD_ILS, (float) rate).apply();
                    render();
                });
            } catch (Exception ignored) {
                // The cached rate (or no combined total) is shown instead.
            }
        });
    }

    // ---- add / edit -------------------------------------------------------------------------

    private void showEditDialog(@Nullable SavingsFund existing) {
        if (fundsRef == null) {
            toast("צריך להתחבר לחשבון.");
            return;
        }
        Context ctx = requireContext();
        SavingsFund f = existing != null ? existing : new SavingsFund();

        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        form.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        EditText etId = field(form, "מספר קופה / מסלול", f.fundId > 0 ? String.valueOf(f.fundId) : "",
                InputType.TYPE_CLASS_NUMBER);
        EditText etName = field(form, "שם הקופה", f.name, InputType.TYPE_CLASS_TEXT);
        TextView search = text("🔍 חיפוש לפי המספר או השם (גמל-נט / פנסיה-נט)", 13, R.color.pf_22d3ee, true);
        search.setPadding(0, dp(6), 0, dp(10));
        form.addView(search);
        EditText etCompany = field(form, "חברה מנהלת", f.company, InputType.TYPE_CLASS_TEXT);

        Spinner kind = new Spinner(ctx);
        String[] kindLabels = new String[SavingsFund.KINDS.length];
        int kindIndex = SavingsFund.KINDS.length - 1;
        for (int i = 0; i < SavingsFund.KINDS.length; i++) {
            kindLabels[i] = SavingsFund.kindLabel(SavingsFund.KINDS[i]);
            if (SavingsFund.KINDS[i].equals(f.kind)) kindIndex = i;
        }
        kind.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_dropdown_item, kindLabels));
        kind.setSelection(kindIndex);
        form.addView(kind);

        EditText etTrack = field(form, "מסלול השקעה", f.track, InputType.TYPE_CLASS_TEXT);
        EditText etBalance = field(form, "יתרה (₪)", f.balance > 0 ? plain(f.balance) : "",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        int defaultPeriod = FundImportParser.previousMonth(Calendar.getInstance());
        EditText etPeriod = field(form, "היתרה נכונה לחודש (MM/YYYY)",
                SavingsFund.periodLabel(f.balancePeriod > 0 ? f.balancePeriod : defaultPeriod),
                InputType.TYPE_CLASS_DATETIME);
        EditText etFee = field(form, "דמי ניהול מצבירה (%)", Double.isNaN(f.feeBalance) ? "" : plain(f.feeBalance),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText etFeeDep = field(form, "דמי ניהול מהפקדה (%)", Double.isNaN(f.feeDeposit) ? "" : plain(f.feeDeposit),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText etDeposit = field(form, "הפקדה חודשית (₪, לא חובה)", f.monthlyDeposit > 0 ? plain(f.monthlyDeposit) : "",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        if (existing != null && existing.market != null && !existing.market.peerTopText.isEmpty()) {
            TextView top = text("המובילות במסלול (ממוצע 3 שנים):\n" + existing.market.peerTopText,
                    12, R.color.pf_8d9aae, false);
            top.setPadding(0, dp(10), 0, 0);
            form.addView(top);
        }

        search.setOnClickListener(v -> {
            String id = etId.getText().toString().trim();
            String q = etName.getText().toString().trim();
            Map<String, Object> req = new HashMap<>();
            if (!id.isEmpty()) req.put("fundId", id);
            else if (q.length() >= 2) req.put("query", q);
            else {
                toast("כתבו מספר קופה או חלק מהשם");
                return;
            }
            search.setText("מחפש…");
            lookup(req, results -> {
                search.setText("🔍 חיפוש לפי המספר או השם (גמל-נט / פנסיה-נט)");
                if (results.isEmpty()) {
                    toast("לא נמצאה קופה מתאימה");
                    return;
                }
                if (results.size() == 1) {
                    fillFromLookup(results.get(0), etId, etName, etCompany, etTrack, kind);
                    return;
                }
                String[] labels = new String[results.size()];
                for (int i = 0; i < results.size(); i++) {
                    Map<String, Object> r = results.get(i);
                    labels[i] = SavingsFund.str(r.get("name")) + "\n" + SavingsFund.str(r.get("specialization"))
                            + " · #" + (long) SavingsFund.num(r.get("fundId"), 0);
                }
                new AlertDialog.Builder(ctx)
                        .setTitle("בחרו קופה")
                        .setItems(labels, (d, which) -> fillFromLookup(results.get(which),
                                etId, etName, etCompany, etTrack, kind))
                        .show();
            });
        });

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(form);
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx)
                .setTitle(existing == null ? "הוספת קופה" : "עריכת קופה")
                .setView(scroll)
                .setPositiveButton("שמירה", null)
                .setNegativeButton("ביטול", null);
        if (existing != null) {
            builder.setNeutralButton("מחיקה", (d, w) -> new AlertDialog.Builder(ctx)
                    .setMessage("למחוק את \"" + existing.displayName() + "\" מהאפליקציה?")
                    .setPositiveButton("מחיקה", (d2, w2) -> fundsRef.child(existing.key).removeValue())
                    .setNegativeButton("ביטול", null)
                    .show());
        }
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            long oldId = f.fundId;
            f.fundId = (long) parse(etId.getText().toString(), 0);
            f.name = etName.getText().toString().trim();
            f.company = etCompany.getText().toString().trim();
            f.kind = SavingsFund.KINDS[kind.getSelectedItemPosition()];
            f.track = etTrack.getText().toString().trim();
            f.balance = parse(etBalance.getText().toString(), 0);
            int period = FundImportParser.parsePeriod(etPeriod.getText().toString());
            f.balancePeriod = period > 0 ? period : defaultPeriod;
            f.feeBalance = parse(etFee.getText().toString(), Double.NaN);
            f.feeDeposit = parse(etFeeDep.getText().toString(), Double.NaN);
            f.monthlyDeposit = parse(etDeposit.getText().toString(), 0);
            if (f.importSource.isEmpty()) f.importSource = "manual";
            if (f.fundId <= 0 && f.name.isEmpty()) {
                toast("צריך מספר קופה או שם");
                return;
            }
            Map<String, Object> values = f.toUserMap();
            if (oldId != f.fundId) values.put("market", null); // data of the old number
            DatabaseReference ref = existing != null ? fundsRef.child(existing.key) : fundsRef.push();
            ref.updateChildren(values).addOnCompleteListener(t -> {
                if (!t.isSuccessful()) {
                    toast("השמירה נכשלה");
                    return;
                }
                if (f.fundId > 0) refreshFromServer(true);
            });
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void fillFromLookup(Map<String, Object> r, EditText etId, EditText etName, EditText etCompany,
                                EditText etTrack, Spinner kind) {
        long id = (long) SavingsFund.num(r.get("fundId"), 0);
        if (id > 0) etId.setText(String.valueOf(id));
        etName.setText(SavingsFund.str(r.get("name")));
        etCompany.setText(SavingsFund.str(r.get("company")));
        String spec = SavingsFund.str(r.get("specialization"));
        String sub = SavingsFund.str(r.get("subSpecialization"));
        etTrack.setText(sub.isEmpty() ? spec : spec + " · " + sub);
        String source = SavingsFund.str(r.get("source"));
        String k = "pension".equals(source) ? SavingsFund.KIND_PENSION
                : "bituach".equals(source) ? SavingsFund.KIND_POLICY
                : SavingsFund.kindFromText(SavingsFund.str(r.get("classification")) + " " + etName.getText());
        if (SavingsFund.KIND_OTHER.equals(k)) k = SavingsFund.KIND_GEMEL;
        for (int i = 0; i < SavingsFund.KINDS.length; i++) {
            if (SavingsFund.KINDS[i].equals(k)) kind.setSelection(i);
        }
    }

    // ---- הר הכסף import ---------------------------------------------------------------------

    private void showImportHelp() {
        if (fundsRef == null) {
            toast("צריך להתחבר לחשבון.");
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("ייבוא מהר הכסף")
                .setMessage("1. היכנסו לאתר \"הר הכסף\" של משרד האוצר והזדהו.\n"
                        + "2. ברשימת החסכונות בחרו ייצוא / הורדה לאקסל ושמרו את הקובץ בטלפון.\n"
                        + "3. לחצו \"בחירת קובץ\" ובחרו אותו.\n\n"
                        + "נקראים קבצי xlsx, csv ו-xls שנשמר מהאתר. קופה שכבר קיימת תתעדכן ולא תוכפל.")
                .setPositiveButton("בחירת קובץ", (d, w) -> pickFile.launch(new String[]{
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.ms-excel", "text/csv", "text/comma-separated-values",
                        "text/html", "text/plain", "application/octet-stream"}))
                .setNegativeButton("ביטול", null)
                .show();
    }

    private void importFile(@Nullable Uri uri) {
        if (uri == null) return;
        Context app = requireContext().getApplicationContext();
        io.execute(() -> {
            String error = null;
            FundImportParser.Result result = null;
            try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("empty");
                result = FundImportParser.parse(FundFileReader.read(in),
                        FundImportParser.previousMonth(Calendar.getInstance()));
            } catch (FundFileReader.UnsupportedFormatException e) {
                error = "הקובץ בפורמט Excel ישן (xls). פתחו אותו ב-Excel או ב-Google Sheets, "
                        + "שמרו בשם כ-xlsx או CSV ונסו שוב.";
            } catch (Exception e) {
                error = "לא ניתן לקרוא את הקובץ: " + e.getMessage();
            }
            final String err = error;
            final FundImportParser.Result res = result;
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (getView() == null) return;
                if (err != null) {
                    new AlertDialog.Builder(requireContext()).setMessage(err).setPositiveButton("אישור", null).show();
                } else if (res.funds.isEmpty()) {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("לא נמצאו קופות")
                            .setMessage("לא מצאתי בקובץ טבלה עם עמודות כמו שם קופה / חברה מנהלת ויתרה.\n"
                                    + "ודאו שזה הקובץ שהורד מהר הכסף, או הוסיפו את הקופות ידנית.")
                            .setPositiveButton("אישור", null)
                            .show();
                } else {
                    confirmImport(res.funds);
                }
            });
        });
    }

    private void confirmImport(List<SavingsFund> found) {
        String[] labels = new String[found.size()];
        boolean[] checked = new boolean[found.size()];
        for (int i = 0; i < found.size(); i++) {
            SavingsFund f = found.get(i);
            labels[i] = f.displayName() + (f.company.isEmpty() ? "" : " · " + f.company)
                    + "\n" + ils(f.balance) + (f.fundId > 0 ? " · #" + f.fundId : "")
                    + (f.active ? "" : " · לא פעילה");
            checked[i] = true;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("נמצאו " + found.size() + " קופות")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("ייבוא", (d, w) -> {
                    int added = 0, updated = 0;
                    for (int i = 0; i < found.size(); i++) {
                        if (!checked[i]) continue;
                        SavingsFund f = found.get(i);
                        SavingsFund match = findExisting(f);
                        if (match != null) {
                            Map<String, Object> values = new HashMap<>();
                            values.put("balance", f.balance);
                            values.put("balancePeriod", f.balancePeriod);
                            values.put("active", f.active);
                            if (!Double.isNaN(f.feeBalance)) values.put("feeBalance", f.feeBalance);
                            if (!Double.isNaN(f.feeDeposit)) values.put("feeDeposit", f.feeDeposit);
                            if (f.monthlyDeposit > 0) values.put("monthlyDeposit", f.monthlyDeposit);
                            if (match.fundId <= 0 && f.fundId > 0) values.put("fundId", f.fundId);
                            fundsRef.child(match.key).updateChildren(values);
                            updated++;
                        } else {
                            fundsRef.push().updateChildren(f.toUserMap());
                            added++;
                        }
                    }
                    toast("נוספו " + added + ", עודכנו " + updated);
                    refreshFromServer(true);
                })
                .setNegativeButton("ביטול", null)
                .show();
    }

    @Nullable
    private SavingsFund findExisting(SavingsFund f) {
        for (SavingsFund e : funds) {
            if (!f.account.isEmpty() && f.account.equals(e.account)) return e;
            if (f.fundId > 0 && f.fundId == e.fundId && (f.account.isEmpty() || e.account.isEmpty())) return e;
            if (f.fundId <= 0 && !f.name.isEmpty() && f.name.equals(e.name) && f.company.equals(e.company)) return e;
        }
        return null;
    }

    // ---- small helpers ----------------------------------------------------------------------

    private EditText field(LinearLayout parent, String hint, String value, int inputType) {
        TextView label = text(hint, 12, R.color.pf_8d9aae, false);
        label.setPadding(0, dp(6), 0, 0);
        parent.addView(label);
        EditText e = new EditText(requireContext());
        e.setInputType(inputType);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        parent.addView(e);
        return e;
    }

    private TextView text(String s, float sp, int colorRes, boolean bold) {
        TextView t = new TextView(requireContext());
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color(colorRes));
        t.setGravity(Gravity.START);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        t.setPadding(0, dp(2), 0, 0);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private int color(int res) {
        return ContextCompat.getColor(requireContext(), res);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        if (getContext() != null) Toast.makeText(getContext(), s, Toast.LENGTH_LONG).show();
    }

    static String ils(double v) {
        return String.format(Locale.US, "₪%,.0f", v);
    }

    /** "₪1.2M", "₪340K" for small buttons. */
    static String ilsShort(double v) {
        if (Math.abs(v) >= 1_000_000) return String.format(Locale.US, "₪%.2fM", v / 1_000_000);
        if (Math.abs(v) >= 10_000) return String.format(Locale.US, "₪%.0fK", v / 1_000);
        return ils(v);
    }

    private static String pct(String label, double v) {
        if (Double.isNaN(v)) return "";
        return String.format(Locale.US, "%s %s%.2f%%", label, v > 0 ? "+" : "", v);
    }

    private static String joinParts(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p);
        }
        return sb.toString();
    }

    private static String plain(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.valueOf(Math.round(v * 10000.0) / 10000.0);
    }

    private static double parse(String s, double fallback) {
        Double v = FundImportParser.parseAmount(s);
        return v == null ? fallback : v;
    }
}
