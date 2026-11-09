package dev.ohealth.bridge.dnd;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;

import dev.ohealth.bridge.Config;
import dev.ohealth.bridge.Xp;
import dev.ohealth.bridge.util.Diag;
import dev.ohealth.bridge.util.SuShell;

/**
 * 勿扰同步桥(等价于修改版 HonorDndBridge, 去掉荣耀硬编码).
 *
 * 手表协议对象 DNDProto$DoNotDisturb 由目标 App 自带, 通过反射构建:
 *   new Builder().setStatus(state).setStatusChangedTime(ts).setSupportLinkage(1).build()
 */
public final class DndBridge {

    private static final String TAG = "OHealthBridge.DND";
    private static final String PREFS = "ohealth_bridge";

    /** 上次已知状态, -1 未知 */
    private static int sLastState = -1;
    private static int sChangedAt = 0;

    private DndBridge() {
    }

    private static Context ctx() {
        Context c = Diag.getTargetContext();
        if (c == null) {
            try {
                Class<?> holder = Xp.findClass(
                        "com.heytap.health.base.base.GlobalApplicationHolder");
                c = (Context) Xp.callStaticMethod(holder, "getAppContext");
            } catch (Throwable ignored) {
            }
        }
        return c != null ? c.getApplicationContext() : null;
    }

