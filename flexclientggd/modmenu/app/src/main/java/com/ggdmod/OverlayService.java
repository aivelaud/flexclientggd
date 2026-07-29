package com.ggdmod;

import android.app.*;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.*;
import android.graphics.PixelFormat;
import android.os.*;
import android.view.*;
import android.view.animation.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.core.app.NotificationCompat;
import java.util.List;

/**
 * Velaud GGD — Overlay service.
 *
 * State machine:
 *   HIDDEN  — GGD not in foreground, nothing visible
 *   FAB     — GGD in foreground, floating icon visible
 *   LOGIN   — full-screen login overlay visible (FAB shrinks to corner)
 *   MENU    — mod menu panel visible, FAB visible
 *
 * Key fixes vs. old code:
 *   - All WindowManager views use FLAG_NOT_FOCUSABLE by default (prevents
 *     BadTokenException / invisible overlay on Samsung + Unity games).
 *   - ContextThemeWrapper ensures Material theme attrs resolve in a Service.
 *   - wm.addView() wrapped in try-catch to prevent uncaught crashes.
 *   - Login EditText dynamically removes FLAG_NOT_FOCUSABLE for keyboard.
 */
public class OverlayService extends Service {

    private static final String CHANNEL_ID  = "velaud_ggd_overlay";
    private static final int    NOTIF_ID    = 42;
    private static final String ADMIN_KEY   = "Admin-ca93c0f26fd972a2ab58afe202e89";
    private static final String PREF_NAME   = "ggdmod_prefs";
    private static final String PREF_AUTH   = "auth";

    private enum OverlayState { HIDDEN, FAB, LOGIN, MENU }

    private OverlayState state = OverlayState.HIDDEN;
    private WindowManager wm;
    private View fabView, loginView, menuView;
    private WindowManager.LayoutParams fabParams, loginParams, menuParams;
    private ModEngine engine;
    private SharedPreferences prefs;
    private boolean menuOpen = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver gameReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (GameDetectorService.ACTION_GGD_FOREGROUND.equals(action)) {
                mainHandler.post(() -> transitionTo(OverlayState.FAB));
            } else if (GameDetectorService.ACTION_GGD_BACKGROUND.equals(action)) {
                mainHandler.post(() -> transitionTo(OverlayState.HIDDEN));
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        engine = ModEngine.get(this);
        prefs  = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        wm     = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
        setupFab();
        setupLogin();
        setupMenu();
        registerGameReceiver();
        // Initial foreground check: GGD may already be open when service starts.
        mainHandler.postDelayed(() -> {
            if (state == OverlayState.HIDDEN && isGgdInForeground()) {
                transitionTo(OverlayState.FAB);
            }
        }, 800);
    }

    // ── State Machine ────────────────────────────────────────────────────

    private void transitionTo(OverlayState next) {
        if (state == next) return;
        OverlayState prev = state;
        state = next;

        switch (next) {
            case HIDDEN:
                hideFab(false);
                hideLogin(false);
                hideMenu(false);
                break;

            case FAB:
                // Coming from HIDDEN → pop in
                if (prev == OverlayState.HIDDEN) showFab(true);
                else showFab(false);   // coming from LOGIN or MENU → snap to normal
                hideLogin(true);
                hideMenu(true);
                menuOpen = false;
                break;

            case LOGIN:
                showFabSmall();
                showLogin();
                hideMenu(false);
                break;

            case MENU:
                showFab(false);
                hideLogin(true);
                showMenuPanel();
                break;
        }
    }

    // ── FAB ──────────────────────────────────────────────────────────────

    private void setupFab() {
        android.view.ContextThemeWrapper ctx =
                new android.view.ContextThemeWrapper(this, R.style.Theme_GGDMod);
        fabView   = LayoutInflater.from(ctx).inflate(R.layout.overlay_fab, null);
        fabParams = new WindowManager.LayoutParams(
            dpToPx(64), dpToPx(64),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        fabParams.gravity = Gravity.TOP | Gravity.START;
        fabParams.x = 30;
        fabParams.y = 300;
        try { wm.addView(fabView, fabParams); } catch (Exception e) { fabView = null; return; }
        fabView.setVisibility(View.GONE);
        fabView.setOnTouchListener(new DragTouchListener());
        fabView.setOnClickListener(v -> {
            switch (state) {
                case FAB:
                    if (isAuthenticated()) transitionTo(OverlayState.MENU);
                    else transitionTo(OverlayState.LOGIN);
                    break;
                case MENU:
                    transitionTo(OverlayState.FAB);
                    break;
                case LOGIN:
                    transitionTo(OverlayState.FAB);
                    break;
            }
        });
    }

    private void showFab(boolean animated) {
        if (fabView == null) return;
        fabView.setVisibility(View.VISIBLE);
        if (animated) {
            fabView.setScaleX(0f); fabView.setScaleY(0f); fabView.setAlpha(0f);
            fabView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(300)
                .setInterpolator(new OvershootInterpolator(1.5f)).start();
        } else {
            fabView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200).start();
        }
    }

