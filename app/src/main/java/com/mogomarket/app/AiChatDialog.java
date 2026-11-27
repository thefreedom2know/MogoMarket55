package com.mogomarket.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat with the AI. Keeps the conversation (per key, e.g. per symbol) while the app is
 * open, so closing and reopening the chat continues where it stopped.
 */
public class AiChatDialog {

    private static final String SYSTEM_PROMPT =
            "אתה יועץ פיננסי מקצועי בתוך אפליקציית מסחר. ענה תמיד בעברית, תמציתי, ברור ומסודר. "
                    + "הסתמך רק על הנתונים שקיבלת ואל תמציא מספרים. הדגש סיכונים, "
                    + "והזכר בקצרה שזה אינו ייעוץ השקעות כשמדובר בהמלצה.";

    private static final String[] CHART_SUGGESTIONS = {
            "נתח את הגרף", "מה הסיכונים?", "איפה תמיכה והתנגדות?", "מה המגמה?"};
    private static final String[] GENERAL_SUGGESTIONS = {
            "מה הדפוסים בעסקאות שלי?", "איך אפשר להשתפר?", "מה הטעויות החוזרות?"};

    private static final int MAX_STORED_TURNS = 40;
    private static final int MAX_SENT_TURNS = 12;
    private static final Map<String, List<LLMService.Turn>> HISTORY = new HashMap<>();

    /** Entry point used by the closed-trades screen. */
    public static void show(Context context, String defaultPrompt, String initialContext) {
        showChat(context, "AI", "trades", initialContext, defaultPrompt, GENERAL_SUGGESTIONS);
    }

    /** Entry point used by the chart screen. */
    public static void showForChart(Context context, String symbol, String chartContext) {
        showChat(context, "AI · " + symbol, "chart:" + symbol, chartContext,
                "נתח את הגרף והסבר את המגמה והסיכונים.", CHART_SUGGESTIONS);
    }

