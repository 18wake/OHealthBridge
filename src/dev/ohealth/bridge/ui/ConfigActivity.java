package dev.ohealth.bridge.ui;

import android.app.Activity;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.ohealth.bridge.Config;
import dev.ohealth.bridge.util.SuShell;

/**
 * 模块配置界面(HyperOS 风格, 纯代码布局, 无 lambda)。
 * API 101/102 下模块无自有 Context —— 配置经 root 写入目标 App 侧。
 */
public class ConfigActivity extends Activity {

    private static final String HEALTH_PKG = "com.heytap.health";
    private static final String HEALTH_DIAG_XML =
            "/data/data/" + HEALTH_PKG + "/shared_prefs/ohealth_bridge.xml";
    private static final String HEALTH_CFG_XML =
            "/data/data/" + HEALTH_PKG + "/shared_prefs/ohealth_bridge_cfg.xml";

    /** UI 本地镜像(供界面回显) */
    private SharedPreferences uiPrefs;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 隐藏系统窗口标题, 避免与页面内标题重复
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        uiPrefs = getSharedPreferences("ui_mirror", MODE_PRIVATE);

        int pageBg = Miuix.pageBg(this);
        Window w = getWindow();
        w.setStatusBarColor(pageBg);
        w.setNavigationBarColor(pageBg);
        int flags = w.getDecorView().getSystemUiVisibility();
        if (!Miuix.dark(this)) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        w.getDecorView().setSystemUiVisibility(flags);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(pageBg);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Miuix.dp(this, 16);
        root.setPadding(pad, Miuix.dp(this, 20), pad, Miuix.dp(this, 28));
        scroll.addView(root);
        setContentView(scroll);

