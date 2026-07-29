package com.ggdmod;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final String SECRET_KEY = "Admin-ca93c0f26fd972a2ab58afe202e89";
    private static final String PREF_NAME = "ggdmod_prefs";
    private static final String PREF_AUTHENTICATED = "authenticated";
    private static final int REQ_OVERLAY = 1001;
    private static final int REQ_USAGE = 1002;
    private static final int REQ_NOTIFICATION = 1003;

    private static final String GITHUB_REPO_URL    = "https://github.com/aivelaud/flexclientggd";
    private static final String GITHUB_ACTIONS_URL = "https://github.com/aivelaud/flexclientggd/actions";

    private SharedPreferences prefs;
    private LinearLayout llSetup, llReady;
    private Button btnStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs   = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        llSetup = findViewById(R.id.ll_setup);
        llReady = findViewById(R.id.ll_ready);
        btnStart = findViewById(R.id.btn_start);

        // Animate logo
        ImageView logo = findViewById(R.id.iv_logo);
        logo.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(800)
            .setStartDelay(200).start();

        // Android 13+ requires POST_NOTIFICATIONS at runtime
        requestNotificationPermissionIfNeeded();

        if (!prefs.getBoolean(PREF_AUTHENTICATED, false)) {
            showKeyDialog();
        } else {
            updateUI();
        }

        btnStart.setOnClickListener(v -> {
            if (!hasOverlayPermission()) {
                requestOverlayPermission();
            } else {
                startOverlayService();
            }
        });

        Button btnLogout = findViewById(R.id.btn_logout);
        if (btnLogout != null) {
            btnLogout.setOnClickListener(v -> {
                prefs.edit().putBoolean(PREF_AUTHENTICATED, false).apply();
                stopService(new Intent(this, OverlayService.class));
                recreate();
            });
        }

        CardView cardGithub = findViewById(R.id.card_github);
        if (cardGithub != null) cardGithub.setOnClickListener(v -> openUrl(GITHUB_REPO_URL));

        CardView cardActions = findViewById(R.id.card_actions);
        if (cardActions != null) cardActions.setOnClickListener(v -> openUrl(GITHUB_ACTIONS_URL));
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Tarayici acilamadi", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs.getBoolean(PREF_AUTHENTICATED, false)) {
            updateUI();
        }
    }

    private void showKeyDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_key, null);
        EditText etKey  = dialogView.findViewById(R.id.et_key);
        TextView tvError = dialogView.findViewById(R.id.tv_error);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create();
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        dialogView.findViewById(R.id.btn_enter).setOnClickListener(v -> {
            String entered = etKey.getText().toString().trim();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(etKey.getWindowToken(), 0);

            if (SECRET_KEY.equals(entered)) {
                prefs.edit().putBoolean(PREF_AUTHENTICATED, true).apply();
                dialog.dismiss();
                tvError.setTextColor(getColor(R.color.green));
                tvError.setText(getString(R.string.key_ok));
                tvError.setVisibility(View.VISIBLE);
                updateUI();
            } else {
                tvError.setTextColor(getColor(R.color.red));
                tvError.setText(getString(R.string.key_wrong));
                tvError.setVisibility(View.VISIBLE);
                etKey.animate().translationX(-15f).setDuration(50)
                    .withEndAction(() -> etKey.animate().translationX(15f).setDuration(50)
                    .withEndAction(() -> etKey.animate().translationX(0f).setDuration(50).start())
                    .start()).start();
            }
        });

        dialog.show();
    }

    private void updateUI() {
        boolean hasOverlay = hasOverlayPermission();
        boolean hasUsage   = hasUsagePermission();
        boolean allOk      = hasOverlay && hasUsage;

        if (allOk) {
            llSetup.setVisibility(View.GONE);
            llReady.setVisibility(View.VISIBLE);
        } else {
            llSetup.setVisibility(View.VISIBLE);
            llReady.setVisibility(View.GONE);
        }

        View cardOverlay = findViewById(R.id.card_overlay);
        if (cardOverlay != null) {
            View tick1 = cardOverlay.findViewById(R.id.tick_overlay);
            if (tick1 != null) tick1.setVisibility(hasOverlay ? View.VISIBLE : View.GONE);
            if (!hasOverlay) cardOverlay.setOnClickListener(v2 -> requestOverlayPermission());
        }

        View cardUsage = findViewById(R.id.card_usage);
        if (cardUsage != null) {
            View tick2 = cardUsage.findViewById(R.id.tick_usage);
            if (tick2 != null) tick2.setVisibility(hasUsage ? View.VISIBLE : View.GONE);
            if (!hasUsage) cardUsage.setOnClickListener(v2 -> requestUsagePermission());
        }

        View cardAccessibility = findViewById(R.id.card_accessibility);
        if (cardAccessibility != null) {
            cardAccessibility.setOnClickListener(v2 -> requestAccessibilityPermission());
        }
    }

    private boolean hasOverlayPermission() {
        return Settings.canDrawOverlays(this);
    }

    private boolean hasUsagePermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    // ── Permission requests ───────────────────────────────────────────────

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
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName()));
        startActivityForResult(intent, REQ_OVERLAY);
    }

    private void requestUsagePermission() {
        startActivityForResult(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), REQ_USAGE);
    }

    private void requestAccessibilityPermission() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void startOverlayService() {
        // Önceki instance'ı durdur — duplicate overlay view'ları önler
        stopService(new Intent(this, OverlayService.class));
        Intent intent = new Intent(this, OverlayService.class);
        startForegroundService(intent);
        Toast.makeText(this, "GGD Mod acik - Oyunu baslatabilirsin", Toast.LENGTH_LONG).show();
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        updateUI();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // POST_NOTIFICATIONS yanıtı — gerek yoksa yoksay
    }
}
