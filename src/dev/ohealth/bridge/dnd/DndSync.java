package dev.ohealth.bridge.dnd;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import dev.ohealth.bridge.Xp;
import dev.ohealth.bridge.util.Diag;

/**
 * DND 双向同步驱动 (6.9.37)。
 *
 * v2.16 关键变更: 不再依赖 App 的 changeDeviceDND(Z,I) 来构造协议对象。
 * 反编译实证 (orig_dnd_repo.smali:15-60):
 *   changeDeviceDND(Z,I) 只 setStatus + setStatusChangedTime,
 *   **没有 setSupportLinkage** → 下发给手表的 proto 里 supportLinkage 恒为 0。
 * 而手表上报的 DNDProto 里 supportLinkage=0 会被 App 判为 "device sync is close"
 * (DoNotDisturbManager$syncPhoneAndDeviceDNDRaw$1.invokeSuspend :64-77)。
 * 因此本类自己构造 MessageEvent(1,108,bytes), 显式 setSupportLinkage(1),
 * 再用 DMCallApi.sendMessageWithCallback$default + 自建 DMMessageCallback 代理取 ACK。
 *
 * 闭环判据: 发送 cmd108 后等待 cmd109 手表状态回报, 与目标一致才记 dnd_sync_result=success。
 */
public final class DndSync {

    private static final String TAG = "OHealthBridge.DNDSync";
    private static final String REPO = "com.heytap.device.sleep.DoNotDisturbRepository";
    private static final String DMHEYTAP = "com.heytap.health.devicemanager.client.DMHeytap";
    private static final String MANAGER_API = "com.heytap.health.devicemanager.client.api.DMManagerApi";
    private static final String MESSAGE_API = "com.heytap.health.devicemanager.client.api.primart.DMMessageApi";
    private static final String CALL_API = "com.heytap.health.devicemanager.client.api.primart.DMCallApi";
    private static final String MSG_CALLBACK = "com.heytap.health.devicemanager.client.call.DMMessageCallback";
    private static final String RESP_TYPE = "com.heytap.health.devicemanager.client.call.DMResponseType";
    private static final String MESSAGE_EVENT = "com.oplus.wearable.linkservice.sdk.common.MessageEvent";
    private static final String PROTO_BUILDER =
            "com.heytap.health.protocol.dnd.DNDProto$DoNotDisturb$Builder";
    private static final String SUPPORT_BUILDER =
            "com.heytap.health.protocol.dnd.DNDProto$DoNotDisturbSupport$Builder";

    /** 回显抑制窗口: 手机→手表后忽略手表回显, 手表→手机后忽略手机回写 */
    private static final long ECHO_GUARD_MS = 6000L;
    private static final long DEDUP_MS = 4000L;
    /** 等待 cmd109 回报的闭环超时 */
    private static final long REPLY_TIMEOUT_MS = 15000L;

    private static final Object LOCK = new Object();
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private static volatile boolean sObserverRegistered = false;
    private static volatile boolean sSupportSent = false;
    private static long sSupportAt = 0L;
    /** v2.14: App 全局 Context 就绪前, 一律不反射任何 OHealth DND 类 */
    private static volatile boolean sReady = false;
    private static int sLastSentState = -1;
    private static long sLastSentAt = 0L;
    private static long sWatchEchoUntil = 0L;
    private static long sPhoneEchoUntil = 0L;
    private static int sLastWatchState = -1;
    /** 闭环: 最近一次 cmd108 的目标状态/时间 */
    private static int sPendingState = -1;
    private static long sPendingAt = 0L;
    private static int sPendingTs = 0;

    private DndSync() {
    }

    /** 由 HookEntry.onAppReady() 在 Application.onCreate 之后调用 */
    public static void markReady() {
        sReady = true;
    }

    /** Application.attachBaseContext 锚点: 每个进程都自己注册 zen_mode 监听 (含 :transport) */
    public static void ensureObserver(final Context c) {
        if (sObserverRegistered || c == null) {
            return;
        }
        sObserverRegistered = true;
        HANDLER.post(new Runnable() {
            @Override
            public void run() {
                try {
                    ContentObserver obs = new ContentObserver(HANDLER) {
                        @Override
                        public void onChange(boolean selfChange, Uri uri) {
                            if (uri != null && !uri.toString().contains("zen_mode")) {
                                return;
                            }
                            onPhoneStateChanged();
                        }
                    };
                    // 父 URI + 精确 URI 双注册: 不同 ROM 的 notifyChange 粒度不一致
                    c.getContentResolver().registerContentObserver(
                            Settings.Global.CONTENT_URI, true, obs);
                    c.getContentResolver().registerContentObserver(
                            Uri.withAppendedPath(Settings.Global.CONTENT_URI, "zen_mode"),
                            true, obs);
                    Diag.record("dnd_observer", "zen_mode registered(both) pid="
                            + android.os.Process.myPid() + " @" + System.currentTimeMillis());
                } catch (Throwable t) {
                    Xp.log(TAG, "register zen_mode observer failed", t);
                    Diag.record("dnd_observer", "FAILED: " + t);
                }
            }
        });
    }

