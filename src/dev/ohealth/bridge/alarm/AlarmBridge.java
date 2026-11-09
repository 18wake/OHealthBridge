package dev.ohealth.bridge.alarm;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.app.Notification;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.lang.reflect.Method;
import java.util.Locale;

import dev.ohealth.bridge.Config;
import dev.ohealth.bridge.Xp;
import dev.ohealth.bridge.clock.ClockLink;
import dev.ohealth.bridge.util.Diag;

/**
 * 闹钟同步桥(镜像修改版 HonorAlarmBridge, 通用化):
 *
 * 手机→手表: 系统闹钟 App 响铃时发一条 category=alarm 的通知(带 关闭/贪睡 按钮),
 *            从中提取 标题/时间/PendingIntent 组装 PlatformClockInfo,
 *            经 AlarmDataController.alarmClockRing() 走 App 自带链路让手表同步响铃。
 * 手表→手机: 关闭/贪睡 → 直接 send() 通知自带的按钮 PendingIntent(令牌即凭证, 无需权限),
 *            兜底 DISMISS_ALARM / SNOOZE_ALARM intent。
 * 手机侧关闭(非手表触发) → onNotificationRemoved → 通知手表停止响铃。
 */
public final class AlarmBridge {

    private static final String TAG = "OHealthBridge.Alarm";
    private static final String ACTION_DISMISS = "android.intent.action.DISMISS_ALARM";
    private static final String ACTION_SNOOZE = "android.intent.action.SNOOZE_ALARM";

    /** 0=手机侧触发, 1=手表关闭, 2=手表贪睡 (防回环) */
    private static int sWatchAction = 0;
    private static Context sContext;
    private static String sNotificationKey;
    /** 已成功转发过的响铃通知 key: 允许"提前提醒→真正响铃"共用同一 key 时仍能转发 */
    private static String sForwardedKey;
    private static String sClockPkg;
    private static long sAlarmId;
    private static PendingIntent sDismissIntent;
    private static PendingIntent sSnoozeIntent;
    /** 本轮响铃是否已向手表发过 DISMISS/SNOOZE 命令 (通知更新+移除双路径去重) */
    private static boolean sWatchCommandSent = false;

    private AlarmBridge() {
    }

    /* ================= 手机→手表: 响铃转发 ================= */

    public static void onNotificationPosted(Context context, Object sbnObj) {
        synchronized (AlarmBridge.class) {
            try {
                StatusBarNotification sbn = (StatusBarNotification) sbnObj;
                Notification n = sbn.getNotification();
                String verdict = classify(sbn, n);
                String key = sbn.getKey();
                recordFingerprint(sbn, n, verdict);
                recordCandidateForDiag(sbn, n);
                Diag.record("phone_alarm_event", "POSTED verdict=" + verdict
                        + " key=" + key + " @" + System.currentTimeMillis());

                // 同一条已转发的响铃通知"不再是 FIRING" = 手机侧关闭/贪睡。
                // 小米等 ROM 常把响铃通知更新成"已关闭/已延迟"而不是移除, 所以不能只靠 onNotificationRemoved。
                boolean sameAsFiring = TextUtils.equals(key, sForwardedKey);
                if (sameAsFiring && !sWatchCommandSent) {
                    String endKind = phoneEndKind(sbn, n, verdict);
                    if (endKind != null) {
                        handlePhoneEnd(endKind, "UPDATED");
                        return;
                    }
                }
                if (!verdict.startsWith("FIRING")) {
                    return; // PRE_ALARM / IGNORE: 只记录, 绝不触发响铃转发
                }
                Diag.record("last_alarm_candidate",
                        sbn.getPackageName() + " cat=" + n.category
                                + " actions=" + n.actions.length + " verdict=" + verdict
                                + " @" + System.currentTimeMillis());
                Notification.Action[] actions = n.actions;
                PendingIntent dismissPi = actions[0].actionIntent;
                PendingIntent snoozePi = actions.length > 1 ? actions[1].actionIntent : null;
                if (dismissPi == null) {
                    return;
                }
                Context app = context.getApplicationContext();
                if (app != null) {
                    context = app;
                }
                sContext = context;
                dev.ohealth.bridge.util.Diag.setTargetContext(context);

                if (sameAsFiring) {
                    return; // 该响铃通知已转发过 (仅按钮更新)
                }
                sNotificationKey = key;
                sClockPkg = sbn.getPackageName();
                sDismissIntent = dismissPi;
                sSnoozeIntent = snoozePi;
                sAlarmId = resolveAlarmId(sbn);
                sWatchAction = 0;
                sWatchCommandSent = false;

                Object controller = ClockLink.getAlarmController(sContext);
                if (controller == null) {
                    Xp.log(TAG, "AlarmDataController unavailable, skip");
                    dev.ohealth.bridge.util.Diag.record("forward_fail",
                            "controller=null @" + System.currentTimeMillis());
                    return;
                }
                dev.ohealth.bridge.util.Diag.record("forward_step", "controller ok");
                Object info = buildClockInfo(sbn, n);
                Xp.callMethod(controller, "alarmClockRing", info);
                Xp.log(TAG, "forwarded ringing alarm to watch: " + key);
                sForwardedKey = key;
                Diag.record("alarm_firing", "FIRING key=" + key + " @" + System.currentTimeMillis());
                dev.ohealth.bridge.util.Diag.record("last_forward", key + " @" + System.currentTimeMillis());
            } catch (Throwable t) {
                Xp.log(TAG, "unable to forward alarm to watch");
                Xp.log(TAG, "error", t);
                dev.ohealth.bridge.util.Diag.record("forward_fail",
                        String.valueOf(t) + " @" + System.currentTimeMillis());
            }
        }
    }

