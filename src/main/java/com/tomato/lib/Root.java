package com.tomato.lib;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** 通过 su 执行脚本，返回 stdout / stderr / 退出码。 */
public final class Root {

    public static final class Result {
        public final String out;
        public final String err;
        public final int code;
        public final boolean timeout;

        Result(String out, String err, int code, boolean timeout) {
            this.out = out;
            this.err = err;
            this.code = code;
            this.timeout = timeout;
        }
    }

    private Root() {}

    /* ---------------- 执行策略 ----------------
     * 不同 root 方案对 mount namespace / SELinux 的处理不一样：
     * 有的 su -c 在 App 自己的命名空间里跑，看不到 /data/data 下别人家的目录；
     * 有的要 --mount-master 才切到全局；Magisk 常用 su 0 sh -c。
     * 这里准备一组策略，逐个试，选第一个能看见目标目录的。
     */

    public static final String[] STRATEGY_NAME = {
            "su -c",
            "su --mount-master -c",
            "su -M -c",
            "su 0 sh -c",
            "nsenter -m -p",
            "nsenter -m",
            "su -mm -c"
    };

    /** 目标目录可能的几个位置（多用户 / 直连 / 隔离）。 */
    public static final String[] ROOT_CANDIDATE = {
            "/data/data/com.dragon.read/files",
            "/data/user/0/com.dragon.read/files",
            "/data/user_de/0/com.dragon.read/files"
    };

    private static String rootPath = ROOT_CANDIDATE[0];
    public static String root() { return rootPath; }

    private static int chosen = -1;
    private static String chosenDetail = "";

    public static int chosenStrategy() { return chosen; }

    public static String chosenDetail() { return chosenDetail; }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String[] cmdFor(int idx, String script) {
        switch (idx) {
            case 1: return new String[]{"su", "--mount-master", "-c", script};
            case 2: return new String[]{"su", "-M", "-c", script};
            case 3: return new String[]{"su", "0", "sh", "-c", script};
            case 4: return new String[]{"su", "-c",
                    "nsenter -t 1 -m -p -- /system/bin/sh -c " + quote(script)};
            case 5: return new String[]{"su", "-c",
                    "nsenter -t 1 -m -- /system/bin/sh -c " + quote(script)};
            case 6: return new String[]{"su", "-mm", "-c", script};
            case 0:
            default: return new String[]{"su", "-c", script};
        }
    }

    /** 用指定策略跑脚本。 */
    public static Result run(int idx, String script, int timeoutMs) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(cmdFor(idx, script));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread t1 = pump(p.getInputStream(), out);
            Thread t2 = pump(p.getErrorStream(), err);

            boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!done) {
                try { p.destroy(); } catch (Throwable ignored) {}
                return new Result(out.toString("UTF-8"), err.toString("UTF-8"), -1, true);
            }
            t1.join(1200);
            t2.join(800);
            int code;
            try { code = p.exitValue(); } catch (Throwable t) { code = -1; }
            return new Result(out.toString("UTF-8"), err.toString("UTF-8"), code, false);
        } catch (Throwable t) {
            return new Result("", String.valueOf(t), -2, false);
        } finally {
            if (p != null) { try { p.destroy(); } catch (Throwable ignored) {} }
        }
    }

    /** 兼容旧调用：用已选策略（未探测时用 0）。 */
    public static Result run(String script, int timeoutMs) {
        return run(chosen >= 0 ? chosen : 0, script, timeoutMs);
    }

    private static String probeScript(String root) {
        return "echo \"S|$(id -u 2>/dev/null)\"\n" +
               "echo \"C|$(cat /proc/self/attr/current 2>/dev/null)\"\n" +
               "echo \"L|$(ls -ld " + root + " 2>&1 | head -2 | tr '\\n' ' ')\"\n" +
               "echo \"N|$(ls -1 " + root + " 2>/dev/null | head -3 | tr '\\n' ',')\"\n";
    }

    /** 一次全量探测的预算。7 策略 × 3 候选根 × 12s 最坏要 4 分钟，不可接受。 */
    private static final long PROBE_BUDGET_MS = 60000;

    /** 上次探测是否命中「root + 目录可见」；命中后下次先用 1 次 su 做廉价复核。 */
    private static volatile boolean cachedHit = false;

    private static boolean isRootOut(String out) {
        return "0".equals(field(out, "S|").trim());
    }

    private static boolean isVisibleOut(String out) {
        return field(out, "L|").contains("drwx");
    }

    private static String ctxOf(String out) {
        String c = field(out, "C|").trim();
        return c.length() == 0 ? "-" : c;
    }

    /**
     * 依次尝试各策略，挑一个「root + 目录可见」的。
     * 返回 {策略序号, 说明, 逐条回执}，同时把结果缓存。
     *
     * v1.3.0 起：
     *  1) 上次命中过的组合先做一次 8s 复核，通过就直接复用（正常扫描只花 1 次 su）；
     *  2) 整体探测有 60s 预算，超时即按「不可见」收尾，不再把重扫拖成几分钟；
     *  3) 删掉原先「再跑 7 次 id -u」的兜底循环——探测脚本本身已经带回 uid，重复弹授权框没意义。
     */
    public static String[] probe() {
        StringBuilder log = new StringBuilder();

        if (cachedHit && chosen >= 0) {
            Result r = run(chosen, probeScript(rootPath), 8000);
            String o = r.out == null ? "" : r.out;
            if (isRootOut(o) && isVisibleOut(o)) {
                String detail = "uid=0 ctx=" + ctxOf(o) + " 模式=" + STRATEGY_NAME[chosen] + " 根=" + rootPath;
                chosenDetail = detail;
                log.append("[复核] ").append(STRATEGY_NAME[chosen]).append(" → uid=0 目录可见（沿用上次组合）");
                return new String[]{String.valueOf(chosen), detail, log.toString()};
            }
            cachedHit = false;
        }

        long t0 = System.currentTimeMillis();
        int best = -1, bestRootIdx = 0, firstRoot = -1;
        String detail = "";
        boolean budgetOut = false;

        for (int idx = 0; idx < STRATEGY_NAME.length && best < 0; idx++) {
            if (System.currentTimeMillis() - t0 > PROBE_BUDGET_MS) { budgetOut = true; break; }
            String summary = "";
            for (int ri = 0; ri < ROOT_CANDIDATE.length; ri++) {
                if (System.currentTimeMillis() - t0 > PROBE_BUDGET_MS) { budgetOut = true; break; }
                String root = ROOT_CANDIDATE[ri];
                Result r = run(idx, probeScript(root), 12000);
                String o = r.out == null ? "" : r.out;
                boolean root0 = isRootOut(o);
                boolean visible = isVisibleOut(o);
                if (root0 && summary.length() == 0) {
                    summary = "uid=0 ctx=" + ctxOf(o);
                    if (firstRoot < 0) firstRoot = idx;
                }
                if (root0 && visible) {
                    best = idx;
                    bestRootIdx = ri;
                    detail = "uid=0 ctx=" + ctxOf(o) + " 模式=" + STRATEGY_NAME[idx] + " 根=" + root;
                    break;
                }
            }
            log.append('[').append(idx).append("] ").append(STRATEGY_NAME[idx]).append(" → ")
               .append(summary.length() == 0 ? "未拿到 root" : summary)
               .append(best == idx ? " 目录可见" : " 目录不可见").append('\n');
        }

        if (budgetOut) log.append("探测超时（").append(PROBE_BUDGET_MS / 1000).append("s），按目录不可见处理\n");

        if (best < 0) {
            // 没找到可见组合：有 root 就沿用第一个拿到 root 的策略，否则退回 0 号
            best = firstRoot < 0 ? 0 : firstRoot;
            bestRootIdx = 0;
            detail = firstRoot < 0
                    ? "未拿到 root：su 不可用或授权被拒"
                    : "root 可用但目标目录不可见，暂用 " + STRATEGY_NAME[firstRoot];
        }

        cachedHit = firstRoot >= 0 && detail.startsWith("uid=0");
        chosen = best;
        chosenDetail = detail;
        rootPath = ROOT_CANDIDATE[bestRootIdx];
        return new String[]{String.valueOf(chosen), chosenDetail, log.toString().trim()};
    }

    private static String field(String out, String key) {
        for (String line : out.split("\n")) {
            if (line.startsWith(key)) return line.substring(key.length());
        }
        return "";
    }

    private static String cut(String s) {
        s = s.replace('\n', ' ').trim();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    private static Thread pump(final InputStream in, final ByteArrayOutputStream sink) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                byte[] buf = new byte[8192];
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) sink.write(buf, 0, n);
                } catch (Throwable ignored) {
                } finally {
                    try { in.close(); } catch (Throwable ignored) {}
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** 找可执行的 su，返回绝对路径；找不到返回空串。 */
    public static String which(String bin) {
        String[] cand = new String[]{
                "/system/bin/" + bin, "/system/xbin/" + bin, "/sbin/" + bin,
                "/su/bin/" + bin, "/debug_ksu/bin/" + bin, "/system/sbin/" + bin
        };
        for (String c : cand) {
            try {
                java.io.File f = new java.io.File(c);
                if (f.exists() && f.canExecute()) return c;
            } catch (Throwable ignored) {}
        }
        return "";
    }

    /** 只接受由数字/字母/下划线/短横/点/斜杠组成的绝对路径，防止命令注入。 */
    public static boolean safePath(String p) {
        if (p == null || p.length() < 8 || p.length() > 260) return false;
        if (!(p.startsWith("/data/data/com.dragon.read/files/")
                || p.startsWith("/data/user/")
                || p.startsWith("/data/user_de/"))) return false;
        if (!p.contains("/com.dragon.read/files/")) return false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '/' || c == '_' || c == '-'
                    || c == '.';
            if (!ok) return false;
        }
        return !p.contains("..");
    }
}
