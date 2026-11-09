package dev.ohealth.bridge.clock;

import android.content.Context;

import java.lang.reflect.Method;

import dev.ohealth.bridge.Xp;

/**
 * ClockLinkManager / AlarmDataController 解析器。
 *
 * 目标 App 版本差异:
 *  - 6.9.x: AlarmDataController 为 Kotlin lazy 属性, 公开 getMAlarmDataController(),
 *           调用即自动创建, 无任何 OPROM 依赖 —— 直接用。
 *  - 6.7.x: init(Context) 网关内直接赋值字段 mAlarmDataController —— 仿修改版,
 *           反射设 mContext 字段(若存在) + init(ctx) + 读字段。
 *  - 兜底: mAlarmDataController$delegate (kotlin.Lazy) 调 getValue()。
 */
public final class ClockLink {

    private static final String TAG = "OHealthBridge.ClockLink";
    private static final String CLM = "com.heytap.wearable.watch.clock.manager.ClockLinkManager";
    private static final String ADC = "com.heytap.wearable.watch.clock.manager.AlarmDataController";

    private static boolean sLoggedUnavailable = false;

    private ClockLink() {
    }

    /** 主界面 onResume 时预热: 提前物化单例与控制器 */
    public static void warmUp() {
        getAlarmController(null);
    }

    /**
     * 取 AlarmDataController 实例(按版本多策略)。
     * @param context 可空; 仅旧版 init(Context) 需要
     */
    public static Object getAlarmController(Context context) {
        try {
            Class<?> clmCls = Xp.findClass(CLM);
            Object clm = resolveManager(clmCls);

            // 策略1: 6.9.x getter (lazy 自动创建)
            try {
                Method m = clmCls.getMethod("getMAlarmDataController");
                Object r = m.invoke(clm);
                if (r != null) {
                    dev.ohealth.bridge.util.Diag.record("clocklink", "strategy1 ok");
                    return r;
                }
                dev.ohealth.bridge.util.Diag.record("clocklink", "strategy1 null");
            } catch (Throwable ignored) {
            }

            // 策略2: 直接字段 (6.7.x)
            Object ctrl = getFieldValue(clm, "mAlarmDataController");
            if (ctrl != null) {
                return ctrl;
            }

            // 策略3: 旧版 init(Context) 赋值后再读
            try {
                if (hasMethod(clmCls, "init", Context.class) && context != null) {
                    Xp.callMethod(clm, "init", context);
                    Object after = getFieldValue(clm, "mAlarmDataController");
                    if (after != null) {
                        return after;
                    }
                    // init 后仍无 → 旧版网关在 OPPO 分支内, 手动 new 并赋值(镜像修改版做法)
                    Object created = Xp.newInstance(Xp.findClass(ADC));
                    trySetField(clm, "mContext", context);
                    trySetField(clm, "mAlarmDataController", created);
                    return created;
                }
            } catch (Throwable ignored) {
            }

            // 策略4: lazy delegate
            Object delegate = getFieldValue(clm, "mAlarmDataController$delegate");
            if (delegate != null) {
                return Xp.callMethod(delegate, "getValue");
            }

            return null;
        } catch (Throwable t) {
            if (!sLoggedUnavailable) {
                sLoggedUnavailable = true;
                Xp.log(TAG, "getAlarmController failed: " + t);
            }
            return null;
        }
    }

    /**
     * 解析 ClockLinkManager 单例。
     *  - 6.9.x: Kotlin object, 静态字段 INSTANCE, 无 getInstance() 方法
     *  - 6.7.x: 静态 getInstance()
     */
    private static Object resolveManager(Class<?> clmCls) {
        Object m = Xp.getStaticObjectField(clmCls, "INSTANCE");
        if (m != null) {
            dev.ohealth.bridge.util.Diag.record("clocklink", "singleton=INSTANCE");
            return m;
        }
        m = Xp.callStaticMethod(clmCls, "getInstance");
        if (m != null) {
            dev.ohealth.bridge.util.Diag.record("clocklink", "singleton=getInstance");
        }
        return m;
    }

    private static Object getFieldValue(Object o, String name) {
        try {
            return Xp.getObjectField(o, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void trySetField(Object o, String name, Object value) {
        try {
            Xp.setObjectField(o, name, value);
        } catch (Throwable ignored) {
        }
    }

    private static boolean hasMethod(Class<?> c, String name, Class<?>... params) {
        try {
            c.getMethod(name, params);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