    /* ================= 手机侧闹钟结束 → 通知手表停 ================= */

    public static void onNotificationRemoved(Object sbnObj) {
        synchronized (AlarmBridge.class) {
            try {
                StatusBarNotification sbn = (StatusBarNotification) sbnObj;
                String key = sbn == null ? "null" : sbn.getKey();
                boolean isFiring = sbn != null
                        && TextUtils.equals(sbn.getPackageName(), sClockPkg)
                        && (TextUtils.equals(key, sForwardedKey)
                            || TextUtils.equals(key, sNotificationKey));
                Diag.record("phone_alarm_event", "REMOVED key=" + key
                        + " isFiring=" + isFiring + " watchAction=" + sWatchAction
                        + " @" + System.currentTimeMillis());
                if (isFiring && sWatchAction == 0 && !sWatchCommandSent) {
                    handlePhoneEnd("DISMISS", "REMOVED");
                }
            } catch (Throwable t) {
                Xp.log(TAG, "unable to stop watch alarm");
                Xp.log(TAG, "error", t);
                Diag.record("forward_fail", String.valueOf(t) + " @" + System.currentTimeMillis());
            } finally {
                clearAlarm();
            }
        }
    }

    /** 手机侧响铃结束的类型: SNOOZE / DISMISS / null(仍在响铃) */
    private static String phoneEndKind(StatusBarNotification sbn, Notification n, String verdict) {
        long now = System.currentTimeMillis();
        boolean future = n.when > now + FUTURE_TOLERANCE_MS;
        String text = notificationText(n);
        boolean snoozeWord = text != null && (text.contains("贪睡") || text.contains("稍后")
                || text.toLowerCase(Locale.ROOT).contains("snooz"));
        if (future || snoozeWord) {
            return "SNOOZE";
        }
        if (!verdict.startsWith("FIRING")) {
            return "DISMISS";
        }
        return null;
    }

    /** 手机关闭/贪睡 → 手表命令 */
    private static void handlePhoneEnd(String kind, String via) {
        if (sWatchAction != 0) {
            Diag.record("phone_dismiss_detected", "ignored watch-origin=" + sWatchAction
                    + " @" + System.currentTimeMillis());
            return;
        }
        Diag.record("phone_dismiss_detected", kind + " via=" + via + " key=" + sForwardedKey
                + " @" + System.currentTimeMillis());
        Diag.record("forward_dismiss_to_watch", kind + " id=" + sAlarmId
                + " @" + System.currentTimeMillis());
        sendWatchCommand(kind);
        // 会话复位: 贪睡后再次响铃常复用同一 notification key, 不能因去重而漏转发
        clearAlarm();
    }

