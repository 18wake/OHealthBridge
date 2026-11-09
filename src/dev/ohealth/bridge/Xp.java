package dev.ohealth.bridge;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 反射 + Hook 工具层(libxposed API 101/102)。
 * MethodHook.before 返回 PROCEED 之外的值 = 跳过原方法并以该值作为结果;
 * after 返回非 null = 替换原结果。
 */
public final class Xp {

    private static volatile XposedInterface sXp;
    private static volatile ClassLoader sAppCl;

    private Xp() {
    }

    public static void init(XposedInterface xp, ClassLoader appCl) {
        sXp = xp;
        if (appCl != null) {
            sAppCl = appCl;
        }
    }

    public static void setAppClassLoader(ClassLoader cl) {
        if (cl != null) {
            sAppCl = cl;
        }
    }

    public static void log(String tag, String msg) {
        Log.i(tag, msg);
        XposedInterface xp = sXp;
        if (xp != null) {
            try {
                xp.log(Log.INFO, tag, msg);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void log(String tag, String msg, Throwable tr) {
        Log.w(tag, msg, tr);
        XposedInterface xp = sXp;
        if (xp != null) {
            try {
                xp.log(Log.WARN, tag, msg, tr);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 只加载/链接, 绝不触发 &lt;clinit&gt;。
     *
     * v2.14 闪退修复: initialize=true 时, 在 onPackageLoaded 早期加载
     * com.heytap.device.sleep.DoNotDisturbManager 会触发 AppConfigUtils.&lt;clinit&gt;,
     * 此时 OHealth 全局 Context 还是 null → NPE → 类被标记为 erroneous,
     * 之后 App 自己用到就抛 NoClassDefFoundError, 整个进程崩溃 (try/catch 也救不回)。
     * hook 方法本身不需要类初始化; 真正需要初始化的反射(读静态字段/构造/调静态方法)
     * 会在 App 就绪之后发生, 由 JVM 自然按需触发。
     */
    public static Class<?> findClass(String name) throws ClassNotFoundException {
        ClassLoader cl = sAppCl;
        if (cl == null) {
            cl = Xp.class.getClassLoader();
        }
        return Class.forName(name, false, cl);
    }

    public static Method findMethodExact(Class<?> c, String name, Class<?>... params) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(c.getName() + "#" + name);
    }

    public abstract static class MethodHook implements XposedInterface.Hooker {
        public static final Object PROCEED = new Object();

        protected Object before(XposedInterface.Chain chain) throws Throwable {
            return PROCEED;
        }

        protected Object after(XposedInterface.Chain chain, Object result) throws Throwable {
            return result;
        }

        @Override
        public final Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object b = before(chain);
            if (b != PROCEED) {
                return b;
            }
            Object r = chain.proceed();
            return after(chain, r);
        }
    }

    public static boolean hookMethod(Class<?> cls, String name, Class<?>[] params, MethodHook hook) {
        try {
            Method m = findMethodExact(cls, name, params);
            sXp.hook(m).intercept(hook);
            return true;
        } catch (Throwable t) {
            log("OHealthBridge", "hook failed: " + cls.getName() + "#" + name, t);
            return false;
        }
    }

    public static int hookAllMethods(Class<?> cls, String name, MethodHook hook) {
        int n = 0;
        for (Class<?> k = cls; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    try {
                        m.setAccessible(true);
                        sXp.hook(m).intercept(hook);
                        n++;
                    } catch (Throwable t) {
                        log("OHealthBridge", "hookAll failed: " + k.getName() + "#" + name, t);
                    }
                }
            }
        }
        return n;
    }

    /* ---------------- 反射工具 ---------------- */

    public static Object callMethod(Object obj, String name, Object... args) {
        try {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                types[i] = args[i] == null ? Object.class : args[i].getClass();
            }
            Method m = findCompatibleMethod(obj.getClass(), name, types);
            return m.invoke(obj, args);
        } catch (Throwable t) {
            if (t instanceof java.lang.reflect.InvocationTargetException) {
                log("OHealthBridge", "callMethod " + name, ((java.lang.reflect.InvocationTargetException) t).getCause());
            } else {
                log("OHealthBridge", "callMethod " + name, t);
            }
            return null;
        }
    }

    public static Object callStaticMethod(Class<?> cls, String name, Object... args) {
        try {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                types[i] = args[i] == null ? Object.class : args[i].getClass();
            }
            Method m = findCompatibleMethod(cls, name, types);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Throwable t) {
            log("OHealthBridge", "callStaticMethod " + cls.getName() + "#" + name, t);
            return null;
        }
    }

    private static Method findCompatibleMethod(Class<?> cls, String name, Class<?>[] types) throws NoSuchMethodException {
        Method best = null;
        for (Class<?> k = cls; k != null && best == null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != types.length) {
                    continue;
                }
                boolean ok = true;
                for (int i = 0; i < pt.length; i++) {
                    if (types[i] != Object.class && !pt[i].isAssignableFrom(types[i])
                            && !box(pt[i]).isAssignableFrom(box(types[i]))) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    best = m;
                    break;
                }
            }
        }
        if (best == null) {
            throw new NoSuchMethodException(cls.getName() + "#" + name);
        }
        best.setAccessible(true);
        return best;
    }

    private static Class<?> box(Class<?> c) {
        if (!c.isPrimitive()) {
            return c;
        }
        if (c == boolean.class) return Boolean.class;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == double.class) return Double.class;
        if (c == float.class) return Float.class;
        if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class;
        return c;
    }

    public static Object getObjectField(Object obj, String name) {
        try {
            Field f = findField(obj.getClass(), name);
            return f.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void setObjectField(Object obj, String name, Object value) {
        try {
            Field f = findField(obj.getClass(), name);
            f.set(obj, value);
        } catch (Throwable ignored) {
        }
    }

    public static Object getStaticObjectField(Class<?> cls, String name) {
        try {
            Field f = findField(cls, name);
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> k = cls; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(cls.getName() + "#" + name);
    }

    public static Object newInstance(Class<?> cls, Object... args) {
        try {
            for (Constructor<?> c : cls.getDeclaredConstructors()) {
                if (c.getParameterTypes().length == args.length) {
                    c.setAccessible(true);
                    return c.newInstance(args);
                }
            }
            throw new NoSuchMethodException(cls.getName() + " ctor(" + args.length + ")");
        } catch (Throwable t) {
            log("OHealthBridge", "newInstance " + cls.getName(), t);
            return null;
        }
    }

    public static boolean hasMethod(Class<?> c, String name, Class<?>... params) {
        try {
            findMethodExact(c, name, params);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static List<Method> declaredMethods(Class<?> cls, String name) {
        List<Method> out = new ArrayList<>();
        for (Class<?> k = cls; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    out.add(m);
                }
            }
        }
        return out;
    }
}