        /* ---------- 头部 ---------- */
        TextView title = new TextView(this);
        title.setText("OHealthBridge");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Miuix.textMain(this));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("在非 OPPO 手机上使用 OPPO 手表的勿扰与闹钟同步");
        sub.setTextSize(14);
        sub.setTextColor(Miuix.textSub(this));
        sub.setPadding(0, Miuix.dp(this, 4), 0, 0);
        root.addView(sub);

        /* ---------- 功能 ---------- */
        root.addView(Miuix.sectionTitle(this, "功能"));
        LinearLayout cardFn = Miuix.card(this);
        root.addView(cardFn);

        final boolean[] vEnabled = {uiPrefs.getBoolean("enabled", true)};
        cardFn.addView(Miuix.switchRow(this, "启用模块", "勿扰与闹钟同步的总开关",
                vEnabled[0], new Miuix.SwitchRowListener() {
                    public void onChanged(boolean checked) {
                        vEnabled[0] = checked;
                        uiPrefs.edit().putBoolean("enabled", checked).apply();
                    }
                }));
        Miuix.addDivider(this, cardFn);

        final boolean[] vDnd = {uiPrefs.getBoolean("dnd_sync", true)};
        cardFn.addView(Miuix.switchRow(this, "勿扰同步", "手机与手表的勿扰状态互相跟随",
                vDnd[0], new Miuix.SwitchRowListener() {
                    public void onChanged(boolean checked) {
                        vDnd[0] = checked;
                        uiPrefs.edit().putBoolean("dnd_sync", checked).apply();
                    }
                }));
        Miuix.addDivider(this, cardFn);

        final boolean[] vAlarm = {uiPrefs.getBoolean("alarm_sync", true)};
        cardFn.addView(Miuix.switchRow(this, "闹钟同步", "闹钟响铃转发到手表，支持在手表上关闭或贪睡",
                vAlarm[0], new Miuix.SwitchRowListener() {
                    public void onChanged(boolean checked) {
                        vAlarm[0] = checked;
                        uiPrefs.edit().putBoolean("alarm_sync", checked).apply();
                    }
                }));

        /* ---------- 闹钟识别 ---------- */
        root.addView(Miuix.sectionTitle(this, "闹钟识别"));
        LinearLayout cardAlarm = Miuix.card(this);
        root.addView(cardAlarm);

        final boolean[] vAny = {uiPrefs.getBoolean("any_package", true)};
        cardAlarm.addView(Miuix.switchRow(this, "按通知类别识别", "识别所有应用的闹钟通知，兼容性最好",
                vAny[0], new Miuix.SwitchRowListener() {
                    public void onChanged(boolean checked) {
                        vAny[0] = checked;
                        uiPrefs.edit().putBoolean("any_package", checked).apply();
                    }
                }));
        Miuix.addDivider(this, cardAlarm);

        LinearLayout pkgRow = Miuix.row(this);
        pkgRow.setOrientation(LinearLayout.VERTICAL);
        TextView pkgLabel = new TextView(this);
        pkgLabel.setText("应用白名单");
        pkgLabel.setTextSize(16);
        pkgLabel.setTextColor(Miuix.textMain(this));
        pkgRow.addView(pkgLabel);
        TextView pkgHint = new TextView(this);
        pkgHint.setText("关闭上一项后生效，多个包名用逗号分隔");
        pkgHint.setTextSize(13);
        pkgHint.setTextColor(Miuix.textSub(this));
        pkgHint.setPadding(0, Miuix.dp(this, 2), 0, Miuix.dp(this, 8));
        pkgRow.addView(pkgHint);
        final EditText editPkgs = Miuix.edit(this,
                uiPrefs.getString("clock_pkgs", Config.DEFAULT_CLOCK_PKGS));
        pkgRow.addView(editPkgs);
        cardAlarm.addView(pkgRow);

        /* ---------- 勿扰权限 ---------- */
        root.addView(Miuix.sectionTitle(this, "勿扰权限"));
        LinearLayout cardPerm = Miuix.card(this);
        root.addView(cardPerm);

        final boolean[] vRoot = {uiPrefs.getBoolean("root_fallback", true)};
        cardPerm.addView(Miuix.switchRow(this, "Root 兜底",
                "未获授权时通过 Root 命令写入勿扰状态",
                vRoot[0], new Miuix.SwitchRowListener() {
                    public void onChanged(boolean checked) {
                        vRoot[0] = checked;
                        uiPrefs.edit().putBoolean("root_fallback", checked).apply();
                    }
                }));

        Button btnGrant = Miuix.button(this, "授权勿扰访问");
        Button btnTest = Miuix.button(this, "测试开关");
        cardPerm.addView(Miuix.buttonPair(this, btnGrant, btnTest));

        /* ---------- 状态 ---------- */
        root.addView(Miuix.sectionTitle(this, "状态"));
        LinearLayout cardStat = Miuix.card(this);
        root.addView(cardStat);

        status = new TextView(this);
        status.setTextSize(12);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextColor(Miuix.textMain(this));
        status.setText("暂无数据。模块初次使用请先「保存并生效」，再重启 OPPO 健康。");
        int sh = Miuix.dp(this, 16);
        status.setPadding(sh, Miuix.dp(this, 14), sh, Miuix.dp(this, 14));
        cardStat.addView(status);

        Button btnDiag = Miuix.button(this, "运行状态");
        Button btnLog = Miuix.button(this, "框架日志");
        cardStat.addView(Miuix.buttonPair(this, btnDiag, btnLog));

        /* ---------- 维护 ---------- */
        root.addView(Miuix.sectionTitle(this, "维护"));
        LinearLayout cardTool = Miuix.card(this);
        root.addView(cardTool);

        cardTool.addView(Miuix.noteRow(this,
                "模块未生效时，按顺序尝试以下操作。"));

        Button btnLsp = Miuix.button(this, "检查作用域");
        Button btnLogs = Miuix.button(this, "完整日志");
        cardTool.addView(Miuix.buttonPair(this, btnLsp, btnLogs));

        Button btnDb = Miuix.button(this, "查询作用域数据库");
        Button btnRestart = Miuix.button(this, "重启健康");
        cardTool.addView(Miuix.buttonPair(this, btnDb, btnRestart));

        /* ---------- 保存 ---------- */
        LinearLayout saveWrap = new LinearLayout(this);
        saveWrap.setOrientation(LinearLayout.VERTICAL);
        saveWrap.setPadding(0, Miuix.dp(this, 20), 0, 0);
        root.addView(saveWrap);
        Button save = Miuix.primaryButton(this, "保存并生效");
        saveWrap.addView(save);

        TextView foot = new TextView(this);
        foot.setText("保存后请重启 OPPO 健康，并断开重连一次手表蓝牙。\n日志：logcat -s OHealthBridge");
        foot.setTextSize(12);
        foot.setTextColor(Miuix.textSub(this));
        foot.setPadding(Miuix.dp(this, 4), Miuix.dp(this, 14), Miuix.dp(this, 4), 0);
        saveWrap.addView(foot);

        /* ---------- 事件 ---------- */
        btnGrant.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                autoGrantAndCheck();
            }
        });

        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runAsync("cmd notification set_dnd priority && sleep 3 && cmd notification set_dnd off",
                        "勿扰应先开启 3 秒再自动关闭");
            }
        });

        btnDiag.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDiag();
            }
        });

        btnLsp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runAsync("echo '== db 注册状态(出现次数) =='; "
                        + "grep -ahoE '(dev.ohealth.bridge|com.heytap.health)' /data/adb/lspd/config/modules_config.db /data/adb/lspd/config/modules_config.db-wal 2>/dev/null | sort | uniq -c; "
                        + "echo '== modules.log 中模块记录 =='; "
                        + "grep -ai ohealth /data/adb/lspd/log/modules_*.log 2>/dev/null | tail -20; "
                        + "echo '== verbose.log 中模块记录 =='; "
                        + "grep -ai ohealth /data/adb/lspd/log/verbose_*.log 2>/dev/null | tail -20; "
                        + "true",
                        "出现 dev.ohealth.bridge 即已注册，日志含加载结果");
            }
        });

        btnDb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                queryLspDb();
            }
        });

        btnLogs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runAsync("echo '== modules.log 最后60行 =='; tail -60 /data/adb/lspd/log/modules_*.log 2>/dev/null; true",
                        "框架模块日志，可复制反馈");
            }
        });

        btnRestart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartHealth();
            }
        });

        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveConfigToHealth(vEnabled[0], vDnd[0], vAlarm[0],
                        vAny[0], vRoot[0], editPkgs.getText().toString().trim());
            }
        });
    }

    /* ---------------- 配置: root 写入目标 App prefs ---------------- */

    private void saveConfigToHealth(boolean enabled, boolean dnd, boolean alarm,
                                    boolean anyPkg, boolean root, String pkgs) {
        // 本地镜像
        uiPrefs.edit()
                .putBoolean("enabled", enabled)
                .putBoolean("dnd_sync", dnd)
                .putBoolean("alarm_sync", alarm)
                .putBoolean("any_package", anyPkg)
                .putBoolean("root_fallback", root)
                .putString("clock_pkgs", pkgs)
                .apply();

        // 目标 prefs XML（SharedPreferences 格式）
        StringBuilder x = new StringBuilder();
        x.append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n");
        x.append("    <boolean name=\"enabled\" value=\"").append(enabled).append("\" />\n");
        x.append("    <boolean name=\"dnd_sync\" value=\"").append(dnd).append("\" />\n");
        x.append("    <boolean name=\"alarm_sync\" value=\"").append(alarm).append("\" />\n");
        x.append("    <boolean name=\"any_package\" value=\"").append(anyPkg).append("\" />\n");
        x.append("    <boolean name=\"root_fallback\" value=\"").append(root).append("\" />\n");
        x.append("    <string name=\"brand_mode\">auto</string>\n");
        x.append("    <string name=\"clock_pkgs\">").append(pkgs.replace("&", "&amp;").replace("<", "&lt;")).append("</string>\n");
        x.append("</map>\n");
        final String xml = x.toString();

        post("正在写入配置 …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                // ① 写入文件 + 修权限(heredoc 结束符必须独立成行)
                String script =
                        "mkdir -p /data/data/" + HEALTH_PKG + "/shared_prefs\n"
                                + "cat > '" + HEALTH_CFG_XML + "' << 'OHEOF'\n"
                                + xml
                                + "OHEOF\n"
                                + "chown $(stat -c %u:%g /data/data/" + HEALTH_PKG + ") '"
                                + HEALTH_CFG_XML + "'\n"
                                + "chmod 660 '" + HEALTH_CFG_XML + "'\n"
                                + "restorecon '" + HEALTH_CFG_XML + "' 2>/dev/null; true";
                SuShell.Result r = SuShell.exec(script, 25L);
                if (!r.success) {
                    post("写入失败，请确认已 Root：\n" + r.output);
                    return;
                }
                // ② 重启健康 App(慢命令单独执行, 不影响写文件结果判定)
                SuShell.Result r2 = SuShell.exec("am force-stop " + HEALTH_PKG, 20L);
                post(r2.success ? "配置已写入，重新打开 OPPO 健康后生效"
                        : "配置已写入。重启健康失败，请手动重新打开 OPPO 健康");
            }
        }).start();
    }

    /* ---------------- 诊断: root 回读目标侧 ---------------- */
    private void restartHealth() {
        post("正在重启 OPPO 健康 …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                SuShell.exec("am force-stop " + HEALTH_PKG);
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException ignored) {
                }
                SuShell.exec("monkey -p " + HEALTH_PKG + " -c android.intent.category.LAUNCHER 1");
                post("已重启。请断开重连一次手表蓝牙，再分别测试勿扰与闹钟。");
            }
        }).start();
    }

    private void queryLspDb() {
        post("正在读取 LSPosed 配置库 …");
        final String dst = getFilesDir().getAbsolutePath() + "/mc.db";
        new Thread(new Runnable() {
            @Override
            public void run() {
                SuShell.exec("rm -f " + dst + " " + dst + "-wal " + dst + "-shm"
                        + " && cp /data/adb/lspd/config/modules_config.db " + dst
                        + " && cp /data/adb/lspd/config/modules_config.db-wal " + dst + "-wal 2>/dev/null"
                        + " ; chown $(stat -c %u /data/data/dev.ohealth.bridge):$(stat -c %g /data/data/dev.ohealth.bridge) "
                        + dst + " " + dst + "-wal 2>/dev/null ; chmod 600 " + dst + " ; true");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        dumpDb(dst);
                    }
                });
            }
        }).start();
    }

    private void showDiag() {
        post("正在读取运行状态 …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                SuShell.Result r = SuShell.exec("cat " + HEALTH_DIAG_XML);
                final String out = (r.success && r.output != null && r.output.contains("module_state"))
                        ? r.output.replace("><", ">" + (char) 10 + "<")
                        : null;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (out == null) {
                            status.setText("暂无数据：模块尚未写入状态。\n请先「保存并生效」，再重启 OPPO 健康。");
                        } else {
                            status.setText("—— 状态原文 ——" + (char) 10 + out);
                        }
                    }
                });
            }
        }).start();
    }

    private void dumpDb(String path) {
        StringBuilder sb = new StringBuilder();
        try {
            SQLiteDatabase d = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE);
            sb.append("== modules（已识别模块）==\012");
            appendRows(sb, d, "SELECT module_pkg_name, apk_path FROM modules");
            sb.append("\012== modules_state（启用状态，1=开）==\012");
            appendRows(sb, d, "SELECT module_pkg_name, user_id, enabled, scope_request_blocked FROM modules_state");
            sb.append("\012== scope（作用域，应含 com.heytap.health）==\012");
            appendRows(sb, d, "SELECT module_pkg_name, app_pkg_name, user_id FROM scope");
            d.close();
        } catch (Throwable t) {
            sb.append("数据库打开失败: ").append(t);
        }
        final String out = sb.toString();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                status.setText(out);
            }
        });
    }

    private void appendRows(StringBuilder sb, SQLiteDatabase d, String sql) {
        try {
            Cursor c = d.rawQuery(sql, null);
            while (c.moveToNext()) {
                StringBuilder row = new StringBuilder();
                for (int i = 0; i < c.getColumnCount(); i++) {
                    if (i > 0) {
                        row.append(" | ");
                    }
                    row.append(c.getString(i));
                }
                sb.append(row).append('\012');
            }
            c.close();
        } catch (Throwable t) {
            sb.append("query failed: ").append(t);
        }
    }

    private void autoGrantAndCheck() {
        post("正在授权勿扰访问 …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                SuShell.Result r1 = SuShell.exec("cmd notification allow_dnd " + HEALTH_PKG);
                if (!r1.success) {
                    post("授权失败（Root 不可用或被系统拒绝）\n" + r1.output
                            + "\n可开启 Root 兜底开关使用备用通道。");
                    return;
                }
                post("授权命令已下发，正在重启 OPPO 健康以刷新状态 …");
                SuShell.exec("am force-stop " + HEALTH_PKG);
                SuShell.exec("sleep 1 && monkey -p " + HEALTH_PKG + " -c android.intent.category.LAUNCHER 1");
                pollPolicyAccess(15);
            }
        }).start();
    }

    private void pollPolicyAccess(final int waitSeconds) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
                String access = null;
                while (System.currentTimeMillis() < deadline) {
                    SuShell.Result r = SuShell.exec("cat " + HEALTH_DIAG_XML);
                    if (r.success && r.output != null && r.output.contains("policy_access")) {
                        access = extractAttr(r.output, "policy_access");
                        if (access != null) {
                            break;
                        }
                    }
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                    }
                }
                if ("true".equals(access)) {
                    post("勿扰访问权限已授权。");
                } else {
                    post("授权状态: " + (access == null ? "未读取到（OPPO 健康未上报）" : access)
                            + "\n开启 Root 兜底开关即可正常使用勿扰写入。");
                }
            }
        }).start();
    }

    private void runAsync(String cmd, String note) {
        status.setText("执行中: " + cmd.substring(0, Math.min(50, cmd.length())) + "…");
        final String c = cmd;
        final String n = note;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final SuShell.Result r = SuShell.exec(c);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText((r.success ? "完成" : "失败") + "\n" + n + "\n----\n" + r.output);
                    }
                });
            }
        }).start();
    }

    private void post(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                status.setText(s);
            }
        });
    }

    /* ---------------- 工具 ---------------- */

    private static String extractAttr(String xml, String name) {
        int i = xml.indexOf("name=\"" + name + "\"");
        if (i < 0) {
            return null;
        }
        int v = xml.indexOf("value=\"", i);
        if (v < 0) {
            return null;
        }
        int e = xml.indexOf('"', v + 7);
        return e > 0 ? xml.substring(v + 7, e) : null;
    }
}