    /**
     * 6.9.37 实测方法(orig_alarmctrl.smali):
     *   dismissClock(J)V → ClockAbility.getCloseCmId() → sendAlarmCommand(7/22)
     *   snoozeClock(J)V  → ClockAbility.getDelayCmId() → sendAlarmCommand(8/23)
     *   sendAlarmCommand(I)V → MessageEvent(10, cmdId, AlarmCommand) → DMMessageApi
     * 旧代码用 Xp.callMethod 会吞掉异常, 所以这里精确反射并落诊断。
     */
    private static void sendWatchCommand(String kind) {
        String method = "SNOOZE".equals(kind) ? "snoozeClock" : "dismissClock";
        String connected = String.valueOf(isCurrentConnected());
        Diag.record("watch_command_method", method + "(J)V args=id:" + sAlarmId
                + " connected=" + connected);
        Object controller = ClockLink.getAlarmController(sContext);
        if (controller == null) {
            Diag.record("watch_command_result", "FAILED controller=null @" + System.currentTimeMillis());
            return;
        }
        try {
            Method m = Xp.findMethodExact(controller.getClass(), method, long.class);
            m.invoke(controller, Long.valueOf(sAlarmId));
            sWatchCommandSent = true;
            Xp.log(TAG, "forwarded phone " + kind + " to watch");
            Diag.record("watch_command_result", "ok method=" + method
                    + " @" + System.currentTimeMillis());
            Diag.record("last_dismiss_notify", "@" + System.currentTimeMillis());
        } catch (Throwable t) {
            Diag.record("watch_command_result", method + " FAILED: " + t
                    + " @" + System.currentTimeMillis());
            Xp.log(TAG, method + " failed, fallback sendAlarmCommand", t);
            sendAlarmCommandFallback(kind);
        }
    }

    /** dismissClock 内部依赖 buildByMac/getBoundDeviceByMac, 拿不到设备对象时会抛异常 */
    private static void sendAlarmCommandFallback(String kind) {
        int cmd = -1;
        try {
            Object mgrApi = Xp.getStaticObjectField(
                    Xp.findClass("com.heytap.health.devicemanager.client.DMHeytap"), "managerApi");
            Object mac = Xp.callMethod(mgrApi, "getCurrentConnectId");
            Object ability = Xp.callStaticMethod(
                    Xp.findClass("com.heytap.health.deviceability.ClockAbilityTool"),
                    "buildByMac", mac);
            Object v = Xp.callMethod(ability,
                    "SNOOZE".equals(kind) ? "getDelayCmId" : "getCloseCmId");
            if (v instanceof Integer) {
                cmd = (Integer) v;
            }
        } catch (Throwable ignored) {
        }
        if (cmd < 0) {
            cmd = "SNOOZE".equals(kind) ? 23 : 22; // ClockAbility 新类型默认值
        }
        try {
            Object controller = ClockLink.getAlarmController(sContext);
            Method sm = Xp.findMethodExact(controller.getClass(), "sendAlarmCommand", int.class);
            sm.invoke(controller, Integer.valueOf(cmd));
            sWatchCommandSent = true;
            Diag.record("watch_command_result", "fallback sendAlarmCommand(" + cmd + ") ok"
                    + " @" + System.currentTimeMillis());
        } catch (Throwable t) {
            Diag.record("watch_command_result", "fallback FAILED: " + t);
            Xp.log(TAG, "sendAlarmCommand fallback failed", t);
        }
    }

    private static boolean isCurrentConnected() {
        try {
            Object mgrApi = Xp.getStaticObjectField(
                    Xp.findClass("com.heytap.health.devicemanager.client.DMHeytap"), "managerApi");
            Method m = Xp.findClass("com.heytap.health.devicemanager.client.api.DMManagerApi")
                    .getMethod("isCurrentConnected");
            Object r = m.invoke(mgrApi);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        }
    }

    /* ================= 手表→手机: 关闭 / 贪睡 ================= */

    public static boolean dismissCurrent() {
        synchronized (AlarmBridge.class) {
            sWatchAction = 1;
            dev.ohealth.bridge.util.Diag.record("watch_dismiss", "@" + System.currentTimeMillis());
            if (sendPendingIntent(sDismissIntent, "dismiss")) {
                return true;
            }
            return startAlarmAction(ACTION_DISMISS, "dismiss");
        }
    }

    public static boolean snoozeCurrent() {
        synchronized (AlarmBridge.class) {
            sWatchAction = 2;
            dev.ohealth.bridge.util.Diag.record("watch_snooze", "@" + System.currentTimeMillis());
            if (sendPendingIntent(sSnoozeIntent, "snooze")) {
                return true;
            }
            return startAlarmAction(ACTION_SNOOZE, "snooze");
        }
    }

    /* ================= 内部 ================= */

    /** "即将响铃"提醒的判定余量: when 比当前时间晚 2 分钟以上即视为预告 */
    private static final long FUTURE_TOLERANCE_MS = 120000L;

