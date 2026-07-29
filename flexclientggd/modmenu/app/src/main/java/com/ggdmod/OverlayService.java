package com.ggdmod;

import android.app.*;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.animation.*;
import android.widget.*;
import androidx.core.app.NotificationCompat;
import java.util.*;

/**
 * Floating overlay service.
 * • Shows a draggable adaptive-shaped FAB over GGD.
 * • Tap → expand mod menu panel with animations.
 * • Hides automatically when GGD goes to background.
 *
 * FIX: onCreate() now proactively checks if GGD is already in the foreground
 * using UsageStatsManager, so the FAB appears even when the game was open
 * before the service started (the common case).
 */
public class OverlayService extends Service {

    private static final String CHANNEL_ID = "ggd_mod_overlay";
    private static final int NOTIF_ID = 42;

    private WindowManager wm;
    private View fabView, menuView;
    private WindowManager.LayoutParams fabParams, menuParams;
    private boolean menuOpen = false;
    private boolean ggdActive = false;
    private ModEngine engine;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver gameReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (GameDetectorService.ACTION_GGD_FOREGROUND.equals(action)) {
                ggdActive = true;
                mainHandler.post(() -> showFab());
            } else if (GameDetectorService.ACTION_GGD_BACKGROUND.equals(action)) {
                ggdActive = false;
                mainHandler.post(() -> hideAll());
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        engine = ModEngine.get(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
        setupFab();
        setupMenu();
        registerGameReceiver();

        // FIX: Check if GGD is already in the foreground right now.
        // If so, show the FAB immediately without waiting for an accessibility event
        // (which won't fire when GGD is already running when the service starts).
        mainHandler.postDelayed(() -> {
            if (!ggdActive && isGgdInForeground()) {
                ggdActive = true;
                showFab();
            }
        }, 600);
    }

    // ── FAB (floating icon) ──────────────────────────────────────────────

    private void setupFab() {
        // FIX: Use ContextThemeWrapper so theme attrs (?attr/...) resolve correctly in a Service.
        android.view.ContextThemeWrapper ctx =
                new android.view.ContextThemeWrapper(this, R.style.Theme_GGDMod);
        fabView = LayoutInflater.from(ctx).inflate(R.layout.overlay_fab, null);
        fabParams = new WindowManager.LayoutParams(
            dpToPx(64), dpToPx(64),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FIX: FLAG_NOT_FOCUSABLE is required for overlays on top of full-screen/Unity games.
            // FLAG_LAYOUT_IN_SCREEN removed — it's redundant and can push the overlay behind
            // the game window on Samsung Galaxy devices.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        fabParams.gravity = Gravity.TOP | Gravity.START;
        fabParams.x = 30;
        fabParams.y = 300;
        // FIX: Wrap addView in try-catch; BadTokenException (permission not yet effective,
        // Samsung system race) must not crash the service silently.
        try {
            wm.addView(fabView, fabParams);
        } catch (Exception e) {
            fabView = null;
            return;
        }
        fabView.setVisibility(View.GONE);

        // Drag
        fabView.setOnTouchListener(new DragTouchListener());

        // Tap → toggle menu
        fabView.setOnClickListener(v -> {
            if (menuOpen) closeMenu();
            else openMenu();
        });
    }

    // ── Mod Menu panel ───────────────────────────────────────────────────

    private void setupMenu() {
        // FIX: Use ContextThemeWrapper so Material theme attrs resolve in a Service.
        android.view.ContextThemeWrapper ctx =
                new android.view.ContextThemeWrapper(this, R.style.Theme_GGDMod);
        menuView = LayoutInflater.from(ctx).inflate(R.layout.overlay_menu, null);
        menuParams = new WindowManager.LayoutParams(
            dpToPx(320), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FIX: FLAG_NOT_FOCUSABLE is the PRIMARY bug fix.
            // Without it, adding this view on top of a full-screen Unity game (GGD)
            // throws BadTokenException on Samsung Galaxy devices (Android 12+),
            // crashing the service.  FLAG_LAYOUT_IN_SCREEN removed for the same reason.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        menuParams.gravity = Gravity.TOP | Gravity.START;
        menuParams.x = 20;
        menuParams.y = 80;
        // FIX: Wrap addView in try-catch to prevent an uncaught BadTokenException
        // from killing the service (seen as Device Care crash on Samsung).
        try {
            wm.addView(menuView, menuParams);
        } catch (Exception e) {
            menuView = null;
            return;
        }
        menuView.setVisibility(View.GONE);

        // Close button
        View btnClose = menuView.findViewById(R.id.btn_close_menu);
        if (btnClose != null) btnClose.setOnClickListener(v -> closeMenu());

        // Build mod rows
        buildModRows();
    }

    private void buildModRows() {
        setupTabButtons();
        showTab(R.id.tab_movement);
    }

    private void setupTabButtons() {
        int[] tabBtns  = {R.id.btn_tab_movement, R.id.btn_tab_role,
                          R.id.btn_tab_vision,   R.id.btn_tab_kill,
                          R.id.btn_tab_task,     R.id.btn_tab_misc};
        int[] tabPanels = {R.id.tab_movement, R.id.tab_role,
                           R.id.tab_vision,   R.id.tab_kill,
                           R.id.tab_task,     R.id.tab_misc};

        for (int i = 0; i < tabBtns.length; i++) {
            final int panelId = tabPanels[i];
            View btn = menuView.findViewById(tabBtns[i]);
            if (btn != null) btn.setOnClickListener(v -> showTab(panelId));
        }

        bindMovementTab();
        bindRoleTab();
        bindVisionTab();
        bindKillTab();
        bindTaskTab();
        bindMiscTab();
    }

    private void showTab(int panelId) {
        int[] panels = {R.id.tab_movement, R.id.tab_role, R.id.tab_vision,
                        R.id.tab_kill,     R.id.tab_task, R.id.tab_misc};
        for (int id : panels) {
            View p = menuView.findViewById(id);
            if (p != null) p.setVisibility(id == panelId ? View.VISIBLE : View.GONE);
        }
    }

    // ── Tab: Hareket ─────────────────────────────────────────────────────

    private void bindMovementTab() {
        bindToggle(R.id.toggle_speed,       ModEngine.MOD_SPEED_ENABLED, "Hız Hack");
        bindSlider(R.id.slider_speed,       ModEngine.MOD_SPEED_VALUE, 1f, 10f, "x");
        bindToggle(R.id.toggle_ghost_speed, ModEngine.MOD_GHOST_SPEED_ENABLED, "Hayalet Hızı");
        bindSlider(R.id.slider_ghost_speed, ModEngine.MOD_GHOST_SPEED_VALUE, 1f, 5f, "x");
        bindToggle(R.id.toggle_teleport,    ModEngine.MOD_TELEPORT, "Işınlanma (Dokunarak)");
    }

    // ── Tab: Rol ─────────────────────────────────────────────────────────

    private void bindRoleTab() {
        bindToggle(R.id.toggle_always_imposter, ModEngine.MOD_ALWAYS_IMPOSTER, "Her Zaman Katil");
        bindToggle(R.id.toggle_always_crewmate, ModEngine.MOD_ALWAYS_CREWMATE, "Her Zaman Mürettebat");
        bindToggle(R.id.toggle_see_roles,       ModEngine.MOD_SEE_ROLES,       "Rolleri Gör");
        bindToggle(R.id.toggle_show_dead,       ModEngine.MOD_SHOW_DEAD,       "Ölüleri Gör");
    }

    // ── Tab: Görüş ───────────────────────────────────────────────────────

    private void bindVisionTab() {
        bindToggle(R.id.toggle_vision,   ModEngine.MOD_VISION_ENABLED, "Görüş Genişlet");
        bindSlider(R.id.slider_vision,   ModEngine.MOD_VISION_VALUE, 1f, 20f, "x");
        bindToggle(R.id.toggle_wallhack, ModEngine.MOD_WALLHACK, "Duvardan Görüş");
        bindToggle(R.id.toggle_radar,    ModEngine.MOD_RADAR,    "Mini Radar");
    }

    // ── Tab: Öldürme ─────────────────────────────────────────────────────

    private void bindKillTab() {
        bindToggle(R.id.toggle_no_kill_cd, ModEngine.MOD_NO_KILL_COOLDOWN, "Sıfır Bekleme Süresi");
        bindToggle(R.id.toggle_instant_kill, ModEngine.MOD_INSTANT_KILL,   "Anında Öldür");
        bindSlider(R.id.slider_kill_cd,    ModEngine.MOD_KILL_COOLDOWN, 0f, 60f, "s");
        bindToggle(R.id.toggle_silent_kill, ModEngine.MOD_SILENT_KILL,  "Sessiz Öldürme");
        bindToggle(R.id.toggle_freeze,      ModEngine.MOD_FREEZE_OTHERS,"Diğerlerini Dondur");
    }

    // ── Tab: Görev ───────────────────────────────────────────────────────

    private void bindTaskTab() {
        bindToggle(R.id.toggle_auto_task, ModEngine.MOD_AUTO_TASK, "Otomatik Görev Tamamla");
        bindSlider(R.id.slider_task_speed, ModEngine.MOD_TASK_SPEED, 1f, 5f, "x");
        bindToggle(R.id.toggle_inf_emergency, ModEngine.MOD_INF_EMERGENCY, "Sonsuz Acil Toplantı");
        bindToggle(R.id.toggle_emergency_spam, ModEngine.MOD_EMERGENCY_SPAM, "Toplantı Spam");
        bindToggle(R.id.toggle_see_votes, ModEngine.MOD_SEE_VOTES, "Oyları Gör");
    }

    // ── Tab: Diğer ───────────────────────────────────────────────────────

    private void bindMiscTab() {
        bindToggle(R.id.toggle_anti_kick,  ModEngine.MOD_ANTI_KICK, "Anti Kick/Ban");
        bindToggle(R.id.toggle_unlock_all, ModEngine.MOD_UNLOCK_ALL, "Tüm Kıyafetler Açık");
        bindToggle(R.id.toggle_name_spoof, ModEngine.MOD_NAME_SPOOF, "İsim Değiştir");

        EditText etName = menuView.findViewById(R.id.et_name_spoof);
        if (etName != null) {
            etName.setText(engine.getString(ModEngine.MOD_NAME_VALUE));
            etName.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) engine.setString(ModEngine.MOD_NAME_VALUE, etName.getText().toString());
            });
        }
    }

