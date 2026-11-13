package com.tomato.lib;

import android.util.Base64;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 章节正文解密。
 *
 * 落盘格式（已在提示词与真机描述中确认）：
 *   文件 = [二进制头][JSON]，JSON 里 content 是 base64(IV[16] || 密文)。
 *   compress_status == 1 表示明文侧是 gzip（魔数 1f 8b 08）。
 *   密钥不在章节文件里，在账号级 MMKV：
 *     files/mmkv/prefix_public_crypt_key_kv_<uid>            一本书一条「密钥 + 版本」
 *     files/mmkv/prefix_private_<uid>_chapter_version_<bid>  这本书当前密文版本
 *
 * 实现策略（全部带日志）：
 *   1) 读文件、定位 JSON、取 book_id / chapter_id / content / compress_status / content_md5；
 *   2) 读 MMKV（key 表 + 版本表），从二进制里按「book_id 键位附近优先」提取候选密钥，
 *      再退化为全表 16/32 字节窗口扫描（带熵过滤）；
 *   3) 每个候选做 AES-CBC / AES-ECB（PKCS5 与 NoPadding 两种），IV 取密文头 16 字节；
 *   4) 判定成功 = 解出 gzip 且 gunzip 后是合法 UTF-8；content_md5 能对上则直接定案；
 *   5) 失败时把每种方案的失败原因、头部 hex、熵、候选数量全部写进 log，方便下一轮定位。
 */
public final class Decrypt {

    private static final int READ_TIMEOUT = 25000;
    private static final int MAX_FILE = 2 * 1024 * 1024;
    private static final int MAX_KEY_WINDOWS = 8000;
    private static final int MAX_KEYS = 20000;
    private static final int LOCKUP_WINDOW = 128;      // book_id 之后扫描的字节数
    private static final int MAX_TEXT = 400 * 1024;

    private Decrypt() {}

    /* ==================== 对外入口 ==================== */

    public static String decrypt(String dir, String file) {
        List<String> log = new ArrayList<String>();
        long t0 = System.currentTimeMillis();
        Info info = new Info();
        try {
            if (!Root.safePath(dir)) return out(false, "路径不合法", null, info, log, t0);
            if (file == null || !file.matches("[A-Za-z0-9._-]{1,80}")) return out(false, "文件名不合法", null, info, log, t0);

            String path = dir + "/" + file;
            byte[] raw = cat(path);
            if (raw == null || raw.length == 0) {
                log.add("读取失败：" + path + "（root 不可用 / 文件不存在 / 权限不足）");
                return out(false, "读不到章节文件", null, info, log, t0);
            }
            info.size = raw.length;
            log.add("读取 " + path + " → " + raw.length + " 字节");

            JSONObject json = parseJson(raw, log);
            if (json == null) {
                log.add("JSON 解析失败；头部 48 字节：" + hex(raw, 48));
                return out(false, "章节文件里没解析出 JSON", null, info, log, t0);
            }
            info.bookId = str(json, "book_id");
            info.chapterId = str(json, "chapter_id");
            info.name = str(json, "name");
            info.md5 = str(json, "content_md5").toLowerCase(Locale.US);
            info.compress = json.optInt("compress_status", -1);
            info.chapterVersion = str(json, "chapter_version");
            String contentB64 = str(json, "content");
            log.add("JSON：book_id=" + info.bookId + " chapter_id=" + info.chapterId
                    + " name=" + info.name + " compress_status=" + info.compress
                    + " chapter_version=" + info.chapterVersion
                    + " content_md5=" + info.md5);

            if (contentB64.length() == 0) {
                log.add("content 字段为空，无可解密内容");
                return out(false, "content 为空", null, info, log, t0);
            }
            byte[] content = b64(contentB64);
            if (content == null || content.length < 32) {
                log.add("content base64 解码失败或过短（" + contentB64.length() + " 字符）");
                return out(false, "content 解码失败", null, info, log, t0);
            }
            info.ctLen = Math.max(0, content.length - 16);
            info.iv = new byte[16];
            System.arraycopy(content, 0, info.iv, 0, 16);
            byte[] ct = new byte[content.length - 16];
            System.arraycopy(content, 16, ct, 0, ct.length);
            log.add("content base64 " + contentB64.length() + " 字符 → " + content.length
                    + " 字节；IV=" + hex(info.iv, 16) + " 密文=" + ct.length
                    + " 字节（%16=" + (ct.length % 16) + "，%8=" + (ct.length % 8) + "）");
            log.add("密文头部：" + hex(ct, 32) + "  熵=" + String.format(Locale.US, "%.2f", entropy(ct)));

            String uid = uidOf(dir);
            info.uid = uid;
            log.add("账号 uid=" + uid + "，开始读 MMKV 密钥表");

            List<byte[]> keys = new ArrayList<byte[]>();
            List<String> keyNames = new ArrayList<String>();
            collectMmkvKeys(uid, info.bookId, info.md5, info.chapterVersion, keys, keyNames, log);
            info.keyCount = keys.size();
            log.add("候选密钥 " + keys.size() + " 个");

            Hit hit = tryAll(ct, keys, keyNames, info, log);
            long cost = System.currentTimeMillis() - t0;
            log.add("尝试完成：共 " + info.tries + " 次解密，用时 " + cost + " ms");

            if (hit == null) {
                log.add("结论：没有候选密钥能解出 gzip 正文。请把本页日志整段复制出来，据此补 key 来源或版本。");
                return out(false, "未解出正文", null, info, log, t0);
            }
            info.best = hit.desc;
            log.add("命中：" + hit.desc + "；试算序号 #" + info.tries
                    + (hit.md5ok ? "；content_md5 校验一致" : "；gzip 头校验通过"));
            return out(true, "已解密", hit.text, info, log, t0);
        } catch (Throwable t) {
            log.add("异常：" + t.getClass().getSimpleName() + " " + t.getMessage());
            return out(false, "解密异常", null, info, log, t0);
        }
    }

