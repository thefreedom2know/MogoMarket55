package com.mogomarket.app;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.FirebaseDatabase;

import java.util.Calendar;
import java.util.Locale;

public class SettingsFragment extends Fragment {

    private String pendingExportCsv;
    private final androidx.activity.result.ActivityResultLauncher<String> exportLauncher =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.CreateDocument(
                            "text/csv"),
                    uri -> {
                        if (uri == null || pendingExportCsv == null || getContext() == null) return;
                        String csv = pendingExportCsv;
                        pendingExportCsv = null;
                        String error = DataExport.write(requireContext(), uri, csv);
                        Toast.makeText(getContext(), error == null ? "File saved" : error,
                                Toast.LENGTH_LONG).show();
                    });

    private MaterialButton btnThemeToggle;
    private TextView tvThemeStatus;
    private static final String PREFS_NAME       = PriceAlertScheduler.PREFS_NAME;
    private static final String KEY_THEME        = "dark_mode";
    private static final String KEY_PRICE_ALERTS = PriceAlertScheduler.KEY_ENABLED;
    private static final String KEY_DAILY_EMAIL  = DailySummaryEmailService.KEY_ENABLED;
    private boolean isDark;
    private SharedPreferences prefs;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (sharedPreferences, key) -> {
                if (KEY_THEME.equals(key)) {
                    isDark = sharedPreferences.getBoolean(KEY_THEME, true);
                    updateThemeUI(isDark);
                }
            };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.fragment_settings, container, false);
        // ❌ הוסר: v.setBackgroundColor(...)

        prefs = requireActivity().getSharedPreferences(PREFS_NAME, 0);

        btnThemeToggle = v.findViewById(R.id.btnThemeToggle);
        tvThemeStatus  = v.findViewById(R.id.tvThemeStatus);

        isDark = prefs.getBoolean(KEY_THEME, true);
        updateThemeUI(isDark);

        if (btnThemeToggle != null) {
            btnThemeToggle.setOnClickListener(view -> {
                isDark = !isDark;
                prefs.edit().putBoolean(KEY_THEME, isDark).apply();
                AppCompatDelegate.setDefaultNightMode(
                        isDark ? AppCompatDelegate.MODE_NIGHT_YES
                                : AppCompatDelegate.MODE_NIGHT_NO);
                updateThemeUI(isDark);
                applyThemeToView(requireView(), isDark);  // ✅ צבעים בלי recreate
            });
        }

        // Watchlist navigation
        SwitchMaterial switchWatchlistNav = v.findViewById(R.id.switchWatchlistNav);
        if (switchWatchlistNav != null) {
            switchWatchlistNav.setChecked(prefs.getBoolean(WatchlistFragment.KEY_WATCHLIST_NAV, true));
            switchWatchlistNav.setOnCheckedChangeListener((btn, isChecked) ->
                    prefs.edit().putBoolean(WatchlistFragment.KEY_WATCHLIST_NAV, isChecked).apply());
        }

        // Hide keyboard on add
        SwitchMaterial switchHideKeyboard = v.findViewById(R.id.switchHideKeyboardOnAdd);
        if (switchHideKeyboard != null) {
            switchHideKeyboard.setChecked(prefs.getBoolean(WatchlistFragment.KEY_WATCHLIST_HIDE_KB, true));
            switchHideKeyboard.setOnCheckedChangeListener((btn, isChecked) ->
                    prefs.edit().putBoolean(WatchlistFragment.KEY_WATCHLIST_HIDE_KB, isChecked).apply());
        }

        // Price target alerts
        SwitchMaterial switchPriceAlerts = v.findViewById(R.id.switchPriceAlerts);
        if (switchPriceAlerts != null) {
            boolean alertsEnabled = prefs.getBoolean(KEY_PRICE_ALERTS, false);
            switchPriceAlerts.setChecked(alertsEnabled);
            if (alertsEnabled) PriceAlertScheduler.schedule(requireContext());

            switchPriceAlerts.setOnCheckedChangeListener((btn, isChecked) -> {
                prefs.edit().putBoolean(KEY_PRICE_ALERTS, isChecked).apply();
                if (isChecked) {
                    PriceAlertScheduler.schedule(requireContext());
                    Toast.makeText(requireContext(), "Price target alerts enabled", Toast.LENGTH_SHORT).show();
                } else {
                    PriceAlertScheduler.cancel(requireContext());
                    Toast.makeText(requireContext(), "Price target alerts disabled", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Daily summary email
        SwitchMaterial switchDailyEmail = v.findViewById(R.id.switchDailyEmail);
        MaterialButton btnDailySummaryTime = v.findViewById(R.id.btnDailySummaryTime);
        if (btnDailySummaryTime != null) {
            updateDailySummaryTimeButton(btnDailySummaryTime);
            btnDailySummaryTime.setOnClickListener(view -> {
                int hour = prefs.getInt(DailySummaryEmailService.KEY_HOUR, 8);
                int minute = prefs.getInt(DailySummaryEmailService.KEY_MINUTE, 0);
                new TimePickerDialog(requireContext(), (picker, selectedHour, selectedMinute) -> {
                    prefs.edit()
                            .putInt(DailySummaryEmailService.KEY_HOUR, selectedHour)
                            .putInt(DailySummaryEmailService.KEY_MINUTE, selectedMinute)
                            .apply();
                    updateDailySummaryTimeButton(btnDailySummaryTime);
                    if (prefs.getBoolean(KEY_DAILY_EMAIL, false)) {
                        DailySummaryEmailService.scheduleDailySummary(requireContext());
                    }
                }, hour, minute, true).show();
            });
        }
        if (switchDailyEmail != null) {
            boolean dailyEnabled = prefs.getBoolean(KEY_DAILY_EMAIL, false);
            switchDailyEmail.setChecked(dailyEnabled);
            if (dailyEnabled) DailySummaryEmailService.scheduleDailySummary(requireContext());

            switchDailyEmail.setOnCheckedChangeListener((btn, isChecked) -> {
                prefs.edit().putBoolean(KEY_DAILY_EMAIL, isChecked).apply();
                if (isChecked) {
                    DailySummaryEmailService.scheduleDailySummary(requireContext());
                    Toast.makeText(requireContext(),
                            "Daily summary enabled Monday through Saturday", Toast.LENGTH_LONG).show();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        android.app.AlarmManager alarmManager =
                                requireContext().getSystemService(android.app.AlarmManager.class);
                        if (alarmManager != null && !alarmManager.canScheduleExactAlarms()) {
                            int hour = prefs.getInt(DailySummaryEmailService.KEY_HOUR, 8);
                            int minute = prefs.getInt(DailySummaryEmailService.KEY_MINUTE, 0);
                            new AlertDialog.Builder(requireContext())
                                    .setTitle("Allow exact alarms")
                                    .setMessage(String.format(Locale.getDefault(),
                                            "Allow MogoMarket to schedule exact alarms so the email can be sent at %02d:%02d.",
                                            hour, minute))
                                    .setPositiveButton("Open settings", (dialog, which) -> {
                                        Intent settingsIntent = new Intent(
                                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                                Uri.parse("package:" + requireContext().getPackageName()));
                                        startActivity(settingsIntent);
                                    })
                                    .setNegativeButton("Not now", null)
                                    .show();
                        }
                    }
                } else {
                    DailySummaryEmailService.cancelDailySummary(requireContext());
                    Toast.makeText(requireContext(), "Daily summary email disabled", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Daily summary: choose content + recipient
        MaterialButton btnSummaryContent = v.findViewById(R.id.btnDailySummaryContent);
        if (btnSummaryContent != null) {
            btnSummaryContent.setOnClickListener(view -> showSummaryContentDialog());
        }
        MaterialButton btnSummaryLanguage = v.findViewById(R.id.btnDailySummaryLanguage);
        if (btnSummaryLanguage != null) {
            updateSummaryLanguageButton(btnSummaryLanguage);
            btnSummaryLanguage.setOnClickListener(view -> {
                boolean hebrew = "he".equals(
                        prefs.getString(DailySummaryEmailService.KEY_LANGUAGE, "en"));
                new AlertDialog.Builder(requireContext())
                        .setTitle("Email language / שפת המייל")
                        .setSingleChoiceItems(new String[]{"English", "עברית"}, hebrew ? 1 : 0,
                                (dialog, which) -> {
                                    prefs.edit().putString(DailySummaryEmailService.KEY_LANGUAGE,
                                            which == 1 ? "he" : "en").apply();
                                    updateSummaryLanguageButton(btnSummaryLanguage);
                                    dialog.dismiss();
                                })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }
        MaterialButton btnSummaryRecipient = v.findViewById(R.id.btnDailySummaryRecipient);
        if (btnSummaryRecipient != null) {
            updateSummaryRecipientButton(btnSummaryRecipient);
            btnSummaryRecipient.setOnClickListener(view ->
                    showSummaryRecipientDialog(btnSummaryRecipient));
        }

        // Button: send daily summary now
        MaterialButton btnSendEmailNow = v.findViewById(R.id.btnSendDailyEmailNow);
        if (btnSendEmailNow != null) {
            btnSendEmailNow.setOnClickListener(view -> {
                FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                if (user == null || user.isAnonymous() || user.getEmail() == null || user.getEmail().trim().isEmpty()) {
                    Toast.makeText(requireContext(), "No valid user email found", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(requireContext(), "Sending daily summary email...", Toast.LENGTH_SHORT).show();
                DailySummaryEmailService.sendNow(requireContext(), true);
            });
        }

        setupDefaultSymbolSection(v);
        setupDefaultChartTimeframe(v);
        setupChartChangePercentage(v);
        setupStartPageSelector(v);

        // Account email
        TextView tvEmail = v.findViewById(R.id.tvUserEmail);
        if (tvEmail != null) {
            // ❌ הוסר: tvEmail.setTextColor(...)
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user != null && user.isAnonymous()) {
                tvEmail.setText("Guest");
            } else {
                tvEmail.setText(user != null && user.getEmail() != null ? user.getEmail() : "Guest");
            }

        }

        // App version
        TextView tvVersion = v.findViewById(R.id.tvAppVersion);
        if (tvVersion != null) {
            // ❌ הוסר: tvVersion.setTextColor(...)
            try {
                String ver = requireContext().getPackageManager()
                        .getPackageInfo(requireContext().getPackageName(), 0).versionName;
                tvVersion.setText("v" + ver);
            } catch (Exception e) {
                tvVersion.setText("v1.0");
            }
        }

        // Logout
        MaterialButton btnLogout = v.findViewById(R.id.btnSettingsLogout);
        if (btnLogout != null) {
            // ❌ הוסרו: setBackgroundTintList + setTextColor
            btnLogout.setOnClickListener(view -> {
                FirebaseAuth.getInstance().signOut();
                Intent intent = new Intent(requireActivity(), AuthLogin.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
            });
        }

        // Export my data (CSV / Excel)
        MaterialButton btnExportData = v.findViewById(R.id.btnExportData);
        if (btnExportData != null) {
            btnExportData.setOnClickListener(view -> showExportDialog());
        }

        // Delete Account
        MaterialButton btnDeleteAccount = v.findViewById(R.id.btnDeleteAccount);
        if (btnDeleteAccount != null) {
            btnDeleteAccount.setOnClickListener(view -> showDeleteAccountDialog());
        }

        setupAccordion(v);
        return v;
    }

    private static final String KEY_SETTINGS_EXPANDED = "settings_expanded_cards";

    /**
     * Turns every settings card into a collapsible section (tap the title to open or close it),
     * styled like the rest of the app. All the controls inside keep their ids and behaviour.
     */
    private void setupAccordion(View root) {
        if (!(root instanceof ViewGroup) || ((ViewGroup) root).getChildCount() == 0) return;
        View first = ((ViewGroup) root).getChildAt(0);
        if (!(first instanceof LinearLayout)) return;
        final LinearLayout page = (LinearLayout) first;
        final float dp = getResources().getDisplayMetrics().density;
        final java.util.Set<String> expanded = new java.util.HashSet<>(
                prefs.getStringSet(KEY_SETTINGS_EXPANDED, new java.util.HashSet<String>()));

        int cardIndex = 0;
        for (int i = 0; i < page.getChildCount(); i++) {
            View child = page.getChildAt(i);
            if (!(child instanceof com.google.android.material.card.MaterialCardView)) continue;
            com.google.android.material.card.MaterialCardView card =
                    (com.google.android.material.card.MaterialCardView) child;
            card.setRadius(20 * dp);
            card.setCardBackgroundColor(getResources().getColor(R.color.pf_0b0f16, null));
            card.setStrokeColor(getResources().getColor(R.color.pf_3322d3ee, null));
            card.setStrokeWidth(Math.max(1, Math.round(dp)));

            if (card.getChildCount() == 0 || !(card.getChildAt(0) instanceof LinearLayout)) continue;
            LinearLayout inner = (LinearLayout) card.getChildAt(0);
            if (inner.getChildCount() < 2) continue;
            buildAccordionCard(page, inner, "card" + cardIndex, expanded, dp);
            cardIndex++;
        }
    }

    private void buildAccordionCard(final LinearLayout page, final LinearLayout inner,
                                    final String key, final java.util.Set<String> expanded,
                                    float dp) {
        final View header = inner.getChildAt(0);
        final java.util.List<View> body = new java.util.ArrayList<>();
        for (int i = 1; i < inner.getChildCount(); i++) body.add(inner.getChildAt(i));

        int originalBottomMargin = 0;
        if (header.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            originalBottomMargin = ((ViewGroup.MarginLayoutParams) header.getLayoutParams())
                    .bottomMargin;
        }
        final int bodyGap = originalBottomMargin;

        if (header instanceof TextView) {
            ((TextView) header).setTextColor(getResources().getColor(R.color.pf_22d3ee, null));
            ((TextView) header).setTextSize(17f);
        }

        inner.removeView(header);
        final LinearLayout bar = new LinearLayout(requireContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.addView(header, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView chevron = new TextView(requireContext());
        chevron.setTextSize(18f);
        chevron.setTextColor(getResources().getColor(R.color.pf_22d3ee, null));
        bar.addView(chevron, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        android.util.TypedValue outValue = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, outValue, true);
        bar.setBackgroundResource(outValue.resourceId);
        bar.setClickable(true);
        bar.setFocusable(true);

        final LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inner.addView(bar, 0, barParams);

        final Runnable apply = () -> {
            boolean open = expanded.contains(key);
            chevron.setText(open ? "\u25B4" : "\u25BE");
            barParams.bottomMargin = open ? bodyGap : 0;
            bar.setLayoutParams(barParams);
            for (View v : body) v.setVisibility(open ? View.VISIBLE : View.GONE);
        };
        apply.run();

        bar.setOnClickListener(view -> {
            android.transition.TransitionManager.beginDelayedTransition(
                    page, new android.transition.AutoTransition().setDuration(180));
            if (!expanded.remove(key)) expanded.add(key);
            apply.run();
            prefs.edit().putStringSet(KEY_SETTINGS_EXPANDED,
                    new java.util.HashSet<>(expanded)).apply();
        });
    }

    private void showSummaryContentDialog() {
        final String[] ids = DailySummaryReport.ITEM_IDS;
        final String[] labels = DailySummaryReport.ITEM_LABELS;
        final boolean[] checked = new boolean[ids.length];
        for (int i = 0; i < ids.length; i++) {
            checked[i] = DailySummaryEmailService.isItemEnabled(prefs, ids[i]);
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Include in the daily summary")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) ->
                        checked[which] = isChecked)
                .setPositiveButton("Save", (dialog, which) -> {
                    SharedPreferences.Editor editor = prefs.edit();
                    for (int i = 0; i < ids.length; i++) {
                        editor.putBoolean(DailySummaryEmailService.KEY_ITEM_PREFIX + ids[i],
                                checked[i]);
                    }
                    editor.apply();
                    Toast.makeText(requireContext(), "Summary content saved",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateSummaryLanguageButton(MaterialButton button) {
        boolean hebrew = "he".equals(prefs.getString(DailySummaryEmailService.KEY_LANGUAGE, "en"));
        button.setText(hebrew ? "Email language: עברית" : "Email language: English");
    }

    private void updateSummaryRecipientButton(MaterialButton button) {
        String saved = prefs.getString(DailySummaryEmailService.KEY_RECIPIENT, "");
        button.setText(saved == null || saved.trim().isEmpty()
                ? "Send to: my account email" : "Send to: " + saved.trim());
    }

    private void showSummaryRecipientDialog(MaterialButton button) {
        final android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        input.setHint("name@example.com (separate several with commas)");
        input.setText(prefs.getString(DailySummaryEmailService.KEY_RECIPIENT, ""));
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout holder = new android.widget.FrameLayout(requireContext());
        holder.setPadding(pad, pad / 2, pad, 0);
        holder.addView(input);
        new AlertDialog.Builder(requireContext())
                .setTitle("Who receives the summary?")
                .setMessage("Leave empty to use your account email.")
                .setView(holder)
                .setPositiveButton("Save", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (!value.isEmpty()) {
                        for (String part : value.split(",")) {
                            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(part.trim()).matches()) {
                                Toast.makeText(requireContext(),
                                        "Invalid email: " + part.trim(), Toast.LENGTH_LONG).show();
                                return;
                            }
                        }
                    }
                    prefs.edit().putString(DailySummaryEmailService.KEY_RECIPIENT, value).apply();
                    updateSummaryRecipientButton(button);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateDailySummaryTimeButton(MaterialButton button) {
        int hour = prefs.getInt(DailySummaryEmailService.KEY_HOUR, 8);
        int minute = prefs.getInt(DailySummaryEmailService.KEY_MINUTE, 0);
        button.setText(String.format(Locale.getDefault(), "Summary time: %02d:%02d",
                hour, minute));
    }

    // ─── Delete Account ───────────────────────────────────────────────────────

    private void showExportDialog() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(requireContext(), "Please log in first", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Export my data (opens in Excel)")
                .setItems(DataExport.LABELS, (dialog, which) ->
                        DataExport.build(which, user.getUid(), (csv, error) -> {
                            if (!isAdded()) return;
                            if (csv == null) {
                                Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
                                return;
                            }
                            pendingExportCsv = csv;
                            exportLauncher.launch(DataExport.FILE_NAMES[which]);
                        }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showDeleteAccountDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete Account")
                .setMessage("This will permanently delete your account and ALL your data (portfolio, watchlist, trades).\n\nThis cannot be undone. Are you sure?")
                .setPositiveButton("Delete", (dialog, which) -> deleteAccount())
                .setNegativeButton("Cancel", null)
                .show();
    }


    private void deleteAccount() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        String uid = user.getUid();
        SecureCredentials.clear(requireContext());

        ProgressDialog pd = new ProgressDialog(requireContext());
        pd.setTitle("Deleting Account");
        pd.setMessage("Please wait...");
        pd.setCancelable(false);
        pd.show();

        // מחיקת נתונים ב-Realtime Database ואז מחיקת חשבון
        FirebaseDatabase.getInstance()
                .getReference("users")
                .child(uid)
                .removeValue()
                .addOnCompleteListener(task -> {
                    user.delete().addOnCompleteListener(authTask -> {
                        pd.dismiss();
                        if (authTask.isSuccessful()) {
                            Toast.makeText(requireContext(),
                                    "Account deleted successfully",
                                    Toast.LENGTH_SHORT).show();
                            Intent intent = new Intent(requireActivity(), AuthLogin.class);
                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            startActivity(intent);
                        } else {
                            String msg = authTask.getException() != null
                                    ? authTask.getException().getMessage()
                                    : "Unknown error";
                            Toast.makeText(requireContext(),
                                    "Failed to delete account: " + msg,
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                });
    }

    // ─── Default Symbol ───────────────────────────────────────────────────────

    private void setupDefaultSymbolSection(View v) {
        android.widget.EditText etDefaultSymbol = v.findViewById(R.id.etDefaultSymbol);
        MaterialButton btnSaveSymbol = v.findViewById(R.id.btnSaveDefaultSymbol);
        MaterialButton btnSymbolMode = v.findViewById(R.id.btnSymbolMode);

        if (etDefaultSymbol != null) {
            String current = prefs.getString(MainActivity.KEY_DEFAULT_SYMBOL, "SPY");
            etDefaultSymbol.setText(current);
        }

        updateSymbolModeButton(btnSymbolMode);

        if (btnSaveSymbol != null) {
            btnSaveSymbol.setOnClickListener(view -> {
                if (etDefaultSymbol == null) return;
                String sym = etDefaultSymbol.getText().toString().trim().toUpperCase();
                if (sym.isEmpty()) sym = "SPY";
                prefs.edit().putString(MainActivity.KEY_DEFAULT_SYMBOL, sym).apply();
                if ("fixed".equals(prefs.getString(ChartFragment.KEY_SYMBOL_MODE, "last"))) {
                    prefs.edit().putString(MainActivity.KEY_LAST_SYMBOL, sym).apply();
                }
                Toast.makeText(requireContext(), "\u2705 Default symbol saved: " + sym, Toast.LENGTH_SHORT).show();
            });
        }

        if (btnSymbolMode != null) {
            btnSymbolMode.setOnClickListener(view -> {
                String current = prefs.getString(ChartFragment.KEY_SYMBOL_MODE, "last");
                String next = "last".equals(current) ? "fixed" : "last";
                prefs.edit().putString(ChartFragment.KEY_SYMBOL_MODE, next).apply();
                updateSymbolModeButton(btnSymbolMode);
                String msg = "fixed".equals(next)
                        ? "\uD83D\uDCCC Switched to fixed default symbol"
                        : "\uD83D\uDD04 Switched to last viewed symbol";
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void setupDefaultChartTimeframe(View v) {
        MaterialButton button = v.findViewById(R.id.btnDefaultChartTimeframe);
        if (button == null) return;
        String[] timeframes = ChartFragment.getTimeframeLabels();
        button.setText(prefs.getString(ChartFragment.KEY_DEFAULT_TIMEFRAME, "1Y"));
        button.setOnClickListener(view -> {
            String current = prefs.getString(ChartFragment.KEY_DEFAULT_TIMEFRAME, "1Y");
            int selected = java.util.Arrays.asList(timeframes).indexOf(current);
            new AlertDialog.Builder(requireContext())
                    .setTitle("Default chart timeframe")
                    .setSingleChoiceItems(timeframes, Math.max(0, selected),
                            (dialog, which) -> {
                                String timeframe = timeframes[which];
                                prefs.edit()
                                        .putString(ChartFragment.KEY_DEFAULT_TIMEFRAME, timeframe)
                                        .apply();
                                button.setText(timeframe);
                                dialog.dismiss();
                                Toast.makeText(requireContext(),
                                        "Default chart timeframe saved",
                                        Toast.LENGTH_SHORT).show();
                            })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    private void setupChartChangePercentage(View v) {
        MaterialButton button = v.findViewById(R.id.btnChartChangePercentage);
        if (button == null) return;
        String[] options = {"Selected timeframe", "Today"};
        boolean showDaily = prefs.getBoolean(ChartFragment.KEY_CHANGE_PERCENT_DAILY, false);
        button.setText(options[showDaily ? 1 : 0]);
        button.setOnClickListener(view -> {
            boolean currentlyDaily = prefs.getBoolean(
                    ChartFragment.KEY_CHANGE_PERCENT_DAILY, false);
            new AlertDialog.Builder(requireContext())
                    .setTitle("Chart change percentage")
                    .setSingleChoiceItems(options, currentlyDaily ? 1 : 0, (dialog, which) -> {
                        boolean daily = which == 1;
                        prefs.edit()
                                .putBoolean(ChartFragment.KEY_CHANGE_PERCENT_DAILY, daily)
                                .apply();
                        button.setText(options[which]);
                        dialog.dismiss();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    private void updateSymbolModeButton(MaterialButton btn) {
        if (btn == null) return;
        String mode = prefs.getString(ChartFragment.KEY_SYMBOL_MODE, "last");
        if ("fixed".equals(mode)) {
            btn.setText("\uD83D\uDCCC Fixed");
            btn.setStrokeColorResource(R.color.primary);
            btn.setStrokeWidth(6);
        } else {
            btn.setText("\uD83D\uDD04 Last");
            btn.setStrokeColorResource(R.color.text_secondary);
            btn.setStrokeWidth(2);
        }
    }

    // ─── Start Page Selector ─────────────────────────────────────────────────

    private void setupStartPageSelector(View v) {
        int[] btnIds = {
                R.id.btnStartChart,
                R.id.btnStartWatchlist,
                R.id.btnStartPortfolio,
                R.id.btnStartTrades,
                R.id.btnStartSettings
        };
        int[] navIds = {
                R.id.nav_chart,
                R.id.nav_stocks,
                R.id.nav_portfolio,
                R.id.nav_closed_trades,
                R.id.nav_settings
        };

        int savedNavId = prefs.getInt(MainActivity.KEY_START_PAGE, R.id.nav_chart);

        for (int i = 0; i < btnIds.length; i++) {
            final int navId = navIds[i];
            MaterialButton btn = v.findViewById(btnIds[i]);
            if (btn == null) continue;

            btn.setStrokeColorResource(navId == savedNavId ? R.color.primary : R.color.text_secondary);
            btn.setStrokeWidth(navId == savedNavId ? 4 : 1);

            btn.setOnClickListener(click -> {
                prefs.edit().putInt(MainActivity.KEY_START_PAGE, navId).apply();
                Toast.makeText(requireContext(), "\u2705 Start page saved", Toast.LENGTH_SHORT).show();
                for (int j = 0; j < btnIds.length; j++) {
                    MaterialButton b = v.findViewById(btnIds[j]);
                    if (b == null) continue;
                    boolean selected = navIds[j] == navId;
                    b.setStrokeColorResource(selected ? R.color.primary : R.color.text_secondary);
                    b.setStrokeWidth(selected ? 4 : 1);
                }
            });
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onStart() {
        super.onStart();
        if (prefs != null) prefs.registerOnSharedPreferenceChangeListener(prefListener);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (prefs == null) return;
        isDark = prefs.getBoolean(KEY_THEME, true);
        updateThemeUI(isDark);
        applyThemeToView(requireView(), isDark);
    }

    private void updateThemeUI(boolean dark) {
        if (btnThemeToggle == null || tvThemeStatus == null) return;

        int primary = requireContext().getColor(R.color.primary);

        tvThemeStatus.setText(dark ? "Dark" : "Light");
        tvThemeStatus.setTextColor(primary);

        btnThemeToggle.setText(dark ? "🌙 Dark" : "☀ Light");
        btnThemeToggle.setStrokeColorResource(R.color.primary);
        btnThemeToggle.setStrokeWidth(2);
        btnThemeToggle.setTextColor(primary);
        btnThemeToggle.setIconTintResource(R.color.primary);

        btnThemeToggle.animate()
                .scaleX(1.02f).scaleY(1.02f).setDuration(120)
                .withEndAction(() -> btnThemeToggle.animate()
                        .scaleX(1f).scaleY(1f).setDuration(120).start())
                .start();
    }

    private void setChildTextColors(LinearLayout layout, int color) {
        for (int i = 0; i < layout.getChildCount(); i++) {
            View child = layout.getChildAt(i);
            if (child instanceof TextView) ((TextView) child).setTextColor(color);
        }
    }

    private void applyThemeToView(View root, boolean dark) {
        int bgPrimary     = dark ? 0xFF07090D : 0xFFF0F4F8;
        int bgSecondary   = dark ? 0xFF11151D : 0xFFFFFFFF;
        int bgCard        = dark ? 0xFF121720 : 0xFFFFFFFF;
        int textPrimary   = dark ? 0xFFF5F7FA : 0xFF0D1117;
        int textSecondary = dark ? 0xFF96A3B5 : 0xFF4A5568;
        int colorPrimary  = dark ? 0xFF22D3EE : 0xFF4DA3FF;
        int colorBorder   = dark ? 0xFF2A303A : 0xFFE2E8F0;

        // רקע הכרטיסייה הראשית
        root.setBackgroundColor(bgPrimary);

        // כל ה-CardView ברקע bg_card
        applyToAllCards(root, bgCard, colorBorder);

        // כל ה-TextViews
        applyToAllTextViews(root, textPrimary, textSecondary, colorPrimary);

        // Bottom nav
        if (getActivity() != null) {
            View dockBackground = getActivity().findViewById(R.id.navigation_dock_background);
            if (dockBackground != null) {
                dockBackground.setBackgroundResource(R.drawable.bg_liquid_glass);
            }
        }
    }

    private void applyToAllCards(View root, int bgCard, int borderColor) {
        if (root instanceof com.google.android.material.card.MaterialCardView) {
            com.google.android.material.card.MaterialCardView card =
                    (com.google.android.material.card.MaterialCardView) root;
            card.setCardBackgroundColor(bgCard);
            card.setStrokeColor(borderColor);
            card.setStrokeWidth(Math.round(1 * getResources().getDisplayMetrics().density));
        } else if (root instanceof androidx.cardview.widget.CardView) {
            ((androidx.cardview.widget.CardView) root).setCardBackgroundColor(bgCard);
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyToAllCards(group.getChildAt(i), bgCard, borderColor);
            }
        }
    }

    private void applyToAllTextViews(View root, int textPrimary, int textSecondary, int colorPrimary) {
        if (root instanceof android.widget.TextView) {
            android.widget.TextView tv = (android.widget.TextView) root;
            String tag = tv.getTag() != null ? tv.getTag().toString() : "";
            if ("muted".equals(tag)) {
                tv.setTextColor(textSecondary);
            } else if ("accent".equals(tag)) {
                tv.setTextColor(colorPrimary);
            } else {
                tv.setTextColor(textPrimary);
            }
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyToAllTextViews(group.getChildAt(i), textPrimary, textSecondary, colorPrimary);
            }
        }
    }
}