    /**
     * 事件分类: 只有 FIRING 才允许调用 AlarmDataController.alarmClockRing()。
     *
     * 小米 com.android.deskclock 会提前发一条"闹钟即将响铃"提醒:
     *   actions=1, ongoing=false, cat=null, when=未来闹钟时间
     * 真正响铃: actions=2, ongoing=true, cat=null, when≈当前时间。
     * 因此除包名/category/按钮外, 必须校验 when 与 isOngoing。
     */
    private static String classify(StatusBarNotification sbn, Notification n) {
        if (sbn == null || n == null) {
            return "IGNORE(null)";
        }
        int actions = n.actions == null ? 0 : n.actions.length;
        if (!matchReason(sbn, n)) {
            return "IGNORE(no_match)";
        }
        if (actions == 0) {
            return "IGNORE(no_actions)";
        }
        if (!Config.get().strictRinging) {
            return "FIRING(relaxed)";
        }
        long now = System.currentTimeMillis();
        if (n.when > now + FUTURE_TOLERANCE_MS) {
            return "PRE_ALARM(future_when=+" + (n.when - now) / 60000L + "min)";
        }
        boolean catAlarm = Notification.CATEGORY_ALARM.equals(n.category);
        boolean ongoing = sbn.isOngoing();
        if (!ongoing && !catAlarm) {
            return "PRE_ALARM(not_ongoing,cat=" + n.category + ",act=" + actions + ")";
        }
        String text = notificationText(n);
        if (looksLikeUpcoming(text)) {
            return "PRE_ALARM(text)";
        }
        if (ongoing || catAlarm || actions >= 2) {
            return "FIRING(ong=" + ongoing + ",cat=" + n.category + ",act=" + actions + ")";
        }
        return "PRE_ALARM(single_action)";
    }

    private static boolean looksLikeUpcoming(String t) {
        if (t == null) {
            return false;
        }
        String s = t.toLowerCase(Locale.ROOT);
        return s.contains("即将") || s.contains("将在") || s.contains("还有")
                || s.contains("upcoming") || s.contains("about to")
                || s.contains("in ") && s.contains("min");
    }

    /** 通知文本内容(title/text/subText/bigText), 压缩成单行供诊断截图查看 */
    private static String notificationText(Notification n) {
        StringBuilder sb = new StringBuilder();
        try {
            Bundle e = n.extras;
            if (e != null) {
                appendExtra(sb, e, Notification.EXTRA_TITLE);
                appendExtra(sb, e, Notification.EXTRA_TEXT);
                appendExtra(sb, e, Notification.EXTRA_SUB_TEXT);
                appendExtra(sb, e, Notification.EXTRA_BIG_TEXT);
            }
        } catch (Throwable ignored) {
        }
        String s = sb.toString().replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 140 ? s.substring(0, 140) : s;
    }

