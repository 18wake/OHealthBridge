package dev.ohealth.bridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * 模块配置(API 101/102: 模块无自有 Context)。
 * UI 通过 root 把配置写到目标 App 私有 prefs 文件 ohealth_bridge_cfg.xml,
 * hook 侧(同 uid)用 java.io 直读; 读不到时全部走默认值(全开)。
 */
public class Config {

    public static final String MODULE_PKG = "dev.ohealth.bridge";
    public static final String TARGET_PKG = HookEntry.TARGET_PKG;
    public static final String CFG_FILE =
            "/data/data/" + TARGET_PKG + "/shared_prefs/ohealth_bridge_cfg.xml";
    public static final String DEFAULT_CLOCK_PKGS =
            "com.android.deskclock,com.hihonor.deskclock,com.google.android.deskclock,com.sec.android.app.clockpackage";

    public final boolean enabled;
    public final boolean dndSync;
    public final boolean alarmSync;
    /** true: 只按 notification.category==alarm 识别, 不校验包名(最通用) */
    public final boolean anyPackage;
    /** 勿扰写权限未授予时, 用 root 执行 cmd notification set_dnd 兜底 */
    public final boolean rootFallback;
    /** true: 严格区分"即将响铃提醒"与"真正响铃"(默认开); 特殊 ROM 可置 false 退回宽松匹配 */
    public final boolean strictRinging;
    /** true: 在 :transport 等非主进程也安装 DND 类 Hook (默认 false, 仅主进程) */
    public final boolean dndAllProcesses;
    /** 响铃通知来源包名白名单(anyPackage=false 时生效), 逗号分隔 */
    public final java.util.List<String> clockPackages;
    /** auto: 非OPPO系自动启用; force: 任何设备强制启用 */
    public final String brandMode;

    private static volatile Config instance;

    private Config(boolean enabled, boolean dnd, boolean alarm, boolean anyPkg, boolean root,
                   boolean strict, boolean dndAllProc, String mode, java.util.List<String> pkgs) {
        this.enabled = enabled;
        this.dndSync = dnd;
        this.alarmSync = alarm;
        this.anyPackage = anyPkg;
        this.rootFallback = root;
        this.strictRinging = strict;
        this.dndAllProcesses = dndAllProc;
        this.brandMode = mode;
        this.clockPackages = pkgs;
    }

    private static java.util.List<String> parsePkgs(String csv) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (csv != null) {
            for (String s : csv.split(",")) {
                s = s.trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    public static Config get() {
        Config c = instance;
        if (c == null) {
            synchronized (Config.class) {
                c = instance;
                if (c == null) {
                    instance = c = load();
                }
            }
        }
        return c;
    }

    private static Config load() {
        boolean enabled = true, dnd = true, alarm = true, anyPkg = true, root = true,
                strict = true, dndAllProc = false;
        String mode = "auto", pkgs = DEFAULT_CLOCK_PKGS;

        // 目标 App 私有 prefs 文件(UI root 写入并 chown; hook 侧同 uid 直读)
        try {
            File f = new File(CFG_FILE);
            if (f.canRead()) {
                StringBuilder sb = new StringBuilder();
                BufferedReader r = new BufferedReader(new FileReader(f));
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                r.close();
                String xml = sb.toString();
                enabled = parseBool(xml, "enabled", enabled);
                dnd = parseBool(xml, "dnd_sync", dnd);
                alarm = parseBool(xml, "alarm_sync", alarm);
                anyPkg = parseBool(xml, "any_package", anyPkg);
                root = parseBool(xml, "root_fallback", root);
                strict = parseBool(xml, "strict_ringing", strict);
                dndAllProc = parseBool(xml, "dnd_all_processes", dndAllProc);
                mode = parseString(xml, "brand_mode", mode);
                pkgs = parseString(xml, "clock_pkgs", pkgs);
            }
        } catch (Throwable ignored) {
        }

        return new Config(enabled, dnd, alarm, anyPkg, root, strict, dndAllProc,
                mode, parsePkgs(pkgs));
    }

    /** SharedPreferences XML: boolean/int 用 value="..", string 用元素文本 */
    private static boolean parseBool(String xml, String name, boolean def) {
        String v = attrValue(xml, name, "value");
        if (v == null) {
            v = elementText(xml, name);
        }
        return v == null ? def : Boolean.parseBoolean(v);
    }

    private static String parseString(String xml, String name, String def) {
        String v = elementText(xml, name);
        if (v == null) {
            v = attrValue(xml, name, "value");
        }
        return v == null ? def : v;
    }

    private static String attrValue(String xml, String name, String attr) {
        int i = xml.indexOf("name=\"" + name + "\"");
        if (i < 0) {
            return null;
        }
        int v = xml.indexOf(attr + "=\"", i);
        if (v < 0) {
            return null;
        }
        int s = v + attr.length() + 2;
        int e = xml.indexOf('"', s);
        return e > 0 ? xml.substring(s, e) : null;
    }

    private static String elementText(String xml, String name) {
        int i = xml.indexOf("name=\"" + name + "\"");
        if (i < 0) {
            return null;
        }
        int gt = xml.indexOf('>', i);
        int lt = xml.indexOf("</string>", gt);
        if (gt > 0 && lt > gt) {
            return xml.substring(gt + 1, lt);
        }
        return null;
    }

    /** 供 hook 侧在 package load 时刷新 */
    public static void reload() {
        instance = load();
    }
}