    private static NotificationManager nm(Context c) {
        try {
            return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 是否已获"勿扰访问"授权(标准 API, 读方向本就可用; 写方向需要授权或 root)。
     *  结果记录到诊断, 模块 UI 直接展示。 */
    public static boolean hasPolicyAccess() {
        try {
            Context c = ctx();
            NotificationManager n = c == null ? null : nm(c);
            boolean granted = n != null && n.isNotificationPolicyAccessGranted();
            Diag.record("policy_access", granted + "@" + System.currentTimeMillis());
            return granted;
        } catch (Throwable t) {
            Xp.log(TAG, "hasPolicyAccess error", t);
            return false;
        }
    }

    /**
     * 读手机勿扰状态, 构建手表协议对象。
     * 映射与修改版一致: INTERRUPTION_FILTER_ALL(1)/UNKNOWN(0) → 0(关), 其余 → 1(开)
     */
    public static Object readDnd() {
        try {
            int[] sf = readStateFilter();
            if (sf[0] < 0) {
                return null;
            }
            Class<?> builder = Xp.findClass(
                    "com.heytap.health.protocol.dnd.DNDProto$DoNotDisturb$Builder");
            Object b = Xp.newInstance(builder);
            b = Xp.callMethod(b, "setStatus", sf[0]);
            b = Xp.callMethod(b, "setStatusChangedTime", lastChangedSeconds());
            b = Xp.callMethod(b, "setSupportLinkage", 1);
            return Xp.callMethod(b, "build");
        } catch (Throwable t) {
            Xp.log(TAG, "readDnd error", t);
            return null;
        }
    }

    /** 读手机状态: int[]{state, filter}; 读不到返回 {-1,-1}. 同时刷新变更时间戳与诊断。 */
    public static int[] readStateFilter() {
        try {
            Context c = ctx();
            NotificationManager n = c == null ? null : nm(c);
            if (c == null || n == null) {
                return new int[]{-1, -1};
            }
            int filter = n.getCurrentInterruptionFilter();
            int state = (filter == NotificationManager.INTERRUPTION_FILTER_ALL
                    || filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) ? 0 : 1;
            updateState(c, state);
            Diag.record("dnd_last_read",
                    "state=" + state + " filter=" + filter + " @" + System.currentTimeMillis());
            return new int[]{state, filter};
        } catch (Throwable t) {
            Xp.log(TAG, "readStateFilter error", t);
            return new int[]{-1, -1};
        }
    }

    /** 跨进程回显抑制: 主进程与 :transport 的 static 状态互不可见, 需落 prefs 共享 */
    public static void markEcho(String key, long until) {
        try {
            Context c = ctx();
            if (c != null) {
                c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putLong(key, until).commit();
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean inEcho(String key, long now) {
        try {
            Context c = ctx();
            return c != null && now < c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getLong(key, 0L);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 上次状态变更时间(秒), 供手表协议 statusChangedTime 使用 */
    public static int lastChangedSeconds() {
        return sChangedAt > 0 ? sChangedAt : nowSeconds();
    }

    /**
     * 跨进程去重: 主进程与 :transport 都会收到 zen_mode 变化, 同一状态只发一次。
     * 写 shared_prefs 并同步 commit, 按时间桶签名。
     */
    public static boolean claimSend(int state, long windowMs) {
        try {
            Context c = ctx();
            if (c == null) {
                return true;
            }
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String sig = state + "|" + (System.currentTimeMillis() / windowMs);
            if (sig.equals(p.getString("dnd_sent_sig", ""))) {
                return false;
            }
            return p.edit().putString("dnd_sent_sig", sig).commit();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 手表 → 手机: 应用勿扰状态。
     * 优先标准 API(需勿扰访问授权);
     * 未授权时按配置走 root 兜底: cmd notification set_dnd priority/off
     */
    public static boolean setDnd(boolean enable) {
        return setDnd(enable, "HOOK");
    }

    public static boolean setDnd(boolean enable, String source) {
        try {
            Context c = ctx();
            NotificationManager n = c == null ? null : nm(c);
            if (c == null || n == null) {
                return false;
            }
            boolean granted = n.isNotificationPolicyAccessGranted();
            int filter = n.getCurrentInterruptionFilter();
            boolean current = !(filter == NotificationManager.INTERRUPTION_FILTER_ALL
                    || filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN);
            if (current == enable) {
                // 已是目标状态: 不再写, 避免触发 zen_mode 回环
                Diag.record("dnd_last_set",
                        "enable=" + enable + " ok=true no_change=true source=" + source
                                + " granted=" + granted + " @" + System.currentTimeMillis());
                return true;
            }
            boolean ok;
            if (granted) {
                n.setInterruptionFilter(enable
                        ? NotificationManager.INTERRUPTION_FILTER_PRIORITY
                        : NotificationManager.INTERRUPTION_FILTER_ALL);
                ok = true;
            } else if (Config.get().rootFallback) {
                ok = SuShell.exec(
                        "cmd notification set_dnd " + (enable ? "priority" : "off")).success;
            } else {
                Xp.log(TAG, "no policy access & root fallback off, skip");
                return false;
            }
            if (ok) {
                recordState(c, enable ? 1 : 0, nowSeconds());
                Xp.log(TAG, "applied watch DND state: " + enable);
            }
            Diag.record("dnd_last_set",
                    "enable=" + enable + " ok=" + ok + " granted=" + granted
                            + " source=" + source + " @" + System.currentTimeMillis());
            return ok;
        } catch (Throwable t) {
            Xp.log(TAG, "setDnd error", t);
            return false;
        }
    }

    /* ---------------- 状态时间戳(镜像修改版语义) ---------------- */

    private static int updateState(Context c, int state) {
        if (sLastState == -1) {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int saved = p.getInt("dnd_state", -1);
            int at = p.getInt("dnd_changed_at", 0);
            if (saved == state && at > 0) {
                sLastState = saved;
                sChangedAt = at;
            } else {
                sChangedAt = nowSeconds();
                recordState(c, state, sChangedAt);
            }
        } else if (sLastState != state) {
            sChangedAt = nowSeconds();
            recordState(c, state, sChangedAt);
        }
        return sChangedAt;
    }

    private static void recordState(Context c, int state, int at) {
        sLastState = state;
        sChangedAt = at;
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt("dnd_state", state)
                    .putInt("dnd_changed_at", at)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private static int nowSeconds() {
        return (int) (System.currentTimeMillis() / 1000L);
    }
}