    /* ==================== 试算 ==================== */

    private static final class Hit {
        String text;
        String desc;
        boolean md5ok;
        boolean gzipOk;
        double score;

        int rank() { return md5ok ? 2 : (gzipOk ? 1 : 0); }
    }

    private static Hit tryAll(byte[] ct, List<byte[]> keys, List<String> names, Info info, List<String> log) {
        final String[] modes = {
                "AES/CBC/PKCS5Padding",
                "AES/CBC/NoPadding",
                "AES/ECB/PKCS5Padding",
                "AES/ECB/NoPadding",
                "AES/CTR/NoPadding"
        };
        // 失败原因按「模式」归类，避免日志被几万行淹没
        java.util.Map<String, int[]> fail = new java.util.LinkedHashMap<String, int[]>();
        Hit best = null;
        long deadline = System.currentTimeMillis() + 20000;

        for (int ki = 0; ki < keys.size(); ki++) {
            byte[] k = keys.get(ki);
            for (int mi = 0; mi < modes.length; mi++) {
                if (System.currentTimeMillis() > deadline) {
                    log.add("试算达到 20s 上限，提前结束（已试 " + info.tries + " 次）");
                    return best;
                }
                String mode = modes[mi];
                info.tries++;
                byte[] pt = aes(mode, k, info.iv, ct);
                if (pt == null) { bump(fail, mode + "/填充或参数异常"); continue; }

                byte[] plain = gunzip(pt);
                boolean gz = plain != null;
                if (!gz) {
                    // 有些实现 compress_status 与真实内容不一致，退一步按明文 UTF-8 打分
                    if (!looksUtf8(pt)) { bump(fail, mode + "/非 UTF-8 且非 gzip"); continue; }
                    plain = pt;
                }
                String text = utf8(plain);
                if (text == null || text.length() < 8) { bump(fail, mode + "/文本过短"); continue; }

                double sc = score(text);
                boolean md5ok = info.md5.length() == 32 && info.md5.equalsIgnoreCase(md5hex(plain));
                if (gz && sc < 6 && !md5ok) { bump(fail, mode + "/gzip 但文本不像正文"); continue; }
                if (!gz && sc < 40) { bump(fail, mode + "/文本评分低"); continue; }

                String desc = "key[" + ki + "]" + (ki < names.size() ? "=" + names.get(ki) : "")
                        + " mode=" + mode + " iv=密文头16字节 score=" + String.format(Locale.US, "%.1f", sc)
                        + (gz ? " gzip=通过" : "");
                Hit h = new Hit();
                h.text = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
                h.desc = desc;
                h.md5ok = md5ok;
                h.gzipOk = gz;
                h.score = md5ok ? 1e9 : sc;
                // gzip 结构校验比文本评分硬，优先按「md5 一致 > gzip 通过 > 评分高」排序
                if (best == null || h.rank() > best.rank()
                        || (h.rank() == best.rank() && h.score > best.score)) {
                    best = h;
                }
                if (md5ok || (gz && sc >= 15)) return best;   // gzip 结构已足够硬，命中即收工
            }
        }
        StringBuilder sb = new StringBuilder("失败统计：");
        for (java.util.Map.Entry<String, int[]> e : fail.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()[0]).append("  ");
        }
        log.add(sb.toString().trim());
        return (best != null && (best.md5ok || best.gzipOk || best.score >= 40)) ? best : null;
    }

    private static void bump(java.util.Map<String, int[]> m, String k) {
        int[] v = m.get(k);
        if (v == null) { v = new int[]{0}; m.put(k, v); }
        v[0]++;
    }

    private static byte[] aes(String mode, byte[] key, byte[] iv, byte[] ct) {
        try {
            Cipher c = Cipher.getInstance(mode);
            SecretKeySpec ks = new SecretKeySpec(key, "AES");
            if (mode.contains("ECB")) {
                c.init(Cipher.DECRYPT_MODE, ks);
            } else {
                c.init(Cipher.DECRYPT_MODE, ks, new IvParameterSpec(iv));
            }
            return c.doFinal(ct);
        } catch (Throwable t) {
            return null;
        }
    }

    /* ==================== MMKV 密钥表 ==================== */

    private static final class MmkvCache {
        String uid;
        List<byte[]> buffers = new ArrayList<byte[]>();
        List<String> paths = new ArrayList<String>();
    }

    private static MmkvCache cache;

    private static synchronized MmkvCache mmkv(String uid, String bookId, List<String> log) {
        if (cache != null && uid != null && uid.equals(cache.uid)) return cache;
        MmkvCache c = new MmkvCache();
        c.uid = uid;

        StringBuilder sb = new StringBuilder();
        sb.append("D=/data/data/com.dragon.read/files/mmkv\n");
        sb.append("[ -d \"$D\" ] && ls -1 \"$D\" 2>/dev/null\n");
        Root.Result r = Root.run(sb.toString(), 12000);
        List<String> names = new ArrayList<String>();
        if (r.out != null) {
            for (String l : r.out.split("\n")) {
                String n = l.trim();
                if (n.length() > 0 && !n.contains(" ")) names.add(n);
            }
        }
        List<String> order = new ArrayList<String>();
        int crcSkipped = 0;
        List<String> useful = new ArrayList<String>();
        for (String n : names) {
            if (n.endsWith(".crc")) { crcSkipped++; continue; }   // CRC 旁路文件，不含数据
            useful.add(n);
        }
        // 优先级：crypt_key_kv（密钥表）> chapter_version（版本表）> 名字含 book_id > 其余含 key 的
        for (String n : useful) if (n.contains("crypt_key_kv")) order.add(n);
        for (String n : useful) if (n.contains("chapter_version") && bookId != null
                && n.contains(bookId) && !order.contains(n)) order.add(n);
        for (String n : useful) if (n.contains("crypt") && !order.contains(n)) order.add(n);
        for (String n : useful) if (bookId != null && n.contains(bookId) && !order.contains(n)) order.add(n);
        for (String n : useful) if (n.contains("key") && !order.contains(n)) order.add(n);
        log.add("mmkv 目录：" + (names.isEmpty() ? "空或不可读" : names.size() + " 个文件")
                + (crcSkipped > 0 ? "，跳过 " + crcSkipped + " 个 .crc" : ""));

        // 优先取密钥表与版本表，其次按名字挑；最后兜底全取（上限 8 个）

        if (bookId != null && bookId.length() > 0) {
            for (String n : useful) if (n.contains(bookId) && !order.contains(n)) order.add(n);
        }
        for (String n : useful) if (!order.contains(n)) order.add(n);

        int picked = 0;
        for (String n : order) {
            if (picked >= 12) break;
            if (!n.matches("[A-Za-z0-9._-]{1,120}")) continue;
            byte[] b = catB64("/data/data/com.dragon.read/files/mmkv/" + n, MAX_FILE);
            if (b == null || b.length == 0) continue;
            c.buffers.add(b);
            c.paths.add(n + "(" + b.length + "B)");
            picked++;
        }
        log.add("读取密钥表：" + (c.paths.isEmpty() ? "无" : join(c.paths, "、")));
        cache = c;
        return c;
    }

    private static void collectMmkvKeys(String uid, String bookId, String infoMd5, String infoVersion,
                                        List<byte[]> keys, List<String> names, List<String> log) {
        MmkvCache m = mmkv(uid, bookId, log);
        LinkedHashSet<String> seen = new LinkedHashSet<String>();

        // 线索：book_id / content_md5 / chapter_version 末段密钥 ID / 整条版本串
        List<String> clues = new ArrayList<String>();
        clues.add(bookId == null ? "" : bookId);
        clues.add(infoMd5);
        clues.add(versionKeyId(infoVersion));
        clues.add(infoVersion);

        // 0) 先解析密钥表：这是真正有密钥的地方，必须排在杂项窗口之前，否则名额会被挤爆
        int total = 0;
        StringBuilder keysSeen = new StringBuilder();
        for (int bi = 0; bi < m.buffers.size(); bi++) {
            String pth = m.paths.get(bi);
            boolean whole = pth.contains("crypt_key_kv") || pth.contains("chapter_version");
            total += parseEntries(m.buffers.get(bi), clues, whole, keys, names, seen,
                    log, pth, bi, keysSeen);
        }
        log.add("MMKV 条目解析：" + (total == 0
                ? "0 条（布局不符，退化为窗口扫描）"
                : total + " 条，示例 key：" + abbreviate(keysSeen.toString().trim(), 80)));

        byte[] bid = bookId == null ? new byte[0] : bookId.getBytes();

        // 1) book_id 键位附近：MMKV 的 entry 是 key 后紧跟 value，key 后面 128 字节里挑窗口
        for (int bi = 0; bi < m.buffers.size(); bi++) {
            byte[] buf = m.buffers.get(bi);
            List<Integer> pos = findAll(buf, bid);
            if (!pos.isEmpty()) log.add("在 " + m.paths.get(bi) + " 里找到 book_id " + pos.size() + " 处");
            for (int p : pos) {
                int start = p + bid.length;
                if (p == pos.get(0)) {
                    // 把键位后面的原始字节记下来：密钥若不是 16 字节裸值，靠这段就能看出编码方式
                    log.add(m.paths.get(bi) + " 键后 48 字节：" + hex(slice(buf, start, 48), 48));
                }
                for (int off = 0; off < 32; off++) {
                    for (int ks : new int[]{16, 32}) {
                        int from = start + off;
                        if (from < 0 || from + ks > buf.length) continue;
                        addKey(keys, names, seen, slice(buf, from, ks), "mmkv#" + bi + "@" + (from - p) + ":" + ks);
                    }
                }
                // 也试试把 book_id 后面当 ASCII hex / base64 解出来的密钥
                String tail = new String(buf, start, Math.min(96, buf.length - start));
                for (String tok : tokens(tail)) {
                    for (byte[] k : fromToken(tok)) addKey(keys, names, seen, k, "mmkv-token:" + k.length);
                }
            }
        }

        // 2) 按「线索 token」找值：book_id、content_md5、chapter_version 里的密钥 ID、完整版本串
        // 1.5) 派生候选：线索两两 md5，以及把密钥 ID 本身当 16 字节 key
        String keyId = versionKeyId(infoVersion);
        String[] parts = new String[]{infoMd5, infoVersion, keyId, bookId == null ? "" : bookId};
        for (String x : parts) {
            if (x == null || x.length() == 0) continue;
            addKey(keys, names, seen, md5raw(x), "md5(" + abbreviate(x, 16) + ")");
            for (String y : parts) {
                if (y == null || y.length() == 0 || y.equals(x)) continue;
                addKey(keys, names, seen, md5raw(x + y),
                        "md5(" + abbreviate(x, 12) + "+" + abbreviate(y, 12) + ")");
            }
        }
        if (keyId.matches("[0-9a-fA-F]{16}|[0-9a-fA-F]{24}|[0-9a-fA-F]{32}")) {
            byte[] kb = unhex(keyId);
            if (kb != null) addKey(keys, names, seen, kb, "keyId-hex:" + kb.length);
        }
        if (keyId.matches("[0-9a-fA-F]{14,15}")) {
            byte[] kb = new byte[16];
            for (int i = 0; i < keyId.length() / 2; i++) {
                kb[i] = (byte) Integer.parseInt(keyId.substring(i * 2, i * 2 + 2), 16);
            }
            addKey(keys, names, seen, kb, "keyId-pad16");
        }

        for (int bi = 0; bi < m.buffers.size(); bi++) {
            byte[] buf = m.buffers.get(bi);
            mmkvDiag(buf, m.paths.get(bi), log);
            for (String c : clues) {
                if (c == null || c.length() < 6) continue;
                List<Integer> hits = findAll(buf, c.getBytes());
                if (!hits.isEmpty()) {
                    log.add("线索「" + abbreviate(c, 40) + "」在 " + m.paths.get(bi) + " 命中 " + hits.size() + " 处");
                    for (int p : hits) {
                        int start = p + c.length();
                        addWindowKeys(buf, start, 128, keys, names, seen, "clue#" + bi + "@" + p);
                        addTokenKeys(buf, start, 128, keys, names, seen, "cluetok#" + bi);
                    }
                    // 线索本身也可能是裸密钥（hex / base64）
                    for (byte[] k : fromToken(c)) addKey(keys, names, seen, k, "clue-self:" + c.length());
                }
            }
        }


        // 4) 全表窗口扫描（熵过滤），覆盖「值没挨着键」的布局
        int windows = 0;
        for (int bi = 0; bi < m.buffers.size() && windows < MAX_KEY_WINDOWS; bi++) {
            byte[] buf = m.buffers.get(bi);
            int step = Math.max(1, buf.length / MAX_KEY_WINDOWS);
            for (int i = 0; i + 32 <= buf.length && windows < MAX_KEY_WINDOWS; i += step) {
                byte[] w16 = slice(buf, i, 16);
                byte[] w32 = slice(buf, i, 32);
                if (entropy(w16) < 3.2 && entropy(w32) < 3.2) continue;
                windows++;
                addKey(keys, names, seen, w16, "scan#" + bi + "@" + i + ":16");
                addKey(keys, names, seen, w32, "scan#" + bi + "@" + i + ":32");
                if (keys.size() >= MAX_KEYS) break;
            }
        }
        log.add("窗口扫描 " + windows + " 个位置");
    }

    /** chapter_version 形如 "<md5>_<n>_<密钥ID>"，取最后一段当密钥 ID 线索。 */
    private static String versionKeyId(String ver) {
        if (ver == null || ver.length() == 0) return "";
        int i = ver.lastIndexOf('_');
        return i >= 0 && i + 1 < ver.length() ? ver.substring(i + 1) : "";
    }

    /** 把某个文件头尾、长度字段、可读率打进日志：判断密钥表是明文还是密文的关键证据。 */
    private static void mmkvDiag(byte[] buf, String name, List<String> log) {
        if (buf == null) return;
        int tail = Math.min(64, buf.length);
        log.add(name + " 长度字段=" + le32(buf, 0) + " 头64=" + hex(buf, 64));
        log.add(name + " 尾64=" + hex(slice(buf, buf.length - tail, tail), tail)
                + " 可读率=" + String.format(Locale.US, "%.2f", printableRatio(buf))
                + " 熵=" + String.format(Locale.US, "%.2f", entropy(buf))
                + (printableRatio(buf) < 0.5 ? "（可读率低：疑似 MMKV 加密模式）" : "（明文布局）"));
    }

    private static double printableRatio(byte[] b) {
        if (b == null || b.length == 0) return 0;
        int ok = 0;
        for (byte x : b) {
            int c = x & 0xff;
            if ((c >= 0x20 && c < 0x7f) || c == 0x0a || c == 0x0d || c == 0x09) ok++;
        }
        return (double) ok / b.length;
    }

    /** 从 from 开始取 N 字节内每个偏移的 16/32 字节窗口。 */
    private static void addWindowKeys(byte[] buf, int from, int span, List<byte[]> keys,
                                      List<String> names, LinkedHashSet<String> seen, String tag) {
        for (int off = 0; off < span; off++) {
            int p = from + off;
            if (p < 0 || p + 16 > buf.length) break;
            addKey(keys, names, seen, slice(buf, p, 16), tag + "@" + off + ":16");
            if (p + 32 <= buf.length) addKey(keys, names, seen, slice(buf, p, 32), tag + "@" + off + ":32");
        }
    }

    /** 从 from 开始 N 字节里的 hex / base64 令牌也当候选。 */
    private static void addTokenKeys(byte[] buf, int from, int span, List<byte[]> keys,
                                     List<String> names, LinkedHashSet<String> seen, String tag) {
        int len = Math.min(span, buf.length - from);
        if (len <= 0) return;
        String v = new String(buf, from, len);
        for (String tok : tokens(v)) {
            for (byte[] k : fromToken(tok)) addKey(keys, names, seen, k, tag + ":" + k.length);
        }
    }

    /**
     * MMKV 落盘的明文布局：前 4 字节小端是数据区长度，之后是连续条目
     *   keyLen(varint) key  valueLen(varint) value
     * 值长度为 0 表示该键被删除。解析通了就把「键 == book_id」的值整段当解密材料。
     */
    private static int parseEntries(byte[] buf, List<String> clues, boolean wholeTable,
                                    List<byte[]> keys, List<String> names,
                                    LinkedHashSet<String> seen, List<String> log, String tag,
                                    int bufIdx, StringBuilder keysSeen) {
        if (buf == null || buf.length < 8) return 0;
        int actual = le32(buf, 0);
        int end = Math.min(buf.length, 4 + Math.max(0, actual));
        // 实测数据区前面固定带 4 字节 ff ff ff 07，跳过它才是第一条 key 的 varint 长度
        int pos = entriesStart(buf);
        int entries = 0;
        int[] cons = new int[]{0};

        while (pos < end) {
            long kl = varint(buf, pos, cons);
            if (kl <= 0 || kl > 4096 || cons[0] == 0) break;
            pos += cons[0];
            if (pos + kl > end) break;
            String key;
            try { key = new String(buf, pos, (int) kl, "UTF-8"); } catch (Throwable t) { break; }
            pos += (int) kl;

            long vl = varint(buf, pos, cons);
            if (vl < 0 || cons[0] == 0 || vl > 4 * 1024 * 1024) break;
            pos += cons[0];
            if (pos + vl > end) break;
            int valStart = pos;
            entries++;
            if (entries <= 6 && keysSeen.length() < 160) {
                keysSeen.append(abbreviate(key, 24)).append('=').append(vl).append("  ");
            }
            boolean match = wholeTable;
            if (!match) {
                for (String c : clues) {
                    if (c != null && c.length() >= 6 && key.contains(c)) { match = true; break; }
                }
            }
            if (match) {
                log.add(tag + " 条目「" + abbreviate(key, 60) + "」值 " + vl + " 字节："
                        + hex(slice(buf, valStart, (int) Math.min(vl, 48)), 48));
                addValueKeys(buf, valStart, (int) vl, keys, names, seen, "mmkv#" + bufIdx + ":entry");
            }
            pos += (int) vl;
        }
        return entries;
    }

    /** MMKV 数据区起点：跳过 4 字节长度字段，若随后是 ff ff ff 07 标记再跳过 4 字节。 */
    private static int entriesStart(byte[] buf) {
        if (buf.length > 8 && (buf[4] & 0xff) == 0xff && (buf[5] & 0xff) == 0xff
                && (buf[6] & 0xff) == 0xff && (buf[7] & 0xff) == 0x07) {
            return 8;
        }
        return 4;
    }

    /** 一个 MMKV 值的所有可能取法：整段 / 首 16-32 / 尾 16-32 / 值内 hex/base64 令牌。 */
    private static void addValueKeys(byte[] buf, int start, int len, List<byte[]> keys,
                                     List<String> names, LinkedHashSet<String> seen, String tag) {
        addKey(keys, names, seen, slice(buf, start, 16), tag + ":head16");
        if (len >= 32) addKey(keys, names, seen, slice(buf, start, 32), tag + ":head32");
        if (len >= 16) addKey(keys, names, seen, slice(buf, start + len - 16, 16), tag + ":tail16");
        if (len > 32) addKey(keys, names, seen, slice(buf, start + len - 32, 32), tag + ":tail32");
        // 实测值形如 " B79F1EF8…"（0x20 + 32 位 hex）或 "@JxFO…"（0x40 + 64 位 base64），先剥标记再解码
        if (len > 2) {
            int b0 = buf[start] & 0xff;
            if (b0 >= 0x20 && b0 < 0x7f) {
                String body = new String(buf, start + 1, len - 1);
                byte[] hexBytes = body.matches("[0-9a-fA-F]{16,64}") ? unhex(body.trim()) : null;
                if (hexBytes != null) addDecoded(keys, names, seen, hexBytes, tag + ":hex");
                byte[] b64Bytes = b64(body.trim());
                if (b64Bytes != null && b64Bytes.length >= 16) {
                    addDecoded(keys, names, seen, b64Bytes, tag + ":b64");
                }
            }
        }
        try {
            String v = new String(buf, start, Math.min(len, 128), "UTF-8");
            for (String tok : tokens(v)) {
                for (byte[] k : fromToken(tok)) addKey(keys, names, seen, k, tag + ":token:" + k.length);
            }
        } catch (Throwable ignored) {}
    }

    /** 把一段解码结果按 16/24/32 字节的各种切法全部登记为候选。 */
    private static void addDecoded(List<byte[]> keys, List<String> names, LinkedHashSet<String> seen,
                                   byte[] d, String tag) {
        if (d == null || d.length < 16) return;
        for (int off = 0; off + 16 <= d.length; off += 8) {
            addKey(keys, names, seen, slice(d, off, 16), tag + "@" + off + ":16");
            if (off + 24 <= d.length) addKey(keys, names, seen, slice(d, off, 24), tag + "@" + off + ":24");
            if (off + 32 <= d.length) addKey(keys, names, seen, slice(d, off, 32), tag + "@" + off + ":32");
        }
    }

    private static long varint(byte[] b, int pos, int[] consumed) {
        long r = 0;
        int shift = 0, i = pos;
        while (i < b.length && shift <= 28) {
            int x = b[i++] & 0xff;
            r |= (long) (x & 0x7f) << shift;
            if ((x & 0x80) == 0) { consumed[0] = i - pos; return r; }
            shift += 7;
        }
        consumed[0] = 0;
        return -1;
    }

    private static int le32(byte[] b, int off) {
        if (b == null || off + 4 > b.length) return 0;
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static String abbreviate(String s, int n) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    private static void addKey(List<byte[]> keys, List<String> names, LinkedHashSet<String> seen,
                               byte[] k, String tag) {
        if (k == null || (k.length != 16 && k.length != 24 && k.length != 32)) return;
        boolean flat = true;
        for (byte b : k) if (b != k[0]) { flat = false; break; }
        if (flat) return;
        String h = hex(k, k.length);
        if (!seen.add(h)) return;
        if (keys.size() >= MAX_KEYS) return;
        keys.add(k);
        names.add(tag);
    }

    /** 从一段文本里挑出像 hex / base64 的 16~64 字节令牌。 */
    private static List<String> tokens(String s) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || c == '+' || c == '/' || c == '=' || c == '_' || c == '-';
            if (ok) cur.append(c);
            else {
                if (cur.length() >= 8) out.add(cur.toString());
                cur.setLength(0);
            }
            if (out.size() >= 24) break;
        }
        if (cur.length() >= 8 && out.size() < 24) out.add(cur.toString());
        return out;
    }

    private static List<byte[]> fromToken(String tok) {
        List<byte[]> out = new ArrayList<byte[]>();
        if (tok.matches("[0-9a-fA-F]{32}|[0-9a-fA-F]{48}|[0-9a-fA-F]{64}")) {
            byte[] b = unhex(tok);
            if (b != null) out.add(b);
        }
        byte[] b64 = b64(tok);
        if (b64 != null && (b64.length == 16 || b64.length == 24 || b64.length == 32)) out.add(b64);
        try {
            byte[] asc = tok.getBytes("UTF-8");
            if (asc.length == 16 || asc.length == 24 || asc.length == 32) out.add(asc);
        } catch (Throwable ignored) {}
        return out;
    }

    /* ==================== 文件 / 解析工具 ==================== */

    private static byte[] cat(String path) {
        return catB64(path, MAX_FILE);
    }

    private static byte[] catB64(String path, int cap) {
        if (path == null || !path.startsWith("/data/data/com.dragon.read/")) return null;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '/' || c == '_' || c == '-' || c == '.')) return null;
        }
        Root.Result r = Root.run("base64 \"" + path + "\" 2>/dev/null | tr -d '\\n' | head -c "
                + ((cap * 4 / 3) + 16), READ_TIMEOUT);
        if (r.out == null || r.out.trim().length() == 0) return null;
        try {
            return Base64.decode(r.out.trim(), Base64.DEFAULT);
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONObject parseJson(byte[] raw, List<String> log) {
        // 二进制头后面可能还有 Java 序列化残渣（aced0005 t… {{），逐个 '{' 试
        int tried = 0;
        for (int i = 0; i < Math.min(raw.length, 4096) && tried < 6; i++) {
            if (raw[i] != '{') continue;
            tried++;
            try {
                String s = new String(raw, i, raw.length - i, "UTF-8");
                Object v = new JSONTokener(s).nextValue();
                if (v instanceof JSONObject) {
                    log.add("JSON 起点偏移 " + i + "，二进制头 " + i + " 字节："
                            + hex(raw, Math.min(i, 32)));
                    return (JSONObject) v;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String uidOf(String dir) {
        String[] p = dir.split("/");
        return p.length >= 2 ? p[p.length - 2] : "";
    }

    private static String str(JSONObject o, String k) {
        try {
            String v = o.optString(k, "");
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    private static byte[] b64(String s) {
        try { return Base64.decode(s, Base64.DEFAULT); } catch (Throwable ignored) {}
        try { return Base64.decode(s, Base64.NO_WRAP | Base64.URL_SAFE); } catch (Throwable ignored) {}
        try { return Base64.decode(s, Base64.NO_PADDING | Base64.NO_WRAP); } catch (Throwable ignored) {}
        return null;
    }

    private static byte[] gunzip(byte[] b) {
        if (b == null || b.length < 18) return null;
        if ((b[0] & 0xff) != 0x1f || (b[1] & 0xff) != 0x8b) return null;
        try {
            GZIPInputStream g = new GZIPInputStream(new ByteArrayInputStream(b));
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = g.read(buf)) > 0) {
                o.write(buf, 0, n);
                total += n;
                if (total > MAX_TEXT) break;
            }
            return o.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] inflate(byte[] b) {
        try {
            Inflater f = new Inflater();
            f.setInput(b);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while (!f.finished() && (n = f.inflate(buf)) > 0) {
                o.write(buf, 0, n);
                total += n;
                if (total > MAX_TEXT) break;
            }
            f.end();
            return o.size() > 0 ? o.toByteArray() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String utf8(byte[] b) {
        if (b == null) return null;
        try {
            return new String(b, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean looksUtf8(byte[] b) {
        if (b == null || b.length == 0) return false;
        try {
            String s = new String(b, "UTF-8");
            return s.indexOf('\uFFFD') < 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 文本评分：CJK 占比 + 可打印率 - 控制字符。 */
    private static double score(String s) {
        if (s == null || s.length() == 0) return 0;
        int cjk = 0, printable = 0, ctrl = 0;
        int n = Math.min(s.length(), 4000);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fff) cjk++;
            if (c == '\n' || c == '\r' || c == '\t' || c >= 0x20) printable++;
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') ctrl++;
        }
        double r = (double) cjk / n;
        double p = (double) printable / n;
        double c = (double) ctrl / n;
        return r * 100 + p * 3 - c * 60;
    }

    private static double entropy(byte[] b) {
        if (b == null || b.length == 0) return 0;
        int[] cnt = new int[256];
        for (byte x : b) cnt[x & 0xff]++;
        double e = 0;
        for (int c : cnt) {
            if (c == 0) continue;
            double p = (double) c / b.length;
            e -= p * (Math.log(p) / Math.log(2));
        }
        return e;
    }

    private static String md5hex(byte[] b) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format(Locale.US, "%02x", x & 0xff));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static byte[] md5raw(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return md.digest(s.getBytes("UTF-8"));
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] slice(byte[] b, int from, int len) {
        if (from < 0 || from + len > b.length) return null;
        byte[] o = new byte[len];
        System.arraycopy(b, from, o, 0, len);
        return o;
    }

    private static byte[] unhex(String s) {
        try {
            byte[] o = new byte[s.length() / 2];
            for (int i = 0; i < o.length; i++) {
                o[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
            }
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<Integer> findAll(byte[] hay, byte[] needle) {
        List<Integer> out = new ArrayList<Integer>();
        if (needle == null || needle.length < 4) return out;
        int limit = hay.length - needle.length;
        for (int i = 0; i <= limit && out.size() < 8; i++) {
            boolean ok = true;
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) { ok = false; break; }
            }
            if (ok) { out.add(i); i += needle.length - 1; }
        }
        return out;
    }

    private static String hex(byte[] b, int n) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(b.length, n); i++) sb.append(String.format(Locale.US, "%02x", b[i] & 0xff));
        return sb.toString();
    }

    private static String join(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }

    /* ==================== 输出 ==================== */

    private static final class Info {
        int size, ctLen, keyCount, tries;
        String bookId = "", chapterId = "", name = "", md5 = "", chapterVersion = "", uid = "", best = "";
        byte[] iv;
        int compress = -1;
    }

    private static String out(boolean okDecrypt, String msg, String text, Info i,
                              List<String> log, long t0) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"okDecrypt\":").append(okDecrypt)
          .append(",\"msg\":").append(Scanner.q(msg))
          .append(",\"text\":").append(Scanner.q(text == null ? "" : text))
          .append(",\"tries\":").append(i.tries)
          .append(",\"keys\":").append(i.keyCount)
          .append(",\"costMs\":").append(System.currentTimeMillis() - t0)
          .append(",\"best\":").append(Scanner.q(i.best))
          .append(",\"meta\":{")
          .append("\"size\":").append(i.size)
          .append(",\"bookId\":").append(Scanner.q(i.bookId))
          .append(",\"chapterId\":").append(Scanner.q(i.chapterId))
          .append(",\"name\":").append(Scanner.q(i.name))
          .append(",\"md5\":").append(Scanner.q(i.md5))
          .append(",\"compress\":").append(i.compress)
          .append(",\"chapterVersion\":").append(Scanner.q(i.chapterVersion))
          .append(",\"uid\":").append(Scanner.q(i.uid))
          .append(",\"ctLen\":").append(i.ctLen)
          .append(",\"iv\":").append(Scanner.q(i.iv == null ? "" : hex(i.iv, 16)))
          .append("},\"log\":[");
        boolean first = true;
        for (String l : log) {
            if (!first) sb.append(',');
            first = false;
            sb.append(Scanner.q(l));
        }
        return sb.append("]}").toString();
    }
}
