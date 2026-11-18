package com.mogomarket.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;

/** Chat dialog: opens with a default question already sent, and lets the user ask more. */
public class AiChatDialog {

    private static final String SYSTEM_PROMPT =
            "אתה יועץ פיננסי מקצועי. ענה תמיד בעברית, תמציתי, ברור ומסודר. "
                    + "אל תמציא נתונים שלא קיבלת. הדגש סיכונים, ובסוף ציין בשורה קצרה "
                    + "שזה אינו ייעוץ השקעות.";

    public static void show(Context context, String defaultPrompt, String initialContext) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_ai_chat, null);
        final EditText question = view.findViewById(R.id.et_question);
        final MaterialButton send = view.findViewById(R.id.btn_send);
        final ProgressBar progress = view.findViewById(R.id.progress_ai);
        final TextView response = view.findViewById(R.id.tv_response);
        response.setTextIsSelectable(true);

        if (defaultPrompt != null) question.setText(defaultPrompt);

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(view)
                .setNegativeButton("סגור", null)
                .create();
        Window window = dialog.getWindow();
        if (window != null) window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        final LLMService llm = new LLMService();

        final Runnable ask = () -> {
            String text = question.getText() == null ? "" : question.getText().toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(context, "הקלד שאלה", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!LLMService.isConfigured()) {
                response.setVisibility(View.VISIBLE);
                response.setText("לא הוגדר מפתח AI. הוסף GROQ_API_KEY ל-local.properties ובנה מחדש.");
                return;
            }
            StringBuilder prompt = new StringBuilder(SYSTEM_PROMPT).append("\n\n");
            if (initialContext != null && !initialContext.trim().isEmpty()) {
                prompt.append("הקשר:\n").append(initialContext).append("\n\n");
            }
            prompt.append("שאלה: ").append(text);

            send.setEnabled(false);
            progress.setVisibility(View.VISIBLE);
            response.setVisibility(View.GONE);
            llm.generateContent(prompt.toString(), new LLMService.LLMCallback() {
                @Override
                public void onSuccess(String result) {
                    progress.setVisibility(View.GONE);
                    send.setEnabled(true);
                    response.setText(result);
                    response.setVisibility(View.VISIBLE);
                }

                @Override
                public void onFailure(Throwable t) {
                    progress.setVisibility(View.GONE);
                    send.setEnabled(true);
                    response.setText(t.getMessage() == null ? "שגיאה" : t.getMessage());
                    response.setVisibility(View.VISIBLE);
                }
            });
        };

        send.setOnClickListener(v -> ask.run());
        dialog.show();

        // Run the default question right away so the button visibly does something.
        if (defaultPrompt != null && !defaultPrompt.trim().isEmpty()) ask.run();
    }
}
