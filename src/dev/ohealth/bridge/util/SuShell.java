package dev.ohealth.bridge.util;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * 极简 su 执行器(零依赖)。仅在用户明确开启的 root 功能中使用:
 * 授权勿扰访问 / set_dnd 兜底。
 */
public final class SuShell {

    public static class Result {
        public final boolean success;
        public final String output;

        Result(boolean success, String output) {
            this.success = success;
            this.output = output;
        }
    }

    private SuShell() {
    }

    public static boolean available() {
        return exec("id").success;
    }

    /**
     * su 执行命令(默认 15 秒超时)。
     * @return success = 进程正常退出(exit 0); output = stdout+stderr 合并(截断)
     */
    public static Result exec(String cmd) {
        return exec(cmd, 15L);
    }

    /**
     * su 执行命令, 可指定超时秒数。
     * 支持多行脚本: 逐行写入 stdin, 由 shell 顺序执行。
     */
    public static Result exec(String cmd, long timeoutSec) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes(cmd + "\n");
            os.writeBytes("exit\n");
            os.flush();

            final Process proc = p;
            final StringBuilder out = new StringBuilder();
            Thread eater = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (out.length() < 8000) {
                                out.append(line).append('\n');
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            eater.setDaemon(true);
            eater.start();

            boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                p.destroy();
                return new Result(false, "timeout(" + timeoutSec + "s)\n" + out);
            }
            eater.join(1000);
            boolean ok = p.exitValue() == 0;
            return new Result(ok, out.toString());
        } catch (Throwable t) {
            if (p != null) {
                p.destroy();
            }
            return new Result(false, String.valueOf(t));
        }
    }
}
