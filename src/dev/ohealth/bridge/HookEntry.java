package dev.ohealth.bridge;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;

import dev.ohealth.bridge.alarm.AlarmBridge;
import dev.ohealth.bridge.clock.ClockLink;
import dev.ohealth.bridge.dnd.DndBridge;
import dev.ohealth.bridge.dnd.DndSync;
import dev.ohealth.bridge.util.Diag;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * OHealthBridge 入口(libxposed API 101/102)。
 *
 * 目标: com.heytap.health (OPPO 健康) 在非 OPPO/ColorOS 手机(小米 HyperOS 等)上
 * 启用 OPPO Watch 的 勿扰同步 与 闹钟同步。
 */
public class HookEntry extends XposedModule {

    public static final String TARGET_PKG = "com.heytap.health";
    public static final String MODULE_VERSION = "2.19";
    private static final String TAG = "OHealthBridge";
    private static boolean sNotified = false;
    private static boolean sInstalled = false;
    private static volatile String sProcessName;
    private static volatile boolean sDndScheduled = false;
    private static volatile boolean sDndInstalled = false;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Xp.init(this, null);
        sProcessName = param.getProcessName();
        Xp.log(TAG, "onModuleLoaded process=" + sProcessName
                + " framework=" + getFrameworkName() + " api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        installAll(param.getPackageName(), param.getClassLoader(), "ready");
    }

    /** 兼容两种生命周期: 部分框架只回调 onPackageLoaded */
    @Override
    public void onPackageLoaded(io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam param) {
        installAll(param.getPackageName(), param.getDefaultClassLoader(), "loaded");
    }

    private void installAll(String pkg, ClassLoader cl, String phase) {
        try {
            if (!TARGET_PKG.equals(pkg)) {
                return;
            }
            Xp.setAppClassLoader(cl);
            Config.reload();
        Diag.record("module_version", MODULE_VERSION);
            Diag.record("package_loaded", pkg + " phase=" + phase
                    + " @" + System.currentTimeMillis());

            if (!Config.get().enabled) {
                Diag.record("module_state", "disabled_by_config");
                return;
            }
            BrandGate gate = BrandGate.fromConfig();
            if (!gate.enabledHere()) {
                Xp.log(TAG, "device is OPPO-family (native support), module idle.");
                Diag.record("module_state", "idle_oplus_family");
                return;
            }

            synchronized (HookEntry.class) {
                if (sInstalled) {
                    return;
                }
                sInstalled = true;
            }

            hookAppAnchor();
            installUiGateHooks();
            // v2.14: DND 类一律延迟到 Application.onCreate 之后安装, 早期绝不触碰
            // DoNotDisturbManager / DoNotDisturbRepository (见 hookAppAnchor / onAppReady)
            if (Config.get().alarmSync) installAlarmHooks();
            installInitHooks();
            Diag.record("module_state", "installed_all_hooks");
        } catch (Throwable t) {
            Xp.log(TAG, "installAll failed in phase " + phase, t);
            Diag.record("module_state", "installAll FAILED: " + t);
        }
    }

    /* ---------------- 目标 App 上下文锚点 + 注入通知 ---------------- */

    private void hookAppAnchor() {
        boolean ok = Xp.hookMethod(Application.class, "attachBaseContext",
                new Class<?>[]{Context.class}, new Xp.MethodHook() {
                    @Override
                    protected Object after(XposedInterface.Chain chain, Object result) {
                        try {
                            Context c = (Context) chain.getArg(0);
                            if (c != null) {
                                Context app = c.getApplicationContext();
                                Diag.setTargetContext(app);
                                notifyInjected(app);
                                if (Config.get().dndSync) {
                                    DndSync.ensureObserver(app);
                                }
                                scheduleAppReady(app);
                            }
                        } catch (Throwable t) {
                            Xp.log(TAG, "anchor diag failed", t);
                        }
                        return null;
                    }
                });
        boolean okCreate = Xp.hookMethod(Application.class, "onCreate", new Class<?>[0],
                new Xp.MethodHook() {
                    @Override
                    protected Object after(XposedInterface.Chain chain, Object result) {
                        try {
                            onAppReady();
                        } catch (Throwable t) {
                            Xp.log(TAG, "onAppReady from onCreate failed", t);
                        }
                        return result;
                    }
                });
        Diag.record("hook_anchor", (ok && okCreate) ? "installed"
                : "PARTIAL(" + ok + "," + okCreate + ")");
    }