    /** 手机 DND 变化 (自有 ContentObserver 或 hook onPhoneDNDChanged 触发) → 去抖后直发手表 */
    public static void onPhoneStateChanged() {
        HANDLER.removeCallbacks(SYNC_TASK);
        HANDLER.postDelayed(SYNC_TASK, 400L);
    }

    private static final Runnable SYNC_TASK = new Runnable() {
        @Override
        public void run() {
            syncPhoneToWatch("PHONE");
        }
    };

    /** 15s 内没等到 cmd109 回报则明确记闭环失败 */
    private static final Runnable TIMEOUT_TASK = new Runnable() {
        @Override
        public void run() {
            synchronized (LOCK) {
                if (sPendingState >= 0
                        && System.currentTimeMillis() - sPendingAt >= REPLY_TIMEOUT_MS) {
                    Diag.record("dnd_sync_result", "failure reason=no_watch_reply want="
                            + sPendingState + " ts=" + sPendingTs
                            + " @" + System.currentTimeMillis());
                    sPendingState = -1;
                }
            }
        }
    };

    /** 手机 → 手表 直接下发。 */
    public static void syncPhoneToWatch(String source) {
        long now = System.currentTimeMillis();
        if (!sReady) {
            Diag.record("dnd_sync_ignored", "source=" + source + " reason=not_ready @" + now);
            return;
        }
        int[] sf = DndBridge.readStateFilter();
        int state = sf[0];
        int filter = sf[1];
        if (state < 0) {
            Diag.record("dnd_sync_ignored", "source=" + source + " reason=no_context @" + now);
            return;
        }
        synchronized (LOCK) {
            if (state == sLastSentState && now - sLastSentAt < DEDUP_MS) {
                Diag.record("dnd_sync_ignored", "source=" + source + " reason=dedup state=" + state);
                return;
            }
            if ("PHONE".equals(source)
                    && (now < sWatchEchoUntil || DndBridge.inEcho("dnd_watch_echo_until", now))) {
                Diag.record("dnd_sync_ignored", "source=PHONE reason=watch_echo state=" + state);
                return;
            }
            // 上一轮闭环若已超时且无 cmd109 回报, 明确记失败
            if (sPendingState >= 0 && now - sPendingAt > REPLY_TIMEOUT_MS) {
                Diag.record("dnd_sync_result", "failure reason=no_watch_reply want="
                        + sPendingState + " ts=" + sPendingTs + " @" + now);
            }
            sLastSentState = state;
            sLastSentAt = now;
            sPhoneEchoUntil = now + ECHO_GUARD_MS;
            DndBridge.markEcho("dnd_phone_echo_until", sPhoneEchoUntil);
        }
        if (!DndBridge.claimSend(state, 2500L)) {
            Diag.record("dnd_sync_ignored", "source=" + source + " reason=cross_process_dedup state=" + state);
            return;
        }
        int changedAt = DndBridge.lastChangedSeconds();
        synchronized (LOCK) {
            sPendingState = state;
            sPendingAt = now;
            sPendingTs = changedAt;
        }
        HANDLER.removeCallbacks(TIMEOUT_TASK);
        HANDLER.postDelayed(TIMEOUT_TASK, REPLY_TIMEOUT_MS);
        Diag.record("dnd_sync_source", source);
        Diag.record("dnd_event", "source=PHONE state=" + state + " filter=" + filter + " @" + now);
        Diag.record("dnd_send_method", CALL_API
                + ".sendMessageWithCallback$default(DMCallApi,String,MessageEvent,DMMessageCallback,DMResponseType,J,I,I,Object)");
        Diag.record("dnd_send_args", "node=<activeNodeId> svc=1 cmd=108"
                + " enable=" + (state == 1) + " status=" + state
                + " statusChangedTime=" + changedAt + "(秒) supportLinkage=1 @" + now);
        Diag.record("dnd_send_params", "method=MessageEvent(1,108,DNDProto$DoNotDisturb)"
                + " enable=" + (state == 1) + " statusChangedTime=" + changedAt
                + " proto=DNDProto$DoNotDisturb{status,statusChangedTime,supportLinkage} @" + now);
        Diag.record("dnd_sync_result", "pending want=" + state + " ts=" + changedAt + " @" + now);
        Diag.record("dnd_ack", "pending (等待 DMMessageCallback) @" + now);
        sendSupportIfNeeded();
        String result = sendToWatch(state == 1, changedAt);
        Diag.record("dnd_send", "enable=" + (state == 1) + " ts=" + changedAt
                + " result=" + result + " @" + System.currentTimeMillis());
    }

