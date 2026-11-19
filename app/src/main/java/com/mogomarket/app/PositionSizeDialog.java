package com.mogomarket.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Locale;

/**
 * Position-size calculator: from the account size, the % you accept to lose, the entry and the
 * stop it returns how many shares to buy, the position value, the risk in dollars and the
 * reward-to-risk ratio. Can hand the result back to an order form.
 */
final class PositionSizeDialog {

    interface OnUse {
        void onUse(double shares, double entry, double stop, double target);
    }

    private static final String PREFS = "position_size_prefs";

    private PositionSizeDialog() { }

    static void show(Context ctx, @Nullable String symbol, double entry, double stop,
                     double target, double accountSize, @Nullable OnUse onUse) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        float d = ctx.getResources().getDisplayMetrics().density;
        int text = MaterialColors.getColor(ctx,
                com.google.android.material.R.attr.colorOnSurface, Color.BLACK);
        int sub = MaterialColors.getColor(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY);
        int gain = ContextCompat.getColor(ctx, R.color.gain);
        int loss = ContextCompat.getColor(ctx, R.color.loss);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (10 * d), (int) (20 * d), (int) (6 * d));

        double account = accountSize > 0 ? accountSize
                : prefs.getFloat("account", 10000f);
        TextInputEditText etAccount = field(ctx, box, "Account size ($)", fmt(account));
        TextInputEditText etRisk = field(ctx, box, "Risk per trade (% of account)",
                fmt(prefs.getFloat("risk", 1f)));
        TextInputEditText etEntry = field(ctx, box, "Entry price ($)", entry > 0 ? fmt(entry) : "");
        TextInputEditText etStop = field(ctx, box, "Stop loss ($)", stop > 0 ? fmt(stop) : "");
        TextInputEditText etTarget = field(ctx, box, "Take profit ($) - optional",
                target > 0 ? fmt(target) : "");

        TextView result = new TextView(ctx);
        result.setTextSize(14);
        result.setTextColor(text);
        result.setPadding(0, (int) (14 * d), 0, 0);
        box.addView(result);

        final double[] last = new double[4]; // shares, entry, stop, target
        Runnable recalc = () -> {
            double acc = num(etAccount);
            double riskPct = num(etRisk);
            double en = num(etEntry);
            double st = num(etStop);
            double tg = num(etTarget);
            last[0] = 0;
            if (acc <= 0 || riskPct <= 0 || en <= 0 || st <= 0) {
                result.setTextColor(sub);
                result.setText("Fill in account size, risk, entry and stop loss.");
                return;
            }
            if (st == en) {
                result.setTextColor(loss);
                result.setText("Stop loss must be different from the entry price.");
                return;
            }
            boolean isLong = st < en;
            double riskMoney = acc * riskPct / 100.0;
            double perShare = Math.abs(en - st);
            double shares = Math.floor(riskMoney / perShare * 100.0) / 100.0;
            boolean capped = false;
            if (shares * en > acc) {
                shares = Math.floor(acc / en * 100.0) / 100.0;
                capped = true;
            }
            if (shares <= 0) {
                result.setTextColor(loss);
                result.setText("The account is too small for this trade.");
                return;
            }
            double value = shares * en;
            double realRisk = shares * perShare;
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "Buy %s shares%s\n", PaperEngine.fmtShares(shares),
                    isLong ? "" : " (SHORT idea - the simulator is long only)"));
            sb.append(String.format(Locale.US, "Position value: $%,.2f (%.1f%% of account)\n",
                    value, value / acc * 100.0));
            sb.append(String.format(Locale.US, "Money at risk: $%,.2f (%.2f%% of account)\n",
                    realRisk, realRisk / acc * 100.0));
            if (tg > 0) {
                double reward = Math.abs(tg - en) * shares;
                boolean right = isLong ? tg > en : tg < en;
                if (right) {
                    sb.append(String.format(Locale.US,
                            "Potential profit: $%,.2f   Reward : risk = %.2f : 1",
                            reward, Math.abs(tg - en) / perShare));
                } else {
                    sb.append("Take profit is on the wrong side of the entry.");
                }
            }
            if (capped) sb.append("\nLimited by the account size (all-in).");
            result.setTextColor(capped ? loss : gain);
            result.setText(sb.toString());
            last[0] = shares;
            last[1] = en;
            last[2] = st;
            last[3] = tg;
        };
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable e) {
                recalc.run();
            }
        };
        for (EditText et : new EditText[]{etAccount, etRisk, etEntry, etStop, etTarget}) {
            et.addTextChangedListener(watcher);
        }
        recalc.run();

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(box, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx)
                .setTitle(symbol == null || symbol.isEmpty() ? "Position size calculator"
                        : "Position size - " + symbol)
                .setView(scroll)
                .setNegativeButton("Close", null);
        if (onUse != null) {
            builder.setPositiveButton("Use in order", (dlg, w) -> {
                if (last[0] <= 0) return;
                prefs.edit().putFloat("account", (float) num(etAccount))
                        .putFloat("risk", (float) num(etRisk)).apply();
                onUse.onUse(last[0], last[1], last[2], last[3]);
            });
        }
        builder.setOnDismissListener(x -> prefs.edit()
                .putFloat("account", (float) Math.max(1, num(etAccount)))
                .putFloat("risk", (float) Math.max(0.01, num(etRisk))).apply());
        builder.show();
    }

    private static TextInputEditText field(Context ctx, LinearLayout parent, String hint,
                                           String value) {
        TextInputLayout til = new TextInputLayout(ctx, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        til.setHint(hint);
        TextInputEditText et = new TextInputEditText(til.getContext());
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setSingleLine(true);
        et.setText(value);
        til.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * ctx.getResources().getDisplayMetrics().density);
        parent.addView(til, lp);
        return et;
    }

    private static double num(EditText et) {
        try {
            double v = Double.parseDouble(et.getText().toString().trim());
            return Double.isFinite(v) ? v : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.0000001) return String.format(Locale.US, "%.0f", v);
        return String.format(Locale.US, "%.4f", v).replaceAll("0+$", "");
    }
}
