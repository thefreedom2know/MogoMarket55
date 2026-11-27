package com.mogomarket.app;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;


public class ForgotPasswordActivity extends AppCompatActivity {

    private static final String TAG           = "ForgotPassword";

    private EditText    editTextEmail;
    private Button      btnSendReset;
    private ProgressBar progressBar;
    private TextView    tvBackToLogin;
    private FirebaseAuth mAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdgeHelper.enable(this);
        setContentView(R.layout.activity_forgot_password);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(systemBars.left, systemBars.top,
                    systemBars.right, systemBars.bottom);
            return insets;
        });

        mAuth         = FirebaseAuth.getInstance();
        editTextEmail = findViewById(R.id.editTextForgotEmail);
        btnSendReset  = findViewById(R.id.btnSendReset);
        progressBar   = findViewById(R.id.progressBarForgot);
        tvBackToLogin = findViewById(R.id.tvBackToLogin);

        String prefillEmail = getIntent().getStringExtra("email");
        if (prefillEmail != null && !prefillEmail.isEmpty()) {
            editTextEmail.setText(prefillEmail);
            editTextEmail.setSelection(prefillEmail.length());
        }

        btnSendReset.setOnClickListener(v -> sendResetEmail());
        tvBackToLogin.setOnClickListener(v -> finish());
    }

    /**
     * Firebase Auth sends the reset link itself (template, sender and wording are set in the
     * Firebase console under Authentication > Templates). No email password lives in the app.
     */
    private void sendResetEmail() {
        String email = editTextEmail.getText().toString().trim();

        if (TextUtils.isEmpty(email)) {
            editTextEmail.setError("Enter your email address");
            editTextEmail.requestFocus();
            return;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            editTextEmail.setError("Enter a valid email address");
            editTextEmail.requestFocus();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        btnSendReset.setEnabled(false);

        mAuth.useAppLanguage();
        mAuth.sendPasswordResetEmail(email)
                .addOnCompleteListener(task -> {
                    progressBar.setVisibility(View.GONE);
                    btnSendReset.setEnabled(true);
                    if (task.isSuccessful()) {
                        Toast.makeText(this,
                                "If this email has an account, a reset link was sent. "
                                        + "Check your inbox (and spam folder).",
                                Toast.LENGTH_LONG).show();
                        finish();
                        return;
                    }
                    Exception error = task.getException();
                    Log.e(TAG, "Password reset failed", error);
                    String msg;
                    if (error instanceof FirebaseAuthInvalidUserException) {
                        // Same answer as success, so the screen does not reveal who has an account.
                        Toast.makeText(this,
                                "If this email has an account, a reset link was sent. "
                                        + "Check your inbox (and spam folder).",
                                Toast.LENGTH_LONG).show();
                        finish();
                        return;
                    } else if (error instanceof FirebaseTooManyRequestsException) {
                        msg = "Too many attempts. Please try again later.";
                    } else if (error instanceof FirebaseNetworkException) {
                        msg = "No internet connection. Please try again.";
                    } else {
                        msg = "Could not send the reset email. Please try again.";
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                });
    }
}