    // ── Helper: bind toggle ──────────────────────────────────────────────

    private void bindToggle(int switchId, String modKey, String label) {
        View row = menuView.findViewById(switchId);
        if (row == null) return;

        TextView tvLabel = row.findViewWithTag("label");
        Switch sw = row.findViewWithTag("switch");

        if (tvLabel != null) tvLabel.setText(label);
        if (sw != null) {
            sw.setChecked(engine.getBool(modKey));
            sw.setOnCheckedChangeListener((b, checked) -> {
                engine.setBool(modKey, checked);
                updateHeader();
            });
        }
    }

    private void bindSlider(int sliderId, String modKey, float min, float max, String unit) {
        View row = menuView.findViewById(sliderId);
        if (row == null) return;

        SeekBar seekBar = row.findViewWithTag("seekbar");
        TextView tvVal  = row.findViewWithTag("value");

        if (seekBar == null) return;

        float cur = engine.getFloat(modKey);
        int progress = (int) (((cur - min) / (max - min)) * 100f);
        seekBar.setProgress(progress);

        if (tvVal != null) tvVal.setText(String.format("%.1f%s", cur, unit));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean byUser) {
                float val = min + ((max - min) * p / 100f);
                engine.setFloat(modKey, val);
                if (tvVal != null) tvVal.setText(String.format("%.1f%s", val, unit));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private void updateHeader() {
        if (menuView == null) return;
        TextView tvCount = menuView.findViewById(R.id.tv_mod_count);
        if (tvCount != null) {
            int count = engine.getActiveModCount();
            tvCount.setText(count > 0 ? count + " MOD AKTİF" : "MOD KAPALI");
        }
    }

    // ── Show / Hide / Animate ────────────────────────────────────────────

    private void showFab() {
        if (fabView == null) return;
        fabView.setVisibility(View.VISIBLE);
        fabView.setScaleX(0f); fabView.setScaleY(0f);
        fabView.animate().scaleX(1f).scaleY(1f).setDuration(300)
            .setInterpolator(new OvershootInterpolator(1.5f)).start();
    }

    private void hideAll() {
        if (fabView == null) return;
        if (menuOpen) closeMenu();
        fabView.animate().scaleX(0f).scaleY(0f).setDuration(200)
            .withEndAction(() -> fabView.setVisibility(View.GONE)).start();
    }

    private void openMenu() {
        if (menuView == null || fabView == null) return;
        menuOpen = true;
        menuView.setVisibility(View.VISIBLE);
        menuView.setScaleX(0.3f); menuView.setScaleY(0.3f); menuView.setAlpha(0f);
        menuView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(350)
            .setInterpolator(new OvershootInterpolator(1.2f)).start();
        // Pulse fab
        fabView.animate().scaleX(0.85f).scaleY(0.85f).setDuration(150)
            .withEndAction(() -> fabView.animate().scaleX(1f).scaleY(1f).setDuration(150).start())
            .start();
        updateHeader();
    }

    private void closeMenu() {
        if (menuView == null) { menuOpen = false; return; }
        menuOpen = false;
        menuView.animate().scaleX(0.3f).scaleY(0.3f).alpha(0f).setDuration(250)
            .setInterpolator(new AccelerateInterpolator())
            .withEndAction(() -> menuView.setVisibility(View.GONE))
            .start();
    }

    // ── UsageStats: GGD ön planda mı? ────────────────────────────────────

    private boolean isGgdInForeground() {
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

    // ── Drag listener ────────────────────────────────────────────────────

    private class DragTouchListener implements View.OnTouchListener {
        private int initX, initY, touchX, touchY;
        private long downTime;
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initX = fabParams.x; initY = fabParams.y;
                    touchX = (int) event.getRawX(); touchY = (int) event.getRawY();
                    downTime = System.currentTimeMillis();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) event.getRawX() - touchX;
                    int dy = (int) event.getRawY() - touchY;
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) moved = true;
                    fabParams.x = initX + dx;
                    fabParams.y = initY + dy;
                    wm.updateViewLayout(fabView, fabParams);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved && System.currentTimeMillis() - downTime < 300) {
                        v.performClick();
                    }
                    return true;
            }
            return false;
        }
    }

    // ── Broadcast receiver ───────────────────────────────────────────────

    private void registerGameReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(GameDetectorService.ACTION_GGD_FOREGROUND);
        filter.addAction(GameDetectorService.ACTION_GGD_BACKGROUND);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gameReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(gameReceiver, filter);
        }
    }

    // ── Notification ─────────────────────────────────────────────────────

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "GGD Mod Overlay", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("GGD mod menü overlay çalışıyor");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, OverlayService.class);
        stopIntent.setAction("STOP");
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GGD Mod Menü")
            .setContentText("Goose Goose Duck açıldığında mod menü görünecek")
            .setSmallIcon(android.R.drawable.star_big_on)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Kapat", stopPi)
            .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(gameReceiver); } catch (Exception ignored) {}
        if (fabView != null) try { wm.removeView(fabView); } catch (Exception ignored) {}
        if (menuView != null) try { wm.removeView(menuView); } catch (Exception ignored) {}
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
