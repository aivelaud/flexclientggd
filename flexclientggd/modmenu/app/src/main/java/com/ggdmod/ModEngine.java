package com.ggdmod;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Mod settings storage & game memory interface.
 * On rooted devices, attempts direct /proc/pid/mem patches.
 */
public class ModEngine {

    private static final String PREF_MODS = "ggd_mods";
    private static ModEngine instance;
    private SharedPreferences prefs;
    private final Map<String, Object> settings = new HashMap<>();

    // GGD package name
    public static final String GGD_PACKAGE = "com.gaggle.fun";

    // ── Default mod values ──────────────────────────────────────────────
    public static final String MOD_SPEED_ENABLED       = "speed_enabled";
    public static final String MOD_SPEED_VALUE         = "speed_value";          // float 1.0–10.0
    public static final String MOD_GHOST_SPEED_ENABLED = "ghost_speed_enabled";
    public static final String MOD_GHOST_SPEED_VALUE   = "ghost_speed_value";    // float 1.0–5.0
    public static final String MOD_ALWAYS_IMPOSTER     = "always_imposter";
    public static final String MOD_ALWAYS_CREWMATE     = "always_crewmate";
    public static final String MOD_ROLE_CUSTOM         = "role_custom";          // int 0=off,1=imp,2=crew,3=detective,4=spy
    public static final String MOD_VISION_ENABLED      = "vision_enabled";
    public static final String MOD_VISION_VALUE        = "vision_value";         // float 1.0–20.0
    public static final String MOD_WALLHACK            = "wallhack";
    public static final String MOD_SEE_ROLES           = "see_roles";
    public static final String MOD_KILL_COOLDOWN       = "kill_cooldown";        // float 0–60
    public static final String MOD_NO_KILL_COOLDOWN    = "no_kill_cooldown";
    public static final String MOD_INSTANT_KILL        = "instant_kill";
    public static final String MOD_AUTO_TASK           = "auto_task";
    public static final String MOD_TASK_SPEED          = "task_speed";           // float 1.0–5.0
    public static final String MOD_INF_EMERGENCY       = "inf_emergency";
    public static final String MOD_EMERGENCY_SPAM      = "emergency_spam";
    public static final String MOD_SEE_VOTES           = "see_votes";
    public static final String MOD_ANTI_KICK           = "anti_kick";
    public static final String MOD_TELEPORT            = "teleport";
    public static final String MOD_FREEZE_OTHERS       = "freeze_others";
    public static final String MOD_SILENT_KILL         = "silent_kill";
    public static final String MOD_NAME_SPOOF          = "name_spoof";
    public static final String MOD_NAME_VALUE          = "name_value";
    public static final String MOD_UNLOCK_ALL          = "unlock_all";
    public static final String MOD_SHOW_DEAD           = "show_dead";
    public static final String MOD_RADAR               = "radar";

    private ModEngine(Context ctx) {
        prefs = ctx.getApplicationContext()
                   .getSharedPreferences(PREF_MODS, Context.MODE_PRIVATE);
        loadDefaults();
    }

    public static synchronized ModEngine get(Context ctx) {
        if (instance == null) instance = new ModEngine(ctx);
        return instance;
    }