    private static void appendExtra(StringBuilder sb, Bundle e, String key) {
        CharSequence v = e.getCharSequence(key);
        if (!TextUtils.isEmpty(v)) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(v);
        }
    }

    /** 完整字段指纹 + 最终判定, 每条疑似通知都落盘(短行纯文本) */
    private static void recordFingerprint(StatusBarNotification sbn, Notification n, String verdict) {
        try {
            String pkg = sbn.getPackageName();
            int actions = n.actions == null ? 0 : n.actions.length;
            boolean interesting = Config.get().clockPackages.contains(pkg)
                    || Notification.CATEGORY_ALARM.equals(n.category)
                    || actions > 0;
            if (!interesting) {
                return;
            }
            long now = System.currentTimeMillis();
            StringBuilder act = new StringBuilder();
            if (n.actions != null) {
                for (int i = 0; i < n.actions.length && i < 3; i++) {
                    if (i > 0) {
                        act.append('/');
                    }
                    act.append(n.actions[i].title == null ? "?" : n.actions[i].title);
                }
            }
            Diag.record("ntl_detail", pkg + " cat=" + n.category + " act=" + actions
                    + "(" + act + ") ong=" + sbn.isOngoing()
                    + " when=" + n.when + "(+" + (n.when - now) / 1000L + "s)"
                    + " post=" + sbn.getPostTime()
                    + " ci=" + (n.contentIntent != null) + " di=" + (n.deleteIntent != null)
                    + " txt=" + notificationText(n));
            Diag.record("ntl_verdict", verdict + " @" + now);
        } catch (Throwable ignored) {
        }
    }

    private static boolean matchReason(StatusBarNotification sbn, Notification n) {
        String pkg = sbn.getPackageName();
        boolean categoryHit = Notification.CATEGORY_ALARM.equals(n.category);
        boolean pkgHit = Config.get().clockPackages.contains(pkg);
        boolean any = Config.get().anyPackage;
        // category 命中: any=true 任意包名; any=false 仅白名单包
        // 白名单包名 + 有按钮: 无条件命中(小米闹钟通知可能没有 category)
        return (categoryHit && (any || pkgHit)) || pkgHit;
    }

    /** 诊断: 记录疑似闹钟通知(白名单包 或 category=alarm 或 有按钮的 ongoing 通知) */
    private static void recordCandidateForDiag(StatusBarNotification sbn, Notification n) {
        try {
            String pkg = sbn.getPackageName();
            boolean interesting = Config.get().clockPackages.contains(pkg)
                    || Notification.CATEGORY_ALARM.equals(n.category)
                    || (n.actions != null && n.actions.length > 0 && sbn.isOngoing());
            if (interesting) {
                dev.ohealth.bridge.util.Diag.record("last_ntl",
                        pkg + " cat=" + n.category + " actions=" + (n.actions == null ? 0 : n.actions.length)
                                + " ongoing=" + sbn.isOngoing()
                                + " match=" + (n.actions != null && n.actions.length > 0 && matchReason(sbn, n))
                                + " @" + System.currentTimeMillis());
            }
        } catch (Throwable ignored) {
        }
    }

    private static long resolveAlarmId(StatusBarNotification sbn) {
        long id = sbn.getId() & 0x7FFFFFFFL;
        if (id == 0) {
            String key = sbn.getKey();
            if (key == null) {
                return 1L;
            }
            id = key.hashCode() & 0x7FFFFFFFL;
            if (id == 0) {
                id = 1L;
            }
        }
        return id;
    }

    private static long resolveAlarmTime(StatusBarNotification sbn, Notification n) {
        if (n.when > 0) {
            return n.when;
        }
        long post = sbn.getPostTime();
        return post > 0 ? post : System.currentTimeMillis();
    }

    private static String resolveAlarmTitle(Notification n) {
        try {
            Bundle extras = n.extras;
            if (extras != null) {
                CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
                if (!TextUtils.isEmpty(title)) {
                    return title.toString();
                }
            }
        } catch (Throwable ignored) {
        }
        return "Alarm";
    }

    /** 组装 com.coloros.platformalarmclock.PlatformClockInfo(App 自带类, 反射构建) */
    private static Object buildClockInfo(StatusBarNotification sbn, Notification n) throws Exception {
        Class<?> cls = Xp.findClass(
                "com.coloros.platformalarmclock.PlatformClockInfo");
        Object info = Xp.newInstance(cls);
        Xp.callMethod(info, "setScheduleId", sAlarmId);
        Xp.callMethod(info, "setAlarmTime", resolveAlarmTime(sbn, n));
        Xp.callMethod(info, "setTagName", resolveAlarmTitle(n));
        Xp.callMethod(info, "setDelayReminder", sSnoozeIntent != null);
        Xp.callMethod(info, "setLanguage", Locale.getDefault().getLanguage());
        Xp.callMethod(info, "setmIsGarbAlarm", false);
        Xp.callMethod(info, "setmSnoozeTime", 60);
        Xp.callMethod(info, "setmRingNum", 1);
        return info;
    }

    private static boolean sendPendingIntent(PendingIntent pi, String what) {
        if (pi == null) {
            return false;
        }
        try {
            pi.send();
            Xp.log(TAG, "applied watch " + what + " via notification action");
            return true;
        } catch (Throwable t) {
            Xp.log(TAG, "notification " + what + " action failed: " + t);
            return false;
        }
    }

    private static boolean startAlarmAction(String action, String what) {
        Context c = sContext;
        if (c == null) {
            return false;
        }
        try {
            Intent i = new Intent(action).setPackage(sClockPkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            Xp.log(TAG, "applied watch " + what + " via AlarmClock intent");
            return true;
        } catch (Throwable t) {
            Xp.log(TAG, "AlarmClock " + what + " fallback failed: " + t);
            return false;
        }
    }

    private static void clearAlarm() {
        sNotificationKey = null;
        sForwardedKey = null;
        sClockPkg = null;
        sAlarmId = 0;
        sDismissIntent = null;
        sSnoozeIntent = null;
        sWatchAction = 0;
        sWatchCommandSent = false;
    }
}
