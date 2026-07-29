package com.ggdmod;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Minimal launcher: requests permissions and starts the overlay service.
 * Authentication has moved into the overlay itself (Velaud GGD login screen).
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_OVERLAY      = 1001;
    private static final int REQ_USAGE        = 1002;
    private static final int REQ_NOTIFICATION = 1003;

    private static final String GITHUB_REPO_URL    = "https://github.com/aivelaud/flexclientggd";
    private static final String GITHUB_ACTIONS_URL = "https://github.com/aivelaud/flexclientggd/actions";

    private Button btnStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStart = findViewById(R.id.btn_start);

        // Animate logo in
        View logo = findViewById(R.id.iv_logo);
        if (logo != null) logo.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(700).setStartDelay(150).start();

        requestNotificationPermissionIfNeeded();
        updateUI();

        btnStart.setOnClickListener(v -> {
            if (!hasOverlayPermission()) {
                requestOverlayPermission();
            } else {
                startOverlayService();
            }
        });

        CardView cardGithub = findViewById(R.id.card_github);
        if (cardGithub != null) cardGithub.setOnClickListener(v -> openUrl(GITHUB_REPO_URL));

        CardView cardActions = findViewById(R.id.card_actions);
        if (cardActions != null) cardActions.setOnClickListener(v -> openUrl(GITHUB_ACTIONS_URL));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
    }

    // ── UI state ──────────────────────────────────────────────────────────

    private void updateUI() {
        boolean hasOverlay      = hasOverlayPermission();
        boolean hasUsage        = hasUsagePermission();
        boolean allPermissions  = hasOverlay && hasUsage;

        View llSetup = findViewById(R.id.ll_setup);
        View llReady = findViewById(R.id.ll_ready);
        if (llSetup != null) llSetup.setVisibility(allPermissions ? View.GONE  : View.VISIBLE);
        if (llReady != null) llReady.setVisibility(allPermissions ? View.VISIBLE : View.GONE);

        // Overlay permission card
        View cardOverlay = findViewById(R.id.card_overlay);
        if (cardOverlay != null) {
            View tick = cardOverlay.findViewById(R.id.tick_overlay);
            if (tick != null) tick.setVisibility(hasOverlay ? View.VISIBLE : View.GONE);
            cardOverlay.setOnClickListener(hasOverlay ? null : v -> requestOverlayPermission());
        }

        // Usage stats card
        View cardUsage = findViewById(R.id.card_usage);
        if (cardUsage != null) {
            View tick = cardUsage.findViewById(R.id.tick_usage);
            if (tick != null) tick.setVisibility(hasUsage ? View.VISIBLE : View.GONE);
            cardUsage.setOnClickListener(hasUsage ? null : v -> requestUsagePermission());
        }

        // Accessibility card (always clickable for user convenience)
        View cardAcc = findViewById(R.id.card_accessibility);
        if (cardAcc != null) cardAcc.setOnClickListener(v -> requestAccessibilityPermission());
    }

    // ── Service ───────────────────────────────────────────────────────────

    private void startOverlayService() {
        stopService(new Intent(this, OverlayService.class));
        startForegroundService(new Intent(this, OverlayService.class));
        Toast.makeText(this, "Velaud GGD acik - GGD yi baslatabilirsin", Toast.LENGTH_LONG).show();
        finish();
    }

    // ── Permissions ───────────────────────────────────────────────────────

    private boolean hasOverlayPermission() {
        return Settings.canDrawOverlays(this);
    }

    private boolean hasUsagePermission() {
        AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
            }
        }
    }

    private void requestOverlayPermission() {
        startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
    }

    private void requestUsagePermission() {
        startActivityForResult(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), REQ_USAGE);
    }

    private void requestAccessibilityPermission() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        updateUI();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "Tarayici acilamadi", Toast.LENGTH_SHORT).show();
        }
    }
}