    private void loadDefaults() {
        settings.put(MOD_SPEED_ENABLED,       prefs.getBoolean(MOD_SPEED_ENABLED, false));
        settings.put(MOD_SPEED_VALUE,         prefs.getFloat(MOD_SPEED_VALUE, 2.0f));
        settings.put(MOD_GHOST_SPEED_ENABLED, prefs.getBoolean(MOD_GHOST_SPEED_ENABLED, false));
        settings.put(MOD_GHOST_SPEED_VALUE,   prefs.getFloat(MOD_GHOST_SPEED_VALUE, 2.0f));
        settings.put(MOD_ALWAYS_IMPOSTER,     prefs.getBoolean(MOD_ALWAYS_IMPOSTER, false));
        settings.put(MOD_ALWAYS_CREWMATE,     prefs.getBoolean(MOD_ALWAYS_CREWMATE, false));
        settings.put(MOD_ROLE_CUSTOM,         prefs.getInt(MOD_ROLE_CUSTOM, 0));
        settings.put(MOD_VISION_ENABLED,      prefs.getBoolean(MOD_VISION_ENABLED, false));
        settings.put(MOD_VISION_VALUE,        prefs.getFloat(MOD_VISION_VALUE, 5.0f));
        settings.put(MOD_WALLHACK,            prefs.getBoolean(MOD_WALLHACK, false));
        settings.put(MOD_SEE_ROLES,           prefs.getBoolean(MOD_SEE_ROLES, false));
        settings.put(MOD_KILL_COOLDOWN,       prefs.getFloat(MOD_KILL_COOLDOWN, 10.0f));
        settings.put(MOD_NO_KILL_COOLDOWN,    prefs.getBoolean(MOD_NO_KILL_COOLDOWN, false));
        settings.put(MOD_INSTANT_KILL,        prefs.getBoolean(MOD_INSTANT_KILL, false));
        settings.put(MOD_AUTO_TASK,           prefs.getBoolean(MOD_AUTO_TASK, false));
        settings.put(MOD_TASK_SPEED,          prefs.getFloat(MOD_TASK_SPEED, 2.0f));
        settings.put(MOD_INF_EMERGENCY,       prefs.getBoolean(MOD_INF_EMERGENCY, false));
        settings.put(MOD_EMERGENCY_SPAM,      prefs.getBoolean(MOD_EMERGENCY_SPAM, false));
        settings.put(MOD_SEE_VOTES,           prefs.getBoolean(MOD_SEE_VOTES, false));
        settings.put(MOD_ANTI_KICK,           prefs.getBoolean(MOD_ANTI_KICK, false));
        settings.put(MOD_TELEPORT,            prefs.getBoolean(MOD_TELEPORT, false));
        settings.put(MOD_FREEZE_OTHERS,       prefs.getBoolean(MOD_FREEZE_OTHERS, false));
        settings.put(MOD_SILENT_KILL,         prefs.getBoolean(MOD_SILENT_KILL, false));
        settings.put(MOD_NAME_SPOOF,          prefs.getBoolean(MOD_NAME_SPOOF, false));
        settings.put(MOD_NAME_VALUE,          prefs.getString(MOD_NAME_VALUE, "GGDMod"));
        settings.put(MOD_UNLOCK_ALL,          prefs.getBoolean(MOD_UNLOCK_ALL, false));
        settings.put(MOD_SHOW_DEAD,           prefs.getBoolean(MOD_SHOW_DEAD, false));
        settings.put(MOD_RADAR,               prefs.getBoolean(MOD_RADAR, false));
    }

    public boolean getBool(String key) {
        Object v = settings.get(key);
        return v instanceof Boolean && (Boolean) v;
    }

    public float getFloat(String key) {
        Object v = settings.get(key);
        if (v instanceof Float) return (Float) v;
        if (v instanceof Double) return ((Double) v).floatValue();
        return 0f;
    }

    public int getInt(String key) {
        Object v = settings.get(key);
        return v instanceof Integer ? (Integer) v : 0;
    }

    public String getString(String key) {
        Object v = settings.get(key);
        return v != null ? v.toString() : "";
    }

    public void setBool(String key, boolean val) {
        settings.put(key, val);
        prefs.edit().putBoolean(key, val).apply();
        applyMod(key);
    }

    public void setFloat(String key, float val) {
        settings.put(key, val);
        prefs.edit().putFloat(key, val).apply();
        applyMod(key);
    }

    public void setInt(String key, int val) {
        settings.put(key, val);
        prefs.edit().putInt(key, val).apply();
        applyMod(key);
    }

    public void setString(String key, String val) {
        settings.put(key, val);
        prefs.edit().putString(key, val).apply();
        applyMod(key);
    }

    /**
     * Apply mod in real time.
     * Requires root access for memory patching.
     */
    private void applyMod(String key) {
        if (!isRooted()) return;
        int pid = findGgdPid();
        if (pid < 0) return;
        // Memory patch would go here using /proc/pid/mem
        // Offsets need to be found via reverse engineering libil2cpp.so
        // This is the framework — add offsets as they are discovered
    }

    public boolean isRooted() {
        String[] paths = {"/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
            "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su"};
        for (String path : paths) {
            if (new File(path).exists()) return true;
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"which", "su"});
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public int findGgdPid() {
        File procDir = new File("/proc");
        File[] dirs = procDir.listFiles(f -> f.isDirectory() && f.getName().matches("\\d+"));
        if (dirs == null) return -1;
        for (File dir : dirs) {
            try {
                File cmdline = new File(dir, "cmdline");
                BufferedReader br = new BufferedReader(new FileReader(cmdline));
                String cmd = br.readLine();
                br.close();
                if (cmd != null && cmd.contains(GGD_PACKAGE)) {
                    return Integer.parseInt(dir.getName());
                }
            } catch (Exception ignored) {}
        }
        return -1;
    }

    public int getActiveModCount() {
        int count = 0;
        for (Object v : settings.values()) {
            if (v instanceof Boolean && (Boolean) v) count++;
        }
        return count;
    }
}