    /** 手表 → 手机: hook onDeviceDNDChanged(Z,I,Z) 的 before 回调 */
    public static void onWatchState(boolean enable, int time) {
        onWatchState(enable, time, false);
    }

    /**
     * 手表 → 手机 (cmd109 完整参数版)。
     * @param syncOpen 手表上报的 supportLinkage==1
     */
    public static void onWatchState(boolean enable, int time, boolean syncOpen) {
        long now = System.currentTimeMillis();
        int watchState = enable ? 1 : 0;
        Diag.record("watch_dnd_event", "enable=" + enable + " statusChangedTime=" + time
                + " supportLinkage=" + (syncOpen ? 1 : 0) + " @" + now);
        Diag.record("dnd_support_linkage", "value=" + (syncOpen ? 1 : 0)
                + " source=cmd109 @" + now);
        Diag.record("dnd_watch_reply", "cmd=109 enable=" + enable
                + " statusChangedTime=" + time + " @" + now);
        Diag.record("dnd_watch_state", "enable=" + enable + " time=" + time + " @" + now);

        // 闭环校验: 与最近一次 cmd108 目标比对 (必须在 echo/same_state 早退之前做)
        synchronized (LOCK) {
            if (sPendingState >= 0 && now - sPendingAt <= REPLY_TIMEOUT_MS) {
                boolean match = watchState == sPendingState;
                Diag.record("dnd_sync_result", match
                        ? "success cmd109 matched state=" + watchState + " ts=" + time + " @" + now
                        : "failure reason=mismatch want=" + sPendingState
                            + " got=" + watchState + " @" + now);
                sPendingState = -1;
            }
        }

        if (!sReady) {
            Diag.record("dnd_sync_ignored", "source=WATCH reason=not_ready @" + now);
            return;
        }
        int[] sf = DndBridge.readStateFilter();
        int phoneState = sf[0];
        synchronized (LOCK) {
            sLastWatchState = watchState;
            if (now < sPhoneEchoUntil || DndBridge.inEcho("dnd_phone_echo_until", now)) {
                Diag.record("dnd_sync_ignored", "source=WATCH reason=phone_echo state=" + watchState);
                return;
            }
            if (phoneState >= 0 && phoneState == watchState) {
                Diag.record("dnd_sync_ignored", "source=WATCH reason=same_state state=" + watchState);
                return;
            }
            sWatchEchoUntil = now + ECHO_GUARD_MS;
            DndBridge.markEcho("dnd_watch_echo_until", sWatchEchoUntil);
        }
        Diag.record("dnd_sync_source", "WATCH");
        Diag.record("dnd_event", "source=WATCH old=" + phoneState + " new=" + watchState
                + " filter=" + sf[1] + " devTime=" + time + " @" + now);
        boolean ok = DndBridge.setDnd(enable, "WATCH");
        int[] after = DndBridge.readStateFilter();
        Diag.record("dnd_phone_state_after_apply", "state=" + after[0] + " filter=" + after[1]
                + " @" + System.currentTimeMillis());
        Diag.record("dnd_apply", "source=WATCH enable=" + enable + " ok=" + ok
                + " @" + System.currentTimeMillis());
    }