    /** Application 子类不调 super.onCreate 时的兜底: 主 Looper 延迟 1.5s */
    private void scheduleAppReady(Context c) {
        if (sDndScheduled) {
            return;
        }
        sDndScheduled = true;
        try {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        onAppReady();
                    } catch (Throwable t) {
                        Xp.log(TAG, "deferred onAppReady failed", t);
                    }
                }
            }, 1500L);
        } catch (Throwable t) {
            Xp.log(TAG, "scheduleAppReady failed", t);
        }
    }

    /**
     * App 全局 Context 就绪后才会走到这里 (onCreate after / 延迟兜底 / MainActivity.onResume),
     * 幂等。此时才允许加载 DND 相关类并安装 Hook。
     */
    private void onAppReady() {
        synchronized (HookEntry.class) {
            if (sDndInstalled) {
                return;
            }
            sDndInstalled = true;
        }
        DndSync.markReady();
        Diag.record("dnd_ready", "process=" + sProcessName + " @" + System.currentTimeMillis());
        if (!Config.get().dndSync) {
            return;
        }
        // 手表 DND 事件(cmd109)在 :transport 进程的 DNDMsgProcessor 里接收, 必须全进程安装
        installWatchDndHooks();
        // 默认只在主进程安装 DND 类 Hook; 用 dnd_all_processes=true 可放开
        if (!isMainProcess() && !Config.get().dndAllProcesses) {
            Diag.record("hook_dnd", "skip_non_main process=" + sProcessName);
            return;
        }
        try {
            installDndHooks();
            Diag.record("hook_dnd_deferred", "installed process=" + sProcessName
                    + " @" + System.currentTimeMillis());
        } catch (Throwable t) {
            Xp.log(TAG, "DND hook install failed (non-fatal)", t);
            Diag.record("hook_dnd_deferred", "FAILED: " + t);
        }
    }

    private static boolean isMainProcess() {
        String p = sProcessName;
        return p == null || TARGET_PKG.equals(p);
    }

    /**
     * v2.15 手表→手机 DND 事件入口 (orig classes15.dex 实证):
     *   com.heytap.device.data.sporthealth.receive.DNDMsgProcessor
     *     acceptMsgTypes() = MsgType.create(1, 109)
     *     onMessageReceived(String mac, MessageEvent event)
     *       → DNDProto$DoNotDisturb.parseFrom(event.getData())
     *       → DoNotDisturbManager.onDeviceDNDChanged(status==1, statusChangedTime, supportLinkage==1)
     * 该类由 ReceiveMsgManager.initTransportProcessor 注册, 运行在 :transport 进程。
     * 之前只 hook 主进程的 onDeviceDNDChanged, 所以完全没有 source=WATCH 日志。
     */
    private void installWatchDndHooks() {
        try {
            Class<?> proc = Xp.findClass(
                    "com.heytap.device.data.sporthealth.receive.DNDMsgProcessor");
            Class<?> evtCls = Xp.findClass(
                    "com.oplus.wearable.linkservice.sdk.common.MessageEvent");
            boolean ok = Xp.hookMethod(proc, "onMessageReceived",
                    new Class<?>[]{String.class, evtCls}, new Xp.MethodHook() {
                        @Override
                        protected Object before(XposedInterface.Chain chain) {
                            try {
                                Object evt = chain.getArg(1);
                                String mac = String.valueOf(chain.getArg(0));
                                String svc = String.valueOf(Xp.callMethod(evt, "getServiceId"));
                                String cmd = String.valueOf(Xp.callMethod(evt, "getCommandId"));
                                Object data = Xp.callMethod(evt, "getData");
                                int len = data instanceof byte[] ? ((byte[]) data).length : -1;
                                Diag.record("watch_dnd_raw", "mac=" + mac + " svc=" + svc
                                        + " cmd=" + cmd + " dataLen=" + len
                                        + " @" + System.currentTimeMillis());
                                if (data instanceof byte[]) {
                                    Object proto = Xp.callStaticMethod(Xp.findClass(
                                            "com.heytap.health.protocol.dnd.DNDProto$DoNotDisturb"),
                                            "parseFrom", data);
                                    if (proto != null) {
                                        Object s = Xp.callMethod(proto, "getStatus");
                                        Object t = Xp.callMethod(proto, "getStatusChangedTime");
                                        Object l = Xp.callMethod(proto, "getSupportLinkage");
                                        boolean enable = s instanceof Integer && (Integer) s == 1;
                                        int time = t instanceof Integer ? (Integer) t : 0;
                                        boolean linkage = l instanceof Integer && (Integer) l == 1;
                                        DndSync.onWatchState(enable, time, linkage);
                                    }
                                }
                            } catch (Throwable t) {
                                Diag.record("watch_dnd_raw", "parse FAILED: " + t);
                            }
                            return Xp.MethodHook.PROCEED;
                        }
                    });
            Diag.record("hook_watch_dnd", ok ? "installed process=" + sProcessName
                    : "FAILED process=" + sProcessName);
        } catch (Throwable t) {
            Xp.log(TAG, "watch DND msg hook FAILED", t);
            Diag.record("hook_watch_dnd", "FAILED: " + t);
        }
    }

    private void notifyInjected(Context c) {
        if (sNotified || c == null) {
            return;
        }
        sNotified = true;
        try {
            NotificationManager nm = (NotificationManager)
                    c.getSystemService(Context.NOTIFICATION_SERVICE);
            String ch = "ohealth_bridge";
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(ch, "OHealthBridge",
                        NotificationManager.IMPORTANCE_LOW));
                Notification n = new Notification.Builder(c, ch)
                        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                        .setContentTitle("OHealthBridge")
                        .setContentText("勿扰与闹钟同步运行中")
                        .setAutoCancel(true)
                        .build();
                nm.notify(1001, n);
            }
        } catch (Throwable t) {
            Xp.log(TAG, "notifyInjected failed", t);
        }
    }

    /* ---------------- UI 入口 / 握手伪装网关 ---------------- */

    private void installUiGateHooks() {
        try {
            Class<?> ssu = Xp.findClass("com.heytap.device.data.utils.SleepSettingUtil");
            boolean ok = Xp.hookMethod(ssu, "isPhoneSupportZenModeLinkage", new Class<?>[0],
                    new Xp.MethodHook() {
                        @Override
                        protected Object after(XposedInterface.Chain chain, Object result) {
                            return Boolean.TRUE;
                        }
                    });
            Diag.record("hook_ui_dnd", ok ? "installed" : "FAILED");
        } catch (Throwable t) {
            Diag.record("hook_ui_dnd", "FAILED: " + t);
        }

        try {
            Class<?> gcv = Xp.findClass("com.heytap.health.base.oplus.GlobalClockVersionUtils");
            Class<?> ctx = Xp.findClass("android.content.Context");
            boolean ok1 = Xp.hookMethod(gcv, "isClockInstalled", new Class<?>[]{ctx},
                    new Xp.MethodHook() {
                        @Override
                        protected Object after(XposedInterface.Chain chain, Object result) {
                            return Boolean.TRUE;
                        }
                    });
            boolean ok2 = Xp.hookMethod(gcv, "isClockVersion7_2", new Class<?>[]{ctx},
                    new Xp.MethodHook() {
                        @Override
                        protected Object after(XposedInterface.Chain chain, Object result) {
                            return Boolean.TRUE;
                        }
                    });
            Diag.record("hook_ui_clock", (ok1 && ok2) ? "installed" : "FAILED(" + ok1 + "," + ok2 + ")");
        } catch (Throwable t) {
            Diag.record("hook_ui_clock", "FAILED: " + t);
        }

        try {
            Class<?> meb = Xp.findClass("com.heytap.health.devicemanager.pbbuild.MessageEventBuild");
            boolean ok = Xp.hookMethod(meb, "getColorOsVersion", new Class<?>[0],
                    new Xp.MethodHook() {
                        @Override
                        protected Object after(XposedInterface.Chain chain, Object result) {
                            int v = result instanceof Integer ? (Integer) result : 0;
                            return v < 17 ? Integer.valueOf(17) : result;
                        }
                    });
            Diag.record("hook_coloros_ver", ok ? "installed" : "FAILED");
        } catch (Throwable t) {
            Diag.record("hook_coloros_ver", "FAILED: " + t);
        }

        try {
            Class<?> su = Xp.findClass("com.heytap.health.base.app.SystemUtils");
            boolean ok = Xp.hookMethod(su, "isLinkageRom", new Class<?>[0],
                    new Xp.MethodHook() {
                        private final String[] whitelist = {
                                "MoreSettingItemAbility",
                                "MessageEventBuild",
                                "ClockMessageManager",
                                "ClockOobeReceiver",
                                "ClockLinkManager",
                                "GlobalClockDataController",
                                "InterConnSyncMainInitializer",
                                "ClockSyncHandler",
                                "InterAbility",
                                // v2.6: 闹钟/时钟/手表设置 包前缀域(闹钟设置页内部链路)
                                "com.heytap.wearable.watch.clock",
                                "com.heytap.health.settings.watch",
                                "com.heytap.device.data.utils.SleepSettingUtil",
                                "DeviceSetCardItem",
                                "device_settings",
                        };

                        @Override
                        protected Object after(XposedInterface.Chain chain, Object result) {
                            if (Boolean.TRUE.equals(result)) {
                                return result;
                            }
                            StackTraceElement[] st = new Throwable().getStackTrace();
                            for (StackTraceElement e : st) {
                                String cn = e.getClassName();
                                for (String w : whitelist) {
                                    if (cn.contains(w)) {
                                        return Boolean.TRUE;
                                    }
                                }
                            }
                            return result;
                        }
                    });
            Diag.record("hook_linkage_rom", ok ? "installed" : "FAILED");
        } catch (Throwable t) {
            Diag.record("hook_linkage_rom", "FAILED: " + t);
        }
    }

    /* ---------------- DND 勿扰同步 ---------------- */

    private void installDndHooks() {
        try {
            Class<?> companion = Xp.findClass(
                    "com.heytap.device.sleep.DoNotDisturbRepository$Companion");

            boolean ok1 = Xp.hookMethod(companion, "isPhoneSupportDND", new Class<?>[0],
                    new Xp.MethodHook() {
                        @Override
                        protected Object after(XposedInterface.Chain chain, Object result) {
                            return (DndBridge.hasPolicyAccess() || Config.get().rootFallback)
                                    ? Boolean.TRUE : Boolean.FALSE;
                        }
                    });

            boolean ok2 = Xp.hookMethod(companion, "readDNDFromPhone", new Class<?>[0],
                    new Xp.MethodHook() {
                        @Override
                        protected Object before(XposedInterface.Chain chain) {
                            Object proto = DndBridge.readDnd();
                            return proto != null ? proto : Xp.MethodHook.PROCEED;
                        }
                    });

            boolean ok3 = Xp.hookMethod(companion, "changePhoneDND", new Class<?>[]{boolean.class},
                    new Xp.MethodHook() {
                        @Override
                        protected Object before(XposedInterface.Chain chain) {
                            boolean enable = Boolean.TRUE.equals(chain.getArg(0));
                            return DndBridge.setDnd(enable) ? null : Xp.MethodHook.PROCEED;
                        }
                    });

            Xp.log(TAG, "DND hooks installed");
            Diag.record("hook_dnd", (ok1 && ok2 && ok3) ? "installed" : "PARTIAL(" + ok1 + ok2 + ok3 + ")");
        } catch (Throwable t) {
            Xp.log(TAG, "DND hooks FAILED (app version changed?)", t);
            Diag.record("hook_dnd", "FAILED: " + t);
        }
        installDndSyncHooks();
    }

    /**
     * 6.9.37 关键补充: onPhoneDNDChanged() 在 isSyncOpen=false 时直接 return,
     * onDeviceDNDChanged() 也会在 isSyncOpen=false 时 return; 两者都发生在本模块 hook 之后,
     * 所以在这里各自补一条不依赖 App 内部闸门的直连链路。
     */
    private void installDndSyncHooks() {
        try {
            Class<?> mgr = Xp.findClass("com.heytap.device.sleep.DoNotDisturbManager");
            boolean ok1 = Xp.hookMethod(mgr, "onPhoneDNDChanged", new Class<?>[0],
                    new Xp.MethodHook() {
                        @Override
                        protected Object after(XposedInterface.Chain chain, Object result) {
                            DndSync.onPhoneStateChanged();
                            return result;
                        }
                    });
            boolean ok2 = Xp.hookMethod(mgr, "onDeviceDNDChanged",
                    new Class<?>[]{boolean.class, int.class, boolean.class},
                    new Xp.MethodHook() {
                        @Override
                        protected Object before(XposedInterface.Chain chain) {
                            boolean enable = Boolean.TRUE.equals(chain.getArg(0));
                            int time = chain.getArg(1) instanceof Integer ? (Integer) chain.getArg(1) : 0;
                            boolean syncOpen = Boolean.TRUE.equals(chain.getArg(2));
                            DndSync.onWatchState(enable, time, syncOpen);
                            return Xp.MethodHook.PROCEED;
                        }
                    });
            Diag.record("hook_dnd_sync", (ok1 && ok2) ? "installed"
                    : "PARTIAL(" + ok1 + "," + ok2 + ")");
        } catch (Throwable t) {
            Xp.log(TAG, "DND sync hooks FAILED", t);
            Diag.record("hook_dnd_sync", "FAILED: " + t);
        }
        installDndAckHooks();
    }

    /**
     * v2.15: changeDeviceDND(Z,I) 是异步的 —— 反射调用返回 ≠ 手表执行成功。
     * 它的回调类 DoNotDisturbRepository$Companion$changeDeviceDND$1 实现 DMMessageCallback:
     *   onSuccess(String mac, MessageEvent response) / onError(DMCallException)
     * 这里 hook 两者作为真正的设备回执; 并 hook readDNDFromDevice 记录手表状态回读。
     */
    private void installDndAckHooks() {
        int ok = 0;
        try {
            Class<?> cb = Xp.findClass(
                    "com.heytap.device.sleep.DoNotDisturbRepository$Companion$changeDeviceDND$1");
            Class<?> evtCls = Xp.findClass(
                    "com.oplus.wearable.linkservice.sdk.common.MessageEvent");
            if (Xp.hookMethod(cb, "onSuccess", new Class<?>[]{String.class, evtCls},
                    new Xp.MethodHook() {
                        @Override
                        protected Object after(XposedInterface.Chain chain, Object result) {
                            Object mac = chain.getArg(0);
                            Object evt = chain.getArg(1);
                            String sid = evt == null ? "null" : String.valueOf(Xp.callMethod(evt, "getServiceId"));
                            String cid = evt == null ? "null" : String.valueOf(Xp.callMethod(evt, "getCommandId"));
                            Object data = evt == null ? null : Xp.callMethod(evt, "getData");
                            int len = data instanceof byte[] ? ((byte[]) data).length : -1;
                            Diag.record("dnd_ack", "onSuccess mac=" + mac + " svc=" + sid
                                    + " cmd=" + cid + " dataLen=" + len
                                    + " @" + System.currentTimeMillis());
                            return result;
                        }
                    })) {
                ok++;
            }
            if (Xp.hookMethod(cb, "onError", new Class<?>[]{Xp.findClass(
                    "com.heytap.health.devicemanager.client.call.DMCallException")},
                    new Xp.MethodHook() {
                        @Override
                        protected Object after(XposedInterface.Chain chain, Object result) {
                            Diag.record("dnd_ack", "onError ex=" + chain.getArg(0)
                                    + " @" + System.currentTimeMillis());
                            return result;
                        }
                    })) {
                ok++;
            }
        } catch (Throwable t) {
            Xp.log(TAG, "DND ack hooks FAILED", t);
            Diag.record("dnd_ack", "hook FAILED: " + t);
        }
        try {
            Class<?> companion = Xp.findClass(
                    "com.heytap.device.sleep.DoNotDisturbRepository$Companion");
            Class<?> cont = Xp.findClass("kotlin.coroutines.Continuation");
            if (Xp.hookMethod(companion, "readDNDFromDevice", new Class<?>[]{cont},
                    new Xp.MethodHook() {
                        @Override
                        protected Object after(XposedInterface.Chain chain, Object result) {
                            if (result != null && result.getClass().getName().contains("DoNotDisturb")) {
                                Object s = Xp.callMethod(result, "getStatus");
                                Object t = Xp.callMethod(result, "getStatusChangedTime");
                                Object l = Xp.callMethod(result, "getSupportLinkage");
                                Diag.record("watch_dnd_read", "status=" + s + " time=" + t
                                        + " supportLinkage=" + l
                                        + " @" + System.currentTimeMillis());
                                Diag.record("dnd_support_linkage", "value=" + l
                                        + " source=readDNDFromDevice(cmd107) @"
                                        + System.currentTimeMillis());
                            }
                            return result;
                        }
                    })) {
                ok++;
            }
        } catch (Throwable t) {
            Xp.log(TAG, "readDNDFromDevice hook FAILED", t);
        }
        Diag.record("hook_dnd_ack", ok + "/3 installed");
    }

    /* ---------------- 闹钟同步 ---------------- */

    private void installAlarmHooks() {
        try {
            Class<?> ctrl = Xp.findClass(
                    "com.heytap.wearable.watch.clock.manager.AlarmDataController");
            boolean ok1 = Xp.hookMethod(ctrl, "closePhoneAlarm", new Class<?>[0],
                    new Xp.MethodHook() {
                        @Override
                        protected Object before(XposedInterface.Chain chain) {
                            return AlarmBridge.dismissCurrent() ? null : Xp.MethodHook.PROCEED;
                        }
                    });
            boolean ok2 = Xp.hookMethod(ctrl, "delayPhoneAlarm", new Class<?>[0],
                    new Xp.MethodHook() {
                        @Override
                        protected Object before(XposedInterface.Chain chain) {
                            return AlarmBridge.snoozeCurrent() ? null : Xp.MethodHook.PROCEED;
                        }
                    });
            Xp.log(TAG, "Alarm controller hooks installed");
            Diag.record("hook_alarm_ctrl", (ok1 && ok2) ? "installed" : "PARTIAL(" + ok1 + ok2 + ")");
        } catch (Throwable t) {
            Xp.log(TAG, "Alarm controller hooks FAILED", t);
            Diag.record("hook_alarm_ctrl", "FAILED: " + t);
        }

        try {
            Class<?> svc = Xp.findClass(
                    "com.heytap.health.watch.commonnotification.HeytapNotificationListenerService");
            int n1 = Xp.hookAllMethods(svc, "onNotificationPosted", new Xp.MethodHook() {
                @Override
                protected Object after(XposedInterface.Chain chain, Object result) {
                    Object sbn = chain.getArgs().isEmpty() ? null : chain.getArg(0);
                    Object thiz = chain.getThisObject();
                    if (sbn != null && thiz instanceof Context) {
                        AlarmBridge.onNotificationPosted((Context) thiz, sbn);
                    }
                    return null;
                }
            });
            int n2 = Xp.hookAllMethods(svc, "onNotificationRemoved", new Xp.MethodHook() {
                @Override
                protected Object after(XposedInterface.Chain chain, Object result) {
                    Object sbn = chain.getArgs().isEmpty() ? null : chain.getArg(0);
                    if (sbn != null) {
                        AlarmBridge.onNotificationRemoved(sbn);
                    }
                    return null;
                }
            });
            Xp.log(TAG, "NotificationListener hooks installed x" + n1 + "/" + n2);
            Diag.record("hook_ntl", (n1 > 0 && n2 > 0) ? "installed" : "PARTIAL(" + n1 + "," + n2 + ")");
        } catch (Throwable t) {
            Xp.log(TAG, "NotificationListener hooks FAILED", t);
            Diag.record("hook_ntl", "FAILED: " + t);
        }
    }

    /* ---------------- 初始化保底 ---------------- */

    private void installInitHooks() {
        try {
            Class<?> main = Xp.findClass("com.heytap.health.main.MainActivity");
            Xp.hookMethod(main, "onResume", new Class<?>[0], new Xp.MethodHook() {
                private boolean done = false;

                @Override
                protected Object after(XposedInterface.Chain chain, Object result) {
                    onAppReady();
                    if (done) {
                        return result;
                    }
                    done = true;
                    Diag.record("module_active", "onResume@" + System.currentTimeMillis());
                    try {
                        Class<?> mgr = Xp.findClass(
                                "com.heytap.device.sleep.DoNotDisturbManager");
                        Object instance = Xp.getStaticObjectField(mgr, "INSTANCE");
                        Xp.callMethod(instance, "tryInitDND");
                        Xp.log(TAG, "DoNotDisturbManager.tryInitDND() ok");
                        if (Config.get().dndSync) {
                            DndSync.sendSupportIfNeeded();
                        }
                    } catch (Throwable t) {
                        Xp.log(TAG, "tryInitDND failed", t);
                    }
                    try {
                        ClockLink.warmUp();
                    } catch (Throwable t) {
                        Xp.log(TAG, "ClockLink warmUp failed", t);
                    }
                    return result;
                }
            });
            Xp.log(TAG, "init hooks installed");
            Diag.record("hook_init", "installed");
        } catch (Throwable t) {
            Xp.log(TAG, "MainActivity hook FAILED (non-fatal)", t);
            Diag.record("hook_init", "FAILED: " + t);
        }
    }
}
