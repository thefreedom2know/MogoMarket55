package com.mogomarket.app;

import android.content.Intent;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.os.Bundle;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * SplashActivity — מסך ראשון שנפתח.
 * בודק אם המשתמש מחובר ומנווט בהתאם:
 *  - מחובר  → MainActivity
 *  - לא מחובר → AuthLogin
 * כך נמנע המסך הריק הנגרם מהעיכוב של onStart ב-AuthLogin.
 */
public class SplashActivity extends AppCompatActivity {

    private android.animation.AnimatorSet splashAnimation;
    private boolean navigated;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdgeHelper.enable(this);
        Window window = getWindow();
        window.setStatusBarColor(android.graphics.Color.BLACK);
        window.setNavigationBarColor(android.graphics.Color.BLACK);
        WindowInsetsControllerCompat bars =
                new WindowInsetsControllerCompat(window, window.getDecorView());
        bars.setAppearanceLightStatusBars(false);
        bars.setAppearanceLightNavigationBars(false);
        setContentView(R.layout.activity_splash);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.splashRoot), (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(systemBars.left, systemBars.top,
                    systemBars.right, systemBars.bottom);
            return insets;
        });

        View logo = findViewById(R.id.splashLogo);
        View glow = findViewById(R.id.splashGlow);
        View title = findViewById(R.id.splashTitle);
        View tagline = findViewById(R.id.splashTagline);
        SplashGraphView graph = findViewById(R.id.splashGraph);
        android.view.animation.Interpolator smooth =
                new android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f);

        // Logo and its glow fade and settle in (about 0.45 s).
        ObjectAnimator logoFade = ObjectAnimator.ofFloat(logo, View.ALPHA, 0f, 1f);
        logoFade.setDuration(350L);
        ObjectAnimator logoScaleX = ObjectAnimator.ofFloat(logo, View.SCALE_X, 0.82f, 1f);
        ObjectAnimator logoScaleY = ObjectAnimator.ofFloat(logo, View.SCALE_Y, 0.82f, 1f);
        logoScaleX.setDuration(450L);
        logoScaleY.setDuration(450L);
        logoScaleX.setInterpolator(new android.view.animation.OvershootInterpolator(1.2f));
        logoScaleY.setInterpolator(new android.view.animation.OvershootInterpolator(1.2f));

        ObjectAnimator glowFade = ObjectAnimator.ofFloat(glow, View.ALPHA, 0f, 1f);
        ObjectAnimator glowScaleX = ObjectAnimator.ofFloat(glow, View.SCALE_X, 0.7f, 1f);
        ObjectAnimator glowScaleY = ObjectAnimator.ofFloat(glow, View.SCALE_Y, 0.7f, 1f);
        glowFade.setDuration(450L);
        glowScaleX.setDuration(550L);
        glowScaleY.setDuration(550L);
        glowScaleX.setInterpolator(smooth);
        glowScaleY.setInterpolator(smooth);

        // The chart line draws across the bottom while the text appears.
        ValueAnimator graphBuild = ValueAnimator.ofFloat(0f, 1f);
        graphBuild.setStartDelay(120L);
        graphBuild.setDuration(720L);
        graphBuild.setInterpolator(smooth);
        graphBuild.addUpdateListener(value ->
                graph.setProgress((float) value.getAnimatedValue()));

        ObjectAnimator titleFade = ObjectAnimator.ofFloat(title, View.ALPHA, 0f, 1f);
        ObjectAnimator titleRise = ObjectAnimator.ofFloat(title, View.TRANSLATION_Y, 14f, 0f);
        titleFade.setStartDelay(180L);
        titleRise.setStartDelay(180L);
        titleFade.setDuration(340L);
        titleRise.setDuration(340L);
        titleRise.setInterpolator(smooth);

        ObjectAnimator taglineFade = ObjectAnimator.ofFloat(tagline, View.ALPHA, 0f, 1f);
        taglineFade.setStartDelay(320L);
        taglineFade.setDuration(300L);

        android.animation.AnimatorSet sequence = new android.animation.AnimatorSet();
        sequence.playTogether(logoFade, logoScaleX, logoScaleY, glowFade, glowScaleX, glowScaleY,
                graphBuild, titleFade, titleRise, taglineFade);
        sequence.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                navigate();
            }
        });
        sequence.start();
        splashAnimation = sequence;

        // Tap anywhere to skip.
        findViewById(R.id.splashRoot).setOnClickListener(v -> navigate());
    }

    private void navigate() {
        if (navigated || isFinishing()) return;
        navigated = true;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

        Intent intent;
        if (user != null) {
            // משתמש מחובר → עבור ישירות לאפליקציה
            intent = new Intent(this, MainActivity.class);
        } else {
            // לא מחובר → מסך התחברות
            intent = new Intent(this, AuthLogin.class);
        }

        startActivity(intent);
        overridePendingTransition(R.anim.splash_enter, R.anim.splash_exit);
        finish(); // סגור את SplashActivity כדי שלא יהיה אפשר לחזור אליו
    }

    @Override
    protected void onDestroy() {
        if (splashAnimation != null) splashAnimation.cancel();
        super.onDestroy();
    }
}