    public static void showChat(final Context context, String title, final String historyKey,
                                final String contextText, final String autoPrompt,
                                String[] suggestions) {
        final float dp = context.getResources().getDisplayMetrics().density;
        final LLMService llm = new LLMService();

        List<LLMService.Turn> stored = HISTORY.get(historyKey);
        final List<LLMService.Turn> turns = stored != null ? stored : new ArrayList<>();
        HISTORY.put(historyKey, turns);

        // ---- Layout ----
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Math.round(16 * dp), Math.round(14 * dp), Math.round(16 * dp),
                Math.round(12 * dp));
        root.setBackground(rounded(context.getColor(R.color.pf_0b0f16), 24 * dp,
                context.getColor(R.color.pf_3322d3ee), dp));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = new TextView(context);
        titleView.setText("✦ " + title);
        titleView.setTextSize(18f);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setTextColor(context.getColor(R.color.pf_22d3ee));
        header.addView(titleView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = new TextView(context);
        clear.setText("נקה");
        clear.setTextSize(13f);
        clear.setTextColor(context.getColor(R.color.text_secondary));
        clear.setPadding(Math.round(10 * dp), Math.round(6 * dp), Math.round(10 * dp),
                Math.round(6 * dp));
        header.addView(clear);
        root.addView(header);

        final ScrollView scroll = new ScrollView(context);
        final LinearLayout messages = new LinearLayout(context);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(0, Math.round(10 * dp), 0, Math.round(6 * dp));
        scroll.addView(messages);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Quick suggestions
        HorizontalScrollView chipScroll = new HorizontalScrollView(context);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chipScroll.addView(chips);
        root.addView(chipScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Input row
        LinearLayout inputRow = new LinearLayout(context);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText input = new EditText(context);
        input.setHint("שאל על הגרף...");
        input.setHintTextColor(context.getColor(R.color.text_secondary));
        input.setTextColor(context.getColor(R.color.text_primary));
        input.setTextSize(15f);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMaxLines(4);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        input.setPadding(Math.round(14 * dp), Math.round(10 * dp), Math.round(14 * dp),
                Math.round(10 * dp));
        input.setBackground(rounded(0x22808080, 22 * dp, 0, 0));
        inputRow.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final MaterialButton send = new MaterialButton(context);
        send.setText("שלח");
        send.setAllCaps(false);
        send.setCornerRadius(Math.round(22 * dp));
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(46 * dp));
        sendParams.setMarginStart(Math.round(8 * dp));
        inputRow.addView(send, sendParams);
        LinearLayout.LayoutParams inputRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputRowParams.topMargin = Math.round(8 * dp);
        root.addView(inputRow, inputRowParams);

        final AlertDialog dialog = new AlertDialog.Builder(context).setView(root).create();

        // ---- Behaviour ----
        final View[] pending = new View[1];
        final boolean[] busy = {false};

        final Runnable scrollDown = () -> scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));


        final java.util.function.Consumer<String> submit = text -> {
            String q = text == null ? "" : text.trim();
            if (q.isEmpty() || busy[0]) return;
            if (!LLMService.isConfigured()) {
                messages.addView(bubble(context, dp, false,
                        "צריך להתחבר לחשבון כדי להשתמש בעוזר ה-AI."));
                scrollDown.run();
                return;
            }
            busy[0] = true;
            send.setEnabled(false);
            input.setText("");
            turns.add(new LLMService.Turn("user", q));
            trim(turns);
            messages.addView(bubble(context, dp, true, q));
            pending[0] = bubble(context, dp, false, "מקליד...");
            messages.addView(pending[0]);
            scrollDown.run();

            String system = SYSTEM_PROMPT + (contextText == null || contextText.trim().isEmpty()
                    ? "" : "\n\nהקשר נוכחי:\n" + contextText);
            List<LLMService.Turn> recent = new ArrayList<>(turns.subList(
                    Math.max(0, turns.size() - MAX_SENT_TURNS), turns.size()));
            llm.chat(system, recent, new LLMService.LLMCallback() {
                @Override
                public void onSuccess(String result) {
                    turns.add(new LLMService.Turn("assistant", result));
                    trim(turns);
                    finish(result);
                }

                @Override
                public void onFailure(Throwable t) {
                    // The failed question stays out of the history so it can be retried.
                    if (!turns.isEmpty()) turns.remove(turns.size() - 1);
                    finish("⚠ " + (t.getMessage() == null ? "שגיאה" : t.getMessage()));
                }

                private void finish(String text) {
                    if (pending[0] != null) messages.removeView(pending[0]);
                    pending[0] = null;
                    messages.addView(bubble(context, dp, false, text));
                    busy[0] = false;
                    send.setEnabled(true);
                    scrollDown.run();
                }
            });
        };

        send.setOnClickListener(v -> submit.accept(input.getText().toString()));
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit.accept(input.getText().toString());
                return true;
            }
            return false;
        });

        if (suggestions != null) {
            for (String s : suggestions) {
                TextView chip = new TextView(context);
                chip.setText(s);
                chip.setTextSize(13f);
                chip.setTextColor(context.getColor(R.color.pf_22d3ee));
                chip.setPadding(Math.round(12 * dp), Math.round(7 * dp), Math.round(12 * dp),
                        Math.round(7 * dp));
                chip.setBackground(rounded(0x00000000, 18 * dp,
                        context.getColor(R.color.pf_3322d3ee), dp));
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cp.setMarginEnd(Math.round(8 * dp));
                chip.setOnClickListener(v -> submit.accept(s));
                chips.addView(chip, cp);
            }
        }

        clear.setOnClickListener(v -> {
            if (busy[0]) return;
            turns.clear();
            messages.removeAllViews();
            addIntro(context, dp, messages);
        });

        // Restore the previous conversation, or start with an intro.
        if (turns.isEmpty()) {
            addIntro(context, dp, messages);
        } else {
            for (LLMService.Turn t : turns) {
                messages.addView(bubble(context, dp, "user".equals(t.role), t.text));
            }
        }

        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
            int height = Math.round(context.getResources().getDisplayMetrics().heightPixels * 0.78f);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, height);
        }
        scrollDown.run();

        // First time: send the default question right away so the button visibly does something.
        if (turns.isEmpty() && autoPrompt != null && !autoPrompt.trim().isEmpty()) {
            submit.accept(autoPrompt);
        }
    }

    private static void addIntro(Context context, float dp, LinearLayout messages) {
        messages.addView(bubble(context, dp, false,
                "היי! אפשר לשאול אותי כל שאלה על הנתונים שמוצגים כאן, או ללחוץ על אחת ההצעות למטה."));
    }

    private static void trim(List<LLMService.Turn> turns) {
        while (turns.size() > MAX_STORED_TURNS) turns.remove(0);
    }

    private static View bubble(Context context, float dp, boolean mine, String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(15f);
        tv.setTextIsSelectable(true);
        tv.setLineSpacing(3 * dp, 1f);
        tv.setTextColor(context.getColor(R.color.text_primary));
        tv.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        tv.setPadding(Math.round(14 * dp), Math.round(10 * dp), Math.round(14 * dp),
                Math.round(10 * dp));
        tv.setBackground(rounded(mine ? 0x3322D3EE : 0x22808080, 18 * dp, 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = mine ? Gravity.END : Gravity.START;
        lp.topMargin = Math.round(6 * dp);
        lp.setMarginStart(Math.round(mine ? 36 * dp : 0));
        lp.setMarginEnd(Math.round(mine ? 0 : 36 * dp));
        tv.setLayoutParams(lp);
        return tv;
    }

    private static GradientDrawable rounded(int fill, float radius, int stroke, float strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (strokeWidth > 0) d.setStroke(Math.max(1, Math.round(strokeWidth)), stroke);
        return d;
    }
}
