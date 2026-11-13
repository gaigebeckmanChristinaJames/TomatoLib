package com.tomato.lib;

import android.util.Base64;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 扫描番茄（com.dragon.read）私有目录里的离线章节缓存。
 *
 * 设计要点：不认目录前缀名，只认“目录里有没有章节文件”。
 * 一个书目录 = 若干个小文件，文件头 600 字节里含 "book_name"/"chapter_id"。
 * 这样番茄改前缀、加账号、加中间层都不会漏。
 */
public final class Scanner {

    public static final String DATA_ROOT = "/data/data/com.dragon.read/files";
    private static final Pattern BOOK_NAME = Pattern.compile("\"book_name\":\"([^\"]*)\"");
    private static final Pattern CHAPTER_ID = Pattern.compile("\"chapter_id\":\"?([0-9]+)");

    private Scanner() {}

    /* ---------------- 扫描脚本 ---------------- */

    private static String script() {
        return
            "export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH\n" +
            "D=" + Root.root() + "\n" +
            "UID_LINE=$($(command -v id) -u 2>/dev/null || echo -)\n" +
            "echo \"UID|$UID_LINE\"\n" +
            "if [ ! -d \"$D\" ]; then echo \"NODIR|$D\"; exit 0; fi\n" +
            "echo \"TOPLIST|$(ls -1 \"$D\" 2>&1 | tr '\\n' ',' )\"\n" +
            // 判断一个目录是不是书目录：至少 2 个文件，且首个文件头里有 book_name
            "is_book() {\n" +
            "  b=\"$1\"\n" +
            "  [ -d \"$b\" ] || return 1\n" +
            "  c=$(ls -1 \"$b\" 2>/dev/null | wc -l)\n" +
            "  [ \"$c\" -ge 2 ] || return 1\n" +
            "  f=$(ls -1 \"$b\" 2>/dev/null | head -n 1)\n" +
            "  [ -n \"$f\" ] || return 1\n" +
            "  head -c 600 \"$b/$f\" 2>/dev/null | grep -q 'book_name' || return 1\n" +
            "  return 0\n" +
            "}\n" +
            "emit() {\n" +
            "  acc=\"$1\"; d=\"$2\"\n" +
            "  c=$(ls -1 \"$d\" 2>/dev/null | wc -l)\n" +
            "  kb=$(du -sk \"$d\" 2>/dev/null | awk '{print $1}')\n" +
            "  mt=$(stat -c %Y \"$d\" 2>/dev/null)\n" +
            "  f=$(ls -1 \"$d\" 2>/dev/null | head -n 1)\n" +
            "  h=$(head -c 600 \"$d/$f\" 2>/dev/null | base64 | tr -d '\\n')\n" +
            "  echo \"BK|$acc|$d|$c|${kb:-0}|${mt:-0}|$h\"\n" +
            "}\n" +
            // 1) 账号层：files/<数字账号>/<书目录>
            "for a in \"$D\"/*; do\n" +
            "  [ -d \"$a\" ] || continue\n" +
            "  an=${a##*/}\n" +
            "  case \"$an\" in prefix_*|mmkv|keva|databases|cache|files|logs|plugins|shared_prefs) continue ;; esac\n" +
            "  echo \"ACC|$an\"\n" +
            "  for b in \"$a\"/*; do\n" +
            "    is_book \"$b\" && emit \"$an\" \"$b\"\n" +
            "  done\n" +
            "done\n" +
            // 2) 平铺层：files/<书目录>，没有账号分层时兜底
            "for b in \"$D\"/*; do\n" +
            "  [ -d \"$b\" ] || continue\n" +
            "  case \"${b##*/}\" in prefix_private_*_chapter_version_*) continue ;; esac\n" +
            "  is_book \"$b\" && emit \"_flat\" \"$b\"\n" +
            "done\n" +
            "echo \"END\"\n";
    }