    /** cmd110: 告诉手表"手机支持勿扰联动", 取 DMMessageApi.sendMessage 的 boolean 回执 */
    public static void sendSupportIfNeeded() {
        if (!sReady) {
            return;
        }
        long now = System.currentTimeMillis();
        if (sSupportSent && now - sSupportAt < 60000L) {
            return;
        }
        sSupportSent = true;
        sSupportAt = now;
        try {
            Object b = Xp.newInstance(Xp.findClass(SUPPORT_BUILDER));
            b = Xp.callMethod(b, "setSupport", Integer.valueOf(1));
            Object proto = Xp.callMethod(b, "build");
            byte[] bytes = (byte[]) Xp.callMethod(proto, "toByteArray");
            Object evt = newMessageEvent(1, 110, bytes);
            Object dp = Xp.getStaticObjectField(Xp.findClass(DMHEYTAP), "devicePrimary");
            Object api = Xp.getObjectField(dp, "messageApi");
            Method sm = Xp.findClass(MESSAGE_API).getMethod("sendMessage", Xp.findClass(MESSAGE_EVENT));
            Object r = sm.invoke(api, evt);
            Diag.record("dnd_support", "cmd110 support=1 result=" + r + " pid="
                    + android.os.Process.myPid() + " @" + now);
        } catch (Throwable t) {
            try {
                Object c = companion();
                Method m = Xp.findMethodExact(c.getClass(),
                        "sendPhoneDNDSupportToDevice", boolean.class);
                m.invoke(c, Boolean.TRUE);
                Diag.record("dnd_support", "cmd110 via App method (direct failed: " + t + ")");
            } catch (Throwable t2) {
                Diag.record("dnd_support", "FAILED: " + t2);
                Xp.log(TAG, "cmd110 support failed", t2);
            }
        }
    }

    /* ---------------- 实际下发 ---------------- */

    private static String sendToWatch(boolean enable, int changedAt) {
        Object evt;
        try {
            byte[] bytes = buildProtoBytes(enable, changedAt, 1);
            recordProtoBytes(bytes, enable, changedAt);
            evt = newMessageEvent(1, 108, bytes);
        } catch (Throwable t) {
            Diag.record("dnd_proto_bytes", "FAILED: " + t);
            return legacyChangeDeviceDND(enable, changedAt);
        }
        try {
            Object mgrApi = Xp.getStaticObjectField(Xp.findClass(DMHEYTAP), "managerApi");
            Object nodeId = getActiveNodeId(mgrApi);
            return sendViaCallApi(nodeId, evt);
        } catch (Throwable t) {
            Xp.log(TAG, "DMCallApi send failed, fallback DMMessageApi", t);
            Diag.record("dnd_send_note", "DMCallApi FAILED: " + t);
            try {
                return "DMMessageApi(108)=" + sendViaMessageApi(evt);
            } catch (Throwable t2) {
                Diag.record("dnd_send_note", "DMMessageApi FAILED: " + t2);
                return legacyChangeDeviceDND(enable, changedAt);
            }
        }
    }

