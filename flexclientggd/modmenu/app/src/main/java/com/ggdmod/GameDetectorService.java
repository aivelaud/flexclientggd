package com.ggdmod;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import java.util.List;

/**
 * Monitors foreground app via Accessibility API.
 * When GGD becomes active → shows overlay.
 * When GGD loses focus → hides overlay.
 *
 * FIX: onServiceConnected() now checks the current foreground app immediately
 * so the overlay appears even if GGD was already open when the service started.
 * accessibility_config.xml no longer restricts packageNames, so we detect
 * background transitions too (any app coming to front means GGD went to back).
 */
public class GameDetectorService extends AccessibilityService {

    public static final String ACTION_GGD_FOREGROUND = "com.ggdmod.GGD_FOREGROUND";
    public static final String ACTION_GGD_BACKGROUND = "com.ggdmod.GGD_BACKGROUND";

    private boolean ggdWasActive = false;

    // ── Called when accessibility service is connected ────────────────────

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        // Delay slightly so OverlayService has time to register its receiver
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            boolean ggdInFront = isGgdCurrentlyInForeground();
            if (ggdInFront) {
                ggdWasActive = true;
                sendBroadcast(new Intent(ACTION_GGD_FOREGROUND));
            }
        }, 800);
    }

    // ── Window state events ───────────────────────────────────────────────

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String packageName = pkg.toString();

        // Ignore system UI / status bar / keyboard packages to reduce noise
        if (packageName.equals("com.android.systemui")
                || packageName.equals("com.android.inputmethod.latin")
                || packageName.startsWith("android")) {
            return;
        }

        boolean isGgdNow = packageName.startsWith(ModEngine.GGD_PACKAGE);

        if (isGgdNow && !ggdWasActive) {
            ggdWasActive = true;
            sendBroadcast(new Intent(ACTION_GGD_FOREGROUND));
        } else if (!isGgdNow && ggdWasActive) {
            ggdWasActive = false;
            sendBroadcast(new Intent(ACTION_GGD_BACKGROUND));
        }
    }

    @Override
    public void onInterrupt() {
        ggdWasActive = false;
    }

    // ── Helper: is GGD currently in the foreground? ───────────────────────

    private boolean isGgdCurrentlyInForeground() {
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return false;

            long now = System.currentTimeMillis();
            List<UsageStats> stats =
                    usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000L, now);
            if (stats == null || stats.isEmpty()) return false;

            String topPackage = null;
            long topTime = 0;
            for (UsageStats us : stats) {
                if (us.getLastTimeUsed() > topTime) {
                    topTime = us.getLastTimeUsed();
                    topPackage = us.getPackageName();
                }
            }
            return topPackage != null && topPackage.startsWith(ModEngine.GGD_PACKAGE);
        } catch (Exception e) {
            return false;
        }
    }
}