    public static String scanAll() {
        // 先探测哪种 su 方式真的能看见目标目录
        String[] probe = Root.probe();
        List<String> diag = new ArrayList<String>();
        for (String line : probe[2].split("\n")) if (line.trim().length() > 0) diag.add(line);
        diag.add("选用模式：" + probe[1]);

        Root.Result r = Root.run(script(), 60000);
        diag.add("扫描 exit=" + r.code + (r.timeout ? " timeout" : ""));
        String suPath = "";
        try {
            String p = Root.which("su");
            if (p.length() > 0) suPath = p;
        } catch (Throwable ignored) {}

        String out = r.out == null ? "" : r.out;
        String err = clean(r.err == null ? "" : r.err);

        if (out.length() == 0) {
            return errJson("su 无输出，可能是 root 授权被拒或未安装 su。"
                    + (err.length() > 0 ? " 错误：" + err : ""), diag, suPath);
        }

        boolean node = false, root = false;
        int uid = -1;
        String top = "";
        String acc = "";
        List<Book> books = new ArrayList<Book>();
        List<String> accs = new ArrayList<String>();

        for (String line : out.split("\n")) {
            line = line.trim();
            if (line.length() == 0) continue;
            if (line.startsWith("NODIR")) { node = true; diag.add("目录不存在：" + Root.root()); continue; }
            if (line.startsWith("UID|")) {
                String v = line.substring(4).trim();
                try { uid = Integer.parseInt(v); } catch (Throwable ignored) {}
                if (uid == 0) root = true;
                diag.add("id -u = " + v);
                continue;
            }
            if (line.startsWith("TOPLIST|")) {
                top = line.substring(8);
                diag.add("files/ 一级条目：" + (top.length() > 240 ? top.substring(0, 240) + "…" : top));
                continue;
            }
            if (line.startsWith("ACC|")) {
                acc = line.substring(4).trim();
                if (!accs.contains(acc)) accs.add(acc);
                continue;
            }
            if (line.startsWith("END")) continue;
            if (!line.startsWith("BK|")) continue;
            String[] f = line.split("\\|", 7);
            if (f.length < 7) continue;
            Book b = new Book();
            b.acc = f[1];
            b.dir = f[2];
            String dn = f[2].substring(f[2].lastIndexOf('/') + 1);
            if (dn.startsWith("prefix_public_")) dn = dn.substring("prefix_public_".length());
            else if (dn.startsWith("prefix_private_")) dn = dn.substring("prefix_private_".length());
            b.bid = dn.replaceAll("_[0-9]+$", "");
            b.chapters = num(f[3]);
            b.sizeKb = num(f[4]);
            b.mtime = numL(f[5]);
            b.name = nameFrom(f[6]);
            books.add(b);
        }

        diag.add("识别到账号 " + accs.size() + " 个，书目录 " + books.size() + " 个");
        diag.add("访问模式：" + Root.chosenDetail());
        if (err.length() > 0) diag.add("stderr：" + err);

        // 账号 -> 书（按账号名排序，_flat 放最后）
        Map<String, List<Book>> byAcc = new TreeMap<String, List<Book>>();
        for (Book b : books) {
            List<Book> l = byAcc.get(b.acc);
            if (l == null) { l = new ArrayList<Book>(); byAcc.put(b.acc, l); }
            l.add(b);
        }

        int totalKb = 0, totalCh = 0;
        for (Book b : books) { totalKb += b.sizeKb; totalCh += b.chapters; }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"su\":").append(root)
          .append(",\"uid\":").append(uid)
          .append(",\"suPath\":").append(q(suPath))
          .append(",\"mode\":").append(q(Root.chosenDetail()))
          .append(",\"noDir\":").append(node)
          .append(",\"rootUid\":").append(root)
          .append(",\"total\":").append(books.size())
          .append(",\"totalKb\":").append(totalKb)
          .append(",\"totalCh\":").append(totalCh)
          .append(",\"accCount\":").append(byAcc.size())
          .append(",\"noBook\":").append(books.isEmpty() && !node)
          .append(",\"diag\":[");
        boolean firstD = true;
        for (String d : diag) {
            if (!firstD) sb.append(',');
            firstD = false;
            sb.append(q(d));
        }
        sb.append("],\"accounts\":[");
        boolean firstAcc = true;
        for (Map.Entry<String, List<Book>> e : byAcc.entrySet()) {
            if (!firstAcc) sb.append(',');
            firstAcc = false;
            String nm = e.getKey();
            sb.append("{\"acc\":").append(q(nm))
              .append(",\"books\":[");
            boolean firstBook = true;
            for (Book b : e.getValue()) {
                if (!firstBook) sb.append(',');
                firstBook = false;
                sb.append("{\"bid\":").append(q(b.bid))
                  .append(",\"name\":").append(q(b.name))
                  .append(",\"chapters\":").append(b.chapters)
                  .append(",\"sizeKb\":").append(b.sizeKb)
                  .append(",\"mtime\":").append(b.mtime)
                  .append(",\"dir\":").append(q(b.dir))
                  .append('}');
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /* ---------------- 章节 ---------------- */

    public static String chapters(String dir) {
        if (!Root.safePath(dir)) return err("路径不合法");
        String script =
                "cd '" + dir + "' 2>/dev/null || { echo @@NOCD; exit 0; }\n" +
                "echo @@S\n" +
                "stat -c '%n|%s|%Y' ./* 2>/dev/null\n" +
                "echo @@N\n" +
                "grep -aoH '\"name\":\"[^\"]*\"' ./* 2>/dev/null\n" +
                "echo @@C\n" +
                "grep -aEoH '\"chapter_id\":\"?[0-9]+' ./* 2>/dev/null\n" +
                "echo @@E\n";
        Root.Result r = Root.run(script, 45000);
        if (r.timeout) return err("读取章节超时");
        if (r.out != null && r.out.contains("@@NOCD")) return err("目录不存在或权限不足");
        if (r.out == null) r = new Root.Result("", "", -1, false);

        Map<String, long[]> stat = new TreeMap<String, long[]>();
        Map<String, String> names = new TreeMap<String, String>();
        Map<String, Long> cids = new TreeMap<String, Long>();

        int section = 0;
        for (String line : r.out.split("\n")) {
            if (line.startsWith("@@S")) { section = 1; continue; }
            if (line.startsWith("@@N")) { section = 2; continue; }
            if (line.startsWith("@@C")) { section = 3; continue; }
            if (line.startsWith("@@E")) { section = 4; continue; }
            if (section == 1) {
                String[] p = line.split("\\|", 3);
                if (p.length == 3) stat.put(norm(p[0]), new long[]{numL(p[1]), numL(p[2])});
            } else if (section == 2) {
                int i = line.indexOf(':');
                if (i <= 0) continue;
                int j = line.indexOf("\"name\":\"", i);
                if (j < 0) continue;
                String file = norm(line.substring(0, i));
                String val = line.substring(j + 8);
                if (val.endsWith("\"")) val = val.substring(0, val.length() - 1);
                names.put(file, unescape(val));
            } else if (section == 3) {
                int i = line.indexOf(':');
                if (i <= 0) continue;
                int j = line.indexOf("\"chapter_id\":", i);
                if (j < 0) continue;
                String file = norm(line.substring(0, i));
                String val = line.substring(j + 13).replaceAll("[^0-9]", "");
                if (val.length() > 0) {
                    try { cids.put(file, Long.valueOf(val)); } catch (Throwable ignored) {}
                }
            }
        }

        List<String[]> rows = new ArrayList<String[]>();
        for (Map.Entry<String, long[]> e : stat.entrySet()) {
            String f = e.getKey();
            Long cid = cids.get(f);
            String nm = names.get(f);
            if (nm == null && cid == null) continue;   // 不是章节文件（-shm/-wal/临时件）
            rows.add(new String[]{
                    f,
                    String.valueOf(cid == null ? 0 : cid.longValue()),
                    String.valueOf(e.getValue()[0]),
                    String.valueOf(e.getValue()[1]),
                    nm == null ? "" : nm
            });
        }
        java.util.Collections.sort(rows, new java.util.Comparator<String[]>() {
            @Override public int compare(String[] a, String[] b) {
                int c = sortKey(a).compareTo(sortKey(b));
                return c != 0 ? c : a[0].compareTo(b[0]);
            }
        });

        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"dir\":").append(q(dir))
          .append(",\"total\":").append(rows.size())
          .append(",\"count\":").append(rows.size())
          .append(",\"list\":[");
        boolean first = true;
        for (String[] row : rows) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"f\":").append(q(row[0]))
              .append(",\"cid\":").append(row[1])
              .append(",\"size\":").append(row[2])
              .append(",\"mtime\":").append(row[3])
              .append(",\"name\":").append(q(row[4]))
              .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    /* ---------------- 导出 ---------------- */

    public static String export(String dir, String bid, String name, String metaB64, String listB64) {
        if (!Root.safePath(dir)) return "路径不合法";
        String safeBid = (bid == null ? "" : bid.replaceAll("[^0-9]", ""));
        if (safeBid.length() == 0) return "书 ID 不合法";
        String dest = "/storage/emulated/0/Download/番茄书库/" + safeBid;
        String script =
                "DEST='" + dest + "'\n" +
                // 前一次导出的旧文件必须先清掉，否则目录里会残留已从缓存消失的章节
                "rm -rf \"$DEST\" 2>/dev/null\n" +
                "mkdir -p \"$DEST\" 2>&1 || { echo MKDIRFAIL; exit 0; }\n" +
                "cp -a '" + dir + "/.' \"$DEST/\" 2>&1\n" +
                "printf '%s' '" + metaB64 + "' | base64 -d > \"$DEST/meta.txt\" 2>/dev/null\n" +
                (listB64 == null || listB64.length() == 0 ? "" :
                "printf '%s' '" + listB64 + "' | base64 -d > \"$DEST/chapters.txt\" 2>/dev/null\n") +
                "chown -R media_rw:media_rw \"$DEST\" 2>/dev/null\n" +
                "chmod -R 0644 \"$DEST\" 2>/dev/null\n" +
                "echo @@SIZE\n" +
                "du -sk \"$DEST\" 2>/dev/null\n" +
                "echo @@LIST\n" +
                "ls -1 \"$DEST\" 2>/dev/null | wc -l\n";
        Root.Result r = Root.run(script, 90000);
        if (r.timeout) return "导出超时";
        String out = r.out == null ? "" : r.out;
        if (out.contains("MKDIRFAIL")) return "无法创建目录（存储不可写）";
        long kb = 0, cnt = 0;
        try {
            int sec = 0;
            for (String l : out.split("\n")) {
                if (l.startsWith("@@SIZE")) { sec = 1; continue; }
                if (l.startsWith("@@LIST")) { sec = 2; continue; }
                String t = l.trim();
                if (t.length() == 0 || !Character.isDigit(t.charAt(0))) continue;
                if (sec == 1) { kb = Long.parseLong(t); sec = 0; }
                else if (sec == 2) cnt = Long.parseLong(t);
            }
        } catch (Throwable ignored) {}
        String e = clean(r.err == null ? "" : r.err);
        return "已导出到 " + dest + "（" + cnt + " 个文件，" + kb + " KB）" +
                (e.length() > 0 ? "\n提示：" + e : "");
    }

    /* ---------------- 工具 ---------------- */

    private static final class Book {
        String acc, bid, name, dir;
        int chapters, sizeKb;
        long mtime;
    }

    private static String nameFrom(String b64) {
        try {
            byte[] raw = Base64.decode(b64, Base64.DEFAULT);
            String s = new String(raw, "UTF-8");
            Matcher m = BOOK_NAME.matcher(s);
            if (m.find()) return m.group(1);
        } catch (Throwable ignored) {}
        return "";
    }

    /** 单一排序键：有章号按章号，没有则按修改时间；保证比较可传递。 */
    private static String sortKey(String[] r) {
        int n = chapNo(r[4]);
        if (n > 0) return "1" + pad(n, 6);
        long t = 0;
        try { t = Long.parseLong(r[3]); } catch (Throwable ignored) {}
        return "2" + pad(t, 14) + pad(0, 3);
    }

    private static String pad(long v, int w) {
        String s = String.valueOf(v);
        while (s.length() < w) s = "0" + s;
        return s;
    }

    /** 从“第82章 失控”里取出 82，取不到返回 0。 */
    private static int chapNo(String name) {
        if (name == null) return 0;
        Matcher m = Pattern.compile("\u7b2c([0-9]+)\u7ae0").matcher(name);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Throwable ignored) {}
        }
        return 0;
    }

    private static String norm(String s) {
        if (s == null) return "";
        if (s.startsWith("./")) s = s.substring(2);
        return s;
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\/", "/").replace("\\u003d", "=");
    }

    private static int num(String s) {
        try { return (int) Long.parseLong(s.trim()); } catch (Throwable t) { return 0; }
    }

    private static long numL(String s) {
        try { return Long.parseLong(s.trim()); } catch (Throwable t) { return 0; }
    }

    private static String clean(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 160 ? s.substring(0, 160) : s;
    }

    public static String q(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String err(String m) {
        return "{\"ok\":false,\"msg\":" + q(m) + ",\"accounts\":[],\"total\":0}";
    }

    private static String errJson(String m, List<String> diag, String suPath) {
        StringBuilder sb = new StringBuilder("{\"ok\":false,\"msg\":").append(q(m))
                .append(",\"suPath\":").append(q(suPath))
          .append(",\"mode\":").append(q(Root.chosenDetail()))
                .append(",\"accounts\":[],\"total\":0,\"diag\":[");
        boolean f = true;
        for (String d : diag) { if (!f) sb.append(','); f = false; sb.append(q(d)); }
        return sb.append("]}").toString();
    }
}
