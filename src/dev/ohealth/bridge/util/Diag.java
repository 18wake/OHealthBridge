package dev.ohealth.bridge.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.ohealth.bridge.HookEntry;
import dev.ohealth.bridge.Xp;

/**
 * 诊断记录器(API 101/102 下模块无自有 Context, 写入目标 App 私有 prefs + 外部文件,
 * 模块 UI 通过 root 回读)。
 */
public final class Diag {

    private static final String PREFS = "ohealth_bridge";
    private static final Map<String, String> pending = new LinkedHashMap<>();
    private static volatile Context sTargetContext;

    private Diag() {
    }

    /** 目标 App 上下文(Application.attachBaseContext 锚点提供) */
    public static void setTargetContext(Context c) {
        if (c != null) {
            sTargetContext = c;
            record("target_context", "attached@" + System.currentTimeMillis());
        }
    }

    public static Context getTargetContext() {
        return sTargetContext;
    }

    public static void record(String key, String value) {
        synchronized (pending) {
            pending.put(key, value);
        }
        flush();
    }

    public static String shortName(String pkg) {
        return pkg == null ? "?" : pkg.substring(pkg.lastIndexOf('.') + 1);
    }

    public static void flush() {
        Context c = sTargetContext;
        if (c == null) {
            // fallback: 直接向目标 App 要上下文(v2.7 起任何 hook 回调都可能先于 anchor 触发)
            try {
                Class<?> holder = dev.ohealth.bridge.Xp.findClass(
                        "com.heytap.health.base.base.GlobalApplicationHolder");
                Object appCtx = dev.ohealth.bridge.Xp.callStaticMethod(holder, "getAppContext");
                if (appCtx instanceof Context) {
                    sTargetContext = (Context) appCtx;
                    c = sTargetContext;
                }
            } catch (Throwable ignored) {
            }
        }
        if (c == null) {
            return;
        }
        Map<String, String> snapshot;
        synchronized (pending) {
            if (pending.isEmpty()) {
                return;
            }
            snapshot = new LinkedHashMap<>(pending);
            pending.clear();
        }
        try {
            SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            for (Map.Entry<String, String> en : snapshot.entrySet()) {
                e.putString(en.getKey(), en.getValue() == null ? "null" : en.getValue());
            }
            e.apply();
        } catch (Throwable t) {
            Xp.log("OHealthBridge.Diag", "flush prefs failed", t);
        }
        // 双保险: 追加写目标 App 外部私有文件(root 可读)
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir != null) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> en : snapshot.entrySet()) {
                    sb.append(en.getKey()).append('=').append(en.getValue()).append('\n');
                }
                File f = new File(dir, "ohealth_bridge_diag.txt");
                FileOutputStream fo = new FileOutputStream(f, true);
                fo.write(("---- @" + System.currentTimeMillis() + "\n" + sb).getBytes());
                fo.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 供 HookEntry 在无 target context 时(尚未 attach)延迟记录 */
    public static boolean hasTargetContext() {
        return sTargetContext != null;
    }

    public static void flushWhenAttached() {
        if (hasTargetContext() && !pending.isEmpty()) {
            flush();
        }
    }

    /** 防止未使用警告 */
    static String marker() {
        return HookEntry.TARGET_PKG;
    }
}