    private void showFabSmall() {
        if (fabView == null) return;
        fabView.setVisibility(View.VISIBLE);
        fabView.animate().scaleX(0.7f).scaleY(0.7f).alpha(0.6f).setDuration(200).start();
    }

    private void hideFab(boolean animated) {
        if (fabView == null) return;
        if (animated) {
            fabView.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(200)
                .withEndAction(() -> fabView.setVisibility(View.GONE)).start();
        } else {
            fabView.setVisibility(View.GONE);
        }
    }

    // ── Login overlay ─────────────────────────────────────────────────────

    private void setupLogin() {
        android.view.ContextThemeWrapper ctx =
                new android.view.ContextThemeWrapper(this, R.style.Theme_GGDMod);
        loginView   = LayoutInflater.from(ctx).inflate(R.layout.overlay_login, null);
        loginParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        try { wm.addView(loginView, loginParams); } catch (Exception e) { loginView = null; return; }
        loginView.setVisibility(View.GONE);

        View    backdrop = loginView.findViewById(R.id.login_backdrop);
        View    btnClose = loginView.findViewById(R.id.btn_close_login);
        View    btnEnter = loginView.findViewById(R.id.btn_enter);
        EditText etKey   = loginView.findViewById(R.id.et_key);
        TextView tvError = loginView.findViewById(R.id.tv_error);

        // Tap backdrop → back to FAB
        if (backdrop != null) backdrop.setOnClickListener(v -> transitionTo(OverlayState.FAB));

        // Close button → back to FAB
        if (btnClose != null) btnClose.setOnClickListener(v -> transitionTo(OverlayState.FAB));

        // EditText needs to temporarily remove FLAG_NOT_FOCUSABLE so keyboard works
        if (etKey != null) {
            etKey.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    loginParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                    try { wm.updateViewLayout(loginView, loginParams); } catch (Exception ignored) {}
                } else {
                    loginParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                    try { wm.updateViewLayout(loginView, loginParams); } catch (Exception ignored) {}
                }
            });
            etKey.setOnEditorActionListener((v, actionId, event) -> {
                performLogin(etKey, tvError);
                return true;
            });
        }

        // Enter button
        if (btnEnter != null) btnEnter.setOnClickListener(v -> performLogin(etKey, tvError));
    }

    private void performLogin(EditText etKey, TextView tvError) {
        String entered = etKey != null ? etKey.getText().toString().trim() : "";
        if (etKey != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(etKey.getWindowToken(), 0);
        }
        if (ADMIN_KEY.equals(entered)) {
            prefs.edit().putBoolean(PREF_AUTH, true).apply();
            if (tvError != null) tvError.setVisibility(View.INVISIBLE);
            transitionTo(OverlayState.MENU);
        } else {
            if (tvError != null) {
                tvError.setText("Yanlis anahtar");
                tvError.setVisibility(View.VISIBLE);
            }
            if (etKey != null) {
                etKey.animate().translationX(-18f).setDuration(60)
                    .withEndAction(() -> etKey.animate().translationX(18f).setDuration(60)
                    .withEndAction(() -> etKey.animate().translationX(0).setDuration(60).start())
                    .start()).start();
            }
        }
    }

    private void showLogin() {
        if (loginView == null) return;
        // Reset state
        EditText etKey = loginView.findViewById(R.id.et_key);
        TextView tvErr = loginView.findViewById(R.id.tv_error);
        if (etKey != null) etKey.setText("");
        if (tvErr != null) tvErr.setVisibility(View.INVISIBLE);

        loginView.setAlpha(0f);
        loginView.setVisibility(View.VISIBLE);
        loginView.animate().alpha(1f).setDuration(250).start();

        View card = loginView.findViewById(R.id.login_card);
        if (card != null) {
            card.setScaleX(0.88f); card.setScaleY(0.88f);
            card.animate().scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(new OvershootInterpolator(1.1f)).start();
        }
    }

    private void hideLogin(boolean animated) {
        if (loginView == null) return;
        // Restore NOT_FOCUSABLE before hiding
        loginParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        try { wm.updateViewLayout(loginView, loginParams); } catch (Exception ignored) {}

        if (animated) {
            loginView.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> loginView.setVisibility(View.GONE)).start();
        } else {
            loginView.setVisibility(View.GONE);
        }
    }

    // ── Menu panel ────────────────────────────────────────────────────────

    private void setupMenu() {
        android.view.ContextThemeWrapper ctx =
                new android.view.ContextThemeWrapper(this, R.style.Theme_GGDMod);
        menuView   = LayoutInflater.from(ctx).inflate(R.layout.overlay_menu, null);
        menuParams = new WindowManager.LayoutParams(
            dpToPx(320), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        menuParams.gravity = Gravity.TOP | Gravity.START;
        menuParams.x = 20;
        menuParams.y = 80;
        try { wm.addView(menuView, menuParams); } catch (Exception e) { menuView = null; return; }
        menuView.setVisibility(View.GONE);

        View btnClose = menuView.findViewById(R.id.btn_close_menu);
        if (btnClose != null) btnClose.setOnClickListener(v -> transitionTo(OverlayState.FAB));

        buildModRows();
    }

    private void showMenuPanel() {
        if (menuView == null) return;
        menuOpen = true;
        menuView.setVisibility(View.VISIBLE);
        menuView.setScaleX(0.3f); menuView.setScaleY(0.3f); menuView.setAlpha(0f);
        menuView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(350)
            .setInterpolator(new OvershootInterpolator(1.2f)).start();
        updateHeader();
    }

    private void hideMenu(boolean animated) {
        if (menuView == null) { menuOpen = false; return; }
        if (!menuOpen && menuView.getVisibility() == View.GONE) return;
        menuOpen = false;
        if (animated) {
            menuView.animate().scaleX(0.3f).scaleY(0.3f).alpha(0f).setDuration(250)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> menuView.setVisibility(View.GONE)).start();
        } else {
            menuView.setVisibility(View.GONE);
        }
    }

    // ── Mod rows ──────────────────────────────────────────────────────────

    private void buildModRows() {
        if (menuView == null) return;
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

    private void bindMovementTab() {
        bindToggle(R.id.toggle_speed,       ModEngine.MOD_SPEED_ENABLED,       "Hiz Hack");
        bindSlider(R.id.slider_speed,       ModEngine.MOD_SPEED_VALUE, 1f, 10f, "x");
        bindToggle(R.id.toggle_ghost_speed, ModEngine.MOD_GHOST_SPEED_ENABLED, "Hayalet Hizi");
        bindSlider(R.id.slider_ghost_speed, ModEngine.MOD_GHOST_SPEED_VALUE, 1f, 5f, "x");
        bindToggle(R.id.toggle_teleport,    ModEngine.MOD_TELEPORT,            "Isinlanma");
    }

    private void bindRoleTab() {
        bindToggle(R.id.toggle_always_imposter, ModEngine.MOD_ALWAYS_IMPOSTER, "Her Zaman Katil");
        bindToggle(R.id.toggle_always_crewmate, ModEngine.MOD_ALWAYS_CREWMATE, "Her Zaman Murettebat");
        bindToggle(R.id.toggle_see_roles,       ModEngine.MOD_SEE_ROLES,       "Rolleri Gor");
        bindToggle(R.id.toggle_show_dead,       ModEngine.MOD_SHOW_DEAD,       "Oluleri Gor");
    }

    private void bindVisionTab() {
        bindToggle(R.id.toggle_vision,   ModEngine.MOD_VISION_ENABLED, "Gorusu Genislet");
        bindSlider(R.id.slider_vision,   ModEngine.MOD_VISION_VALUE, 1f, 20f, "x");
        bindToggle(R.id.toggle_wallhack, ModEngine.MOD_WALLHACK, "Duvardan Gorunum");
        bindToggle(R.id.toggle_radar,    ModEngine.MOD_RADAR,    "Mini Radar");
    }

    private void bindKillTab() {
        bindToggle(R.id.toggle_no_kill_cd,  ModEngine.MOD_NO_KILL_COOLDOWN, "Sifir Bekleme");
        bindToggle(R.id.toggle_instant_kill, ModEngine.MOD_INSTANT_KILL,    "Aninda Oldur");
        bindSlider(R.id.slider_kill_cd,     ModEngine.MOD_KILL_COOLDOWN, 0f, 60f, "s");
        bindToggle(R.id.toggle_silent_kill,  ModEngine.MOD_SILENT_KILL,  "Sessiz Oldurme");
        bindToggle(R.id.toggle_freeze,       ModEngine.MOD_FREEZE_OTHERS,"Digerleri Dondur");
    }

    private void bindTaskTab() {
        bindToggle(R.id.toggle_auto_task,      ModEngine.MOD_AUTO_TASK,       "Otomatik Gorev");
        bindSlider(R.id.slider_task_speed,     ModEngine.MOD_TASK_SPEED, 1f, 5f, "x");
        bindToggle(R.id.toggle_inf_emergency,  ModEngine.MOD_INF_EMERGENCY,  "Sonsuz Acil Toplanti");
        bindToggle(R.id.toggle_emergency_spam, ModEngine.MOD_EMERGENCY_SPAM, "Toplanti Spam");
        bindToggle(R.id.toggle_see_votes,      ModEngine.MOD_SEE_VOTES,      "Oyları Gor");
    }

    private void bindMiscTab() {
        bindToggle(R.id.toggle_anti_kick,  ModEngine.MOD_ANTI_KICK,  "Anti Kick");
        bindToggle(R.id.toggle_unlock_all, ModEngine.MOD_UNLOCK_ALL, "Tum Kiyafetler");
        bindToggle(R.id.toggle_name_spoof, ModEngine.MOD_NAME_SPOOF, "Isim Degistir");

        EditText etName = menuView.findViewById(R.id.et_name_spoof);
        if (etName != null) {
            etName.setText(engine.getString(ModEngine.MOD_NAME_VALUE));
            etName.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) engine.setString(ModEngine.MOD_NAME_VALUE, etName.getText().toString());
            });
        }
    }

    private void bindToggle(int switchId, String modKey, String label) {
        if (menuView == null) return;
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
        if (menuView == null) return;
        View row = menuView.findViewById(sliderId);
        if (row == null) return;
        SeekBar seekBar = row.findViewWithTag("seekbar");
        TextView tvVal  = row.findViewWithTag("value");
        if (seekBar == null) return;
        float cur = engine.getFloat(modKey);
        seekBar.setProgress((int) (((cur - min) / (max - min)) * 100f));
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
        TextView tv = menuView.findViewById(R.id.tv_mod_count);
        if (tv != null) {
            int count = engine.getActiveModCount();
            tv.setText(count > 0 ? count + " MOD AKTIF" : "MOD KAPALI");
        }
    }

    // ── Drag touch listener ───────────────────────────────────────────────

    private class DragTouchListener implements View.OnTouchListener {
        private int initX, initY, touchX, touchY;
        private long downTime;
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initX = fabParams.x; initY = fabParams.y;
                    touchX = (int) e.getRawX(); touchY = (int) e.getRawY();
                    downTime = System.currentTimeMillis(); moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) e.getRawX() - touchX;
                    int dy = (int) e.getRawY() - touchY;
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) moved = true;
                    fabParams.x = initX + dx; fabParams.y = initY + dy;
                    try { wm.updateViewLayout(fabView, fabParams); } catch (Exception ignored) {}
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved && System.currentTimeMillis() - downTime < 300) v.performClick();
                    return true;
            }
            return false;
        }
    }

    // ── UsageStats: GGD ön planda mı? ────────────────────────────────────

    private boolean isGgdInForeground() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return false;
            long now = System.currentTimeMillis();
            List<UsageStats> stats =
                    usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000L, now);
            if (stats == null || stats.isEmpty()) return false;
            String top = null; long topTime = 0;
            for (UsageStats us : stats) {
                if (us.getLastTimeUsed() > topTime) { topTime = us.getLastTimeUsed(); top = us.getPackageName(); }
            }
            return top != null && top.startsWith(ModEngine.GGD_PACKAGE);
        } catch (Exception e) { return false; }
    }

    // ── Auth helper ───────────────────────────────────────────────────────

    private boolean isAuthenticated() {
        return prefs.getBoolean(PREF_AUTH, false);
    }

    // ── Broadcast registration ───────────────────────────────────────────

    private void registerGameReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(GameDetectorService.ACTION_GGD_FOREGROUND);
        f.addAction(GameDetectorService.ACTION_GGD_BACKGROUND);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gameReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(gameReceiver, f);
        }
    }

    // ── Notification ─────────────────────────────────────────────────────

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "Velaud GGD", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Mod menü servisi calisiyor");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent stop = new Intent(this, OverlayService.class).setAction("STOP");
        PendingIntent pi = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Velaud GGD")
            .setContentText("GGD acilinca mod menu gozukecek")
            .setSmallIcon(android.R.drawable.star_big_on)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Kapat", pi)
            .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) stopSelf();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(gameReceiver); } catch (Exception ignored) {}
        if (fabView   != null) try { wm.removeView(fabView);   } catch (Exception ignored) {}
        if (loginView != null) try { wm.removeView(loginView); } catch (Exception ignored) {}
        if (menuView  != null) try { wm.removeView(menuView);  } catch (Exception ignored) {}
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