    /**
     * 与 App 自身 changeDeviceDND 等价的调用 (mask=56 → responseType=TypeSymmetry,
     * timeout=5000ms, retry=0), 但用我们自己的 DMMessageCallback 代理收 ACK。
     */
    private static String sendViaCallApi(Object nodeId, Object evt) throws Exception {
        if (nodeId == null) {
            Diag.record("dnd_send_note", "activeNode=null, fallback DMMessageApi");
            throw new IllegalStateException("activeNodeId=null");
        }
        Class<?> apiCls = Xp.findClass(CALL_API);
        Class<?> evtCls = Xp.findClass(MESSAGE_EVENT);
        Class<?> cbCls = Xp.findClass(MSG_CALLBACK);
        Class<?> respCls = Xp.findClass(RESP_TYPE);
        Object dp = Xp.getStaticObjectField(Xp.findClass(DMHEYTAP), "devicePrimary");
        Object callApi = Xp.getObjectField(dp, "callApi");
        if (callApi == null) {
            throw new IllegalStateException("callApi=null");
        }
        Object callback = Proxy.newProxyInstance(cbCls.getClassLoader(),
                new Class<?>[]{cbCls}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("onSuccess".equals(name)) {
                            Object mac = args == null || args.length < 1 ? null : args[0];
                            Object resp = args == null || args.length < 2 ? null : args[1];
                            String svc = resp == null ? "null" : String.valueOf(Xp.callMethod(resp, "getServiceId"));
                            String cmd = resp == null ? "null" : String.valueOf(Xp.callMethod(resp, "getCommandId"));
                            Diag.record("dnd_ack", "onSuccess mac=" + mac + " svc=" + svc
                                    + " cmd=" + cmd + " @" + System.currentTimeMillis());
                            Diag.record("dnd_transport_ack", "success @" + System.currentTimeMillis());
                        } else if ("onError".equals(name)) {
                            Object ex = args == null || args.length < 1 ? null : args[0];
                            Diag.record("dnd_ack", "onError ex=" + ex + " @" + System.currentTimeMillis());
                            Diag.record("dnd_transport_ack", "failure @" + System.currentTimeMillis());
                        } else if ("toString".equals(name)) {
                            return "OHealthBridge.DndCallback";
                        } else if ("hashCode".equals(name)) {
                            return Integer.valueOf(System.identityHashCode(proxy));
                        } else if ("equals".equals(name)) {
                            return Boolean.valueOf(proxy == (args == null ? null : args[0]));
                        }
                        return null;
                    }
                });
        Method dflt = apiCls.getDeclaredMethod("sendMessageWithCallback$default",
                apiCls, String.class, evtCls, cbCls, respCls,
                long.class, int.class, int.class, Object.class);
        dflt.setAccessible(true);
        dflt.invoke(null, callApi, nodeId, evt, callback, null,
                Long.valueOf(0), Integer.valueOf(0), Integer.valueOf(56), null);
        return "DMCallApi(108) node=" + nodeId;
    }

    private static Object sendViaMessageApi(Object evt) throws Exception {
        Object dp = Xp.getStaticObjectField(Xp.findClass(DMHEYTAP), "devicePrimary");
        Object api = Xp.getObjectField(dp, "messageApi");
        if (api == null) {
            throw new IllegalStateException("messageApi=null");
        }
        Method sm = Xp.findClass(MESSAGE_API).getMethod("sendMessage", Xp.findClass(MESSAGE_EVENT));
        return sm.invoke(api, evt);
    }

    /** 最后兜底: 复用 App 自带 changeDeviceDND(Z,I) (proto 里 supportLinkage=0) */
    private static String legacyChangeDeviceDND(boolean enable, int changedAt) {
        try {
            Object c = companion();
            Method m = Xp.findMethodExact(c.getClass(), "changeDeviceDND", boolean.class, int.class);
            m.invoke(c, enable, changedAt);
            return "legacy changeDeviceDND(Z,I)";
        } catch (Throwable t) {
            return "FAILED: " + t;
        }
    }

    private static byte[] buildProtoBytes(boolean enable, int changedAt, int linkage) throws Exception {
        Object b = Xp.newInstance(Xp.findClass(PROTO_BUILDER));
        b = Xp.callMethod(b, "setStatus", Integer.valueOf(enable ? 1 : 0));
        b = Xp.callMethod(b, "setStatusChangedTime", Integer.valueOf(changedAt));
        b = Xp.callMethod(b, "setSupportLinkage", Integer.valueOf(linkage));
        Object proto = Xp.callMethod(b, "build");
        Object bytes = Xp.callMethod(proto, "toByteArray");
        if (!(bytes instanceof byte[])) {
            throw new IllegalStateException("DNDProto.toByteArray failed");
        }
        return (byte[]) bytes;
    }

    /** 抓 cmd108 完整序列化数据, 便于对比开/关两次发送 */
    private static void recordProtoBytes(byte[] bytes, boolean enable, int changedAt) {
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < bytes.length && i < 48; i++) {
            hex.append(String.format("%02x", Integer.valueOf(bytes[i] & 0xff)));
        }
        Diag.record("dnd_proto_bytes", "enable=" + enable + " ts=" + changedAt
                + " len=" + bytes.length + " hex=" + hex + " @" + System.currentTimeMillis());
    }

    private static Object newMessageEvent(int serviceId, int cmdId, byte[] bytes) throws Exception {
        Class<?> evtCls = Xp.findClass(MESSAGE_EVENT);
        try {
            java.lang.reflect.Constructor<?> ctor = evtCls.getDeclaredConstructor(
                    int.class, int.class, byte[].class);
            ctor.setAccessible(true);
            return ctor.newInstance(Integer.valueOf(serviceId), Integer.valueOf(cmdId), bytes);
        } catch (Throwable t) {
            Object evt = Xp.newInstance(evtCls, Integer.valueOf(serviceId),
                    Integer.valueOf(cmdId), bytes);
            if (evt == null) {
                throw new IllegalStateException("MessageEvent ctor failed");
            }
            return evt;
        }
    }

    private static Object getActiveNodeId(Object managerApi) {
        if (managerApi == null) {
            return null;
        }
        try {
            Method m = Xp.findClass(MANAGER_API).getMethod("getActiveNodeId");
            return m.invoke(managerApi);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object companion() throws ClassNotFoundException {
        Object c = Xp.getStaticObjectField(Xp.findClass(REPO), "Companion");
        if (c == null) {
            throw new ClassNotFoundException(REPO + "$Companion");
        }
        return c;
    }
}
