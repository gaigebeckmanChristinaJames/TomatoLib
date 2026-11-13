package com.tomato.lib;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

public class MainActivity extends Activity {

    private WebView web;
    private SharedPreferences sp;

    /** 兜底底色：与 WebUI 皮肤 A 的 --bg 一致；真实值由 JS 每次换肤时同步过来。 */
    private static final String DEF_BG = "#18181B";

    /** 返回键去抖窗口：防止一次按住/连按被当成多次「返回」而连关两层。 */
    private static final long BACK_GUARD_MS = 320;
    private long lastBackAt = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("tomato_lib", Context.MODE_PRIVATE);

        web = new WebView(this);
        applyBg(sp.getString("ui_bg", DEF_BG));
        web.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Lib");
        setContentView(web);
        web.loadUrl("file:///android_asset/webui/index.html");
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            long now = System.currentTimeMillis();
            if (now - lastBackAt < BACK_GUARD_MS) return true;   // 去抖，丢弃连按
            lastBackAt = now;
            web.evaluateJavascript(
                    "(function(){return window.uiBack?uiBack():false})()",
                    new android.webkit.ValueCallback<String>() {
                        @Override public void onReceiveValue(String v) {
                            lastBackAt = System.currentTimeMillis();
                            if (!"true".equals(v)) finish();
                        }
                    });
            return true;
        }
        return super.onKeyDown(code, e);
    }

    /* ---------------- 异步执行 ---------------- */

    void startScan() {
        new Thread(new Runnable() {
            @Override public void run() {
                final String json = Scanner.scanAll();
                post("window.onScan&&onScan(" + JSONObject.quote(json) + ")");
            }
        }).start();
    }

    void startChapters(final String dir) {
        new Thread(new Runnable() {
            @Override public void run() {
                final String json = Scanner.chapters(dir);
                post("window.onChapters&&onChapters(" + JSONObject.quote(json) + ","
                        + JSONObject.quote(dir) + ")");
            }
        }).start();
    }

    private static String tag(String dir) {
        // 只按书目录名做键，避免 /data/data 与 /data/user/0 前缀变化导致缓存失配
        String last = dir;
        int i = dir.lastIndexOf('/');
        if (i >= 0 && i + 1 < dir.length()) last = dir.substring(i + 1);
        long h = 1125899906842597L;
        for (int j = 0; j < last.length(); j++) h = 31 * h + last.charAt(j);
        return "d" + Math.abs(h);
    }

    private java.io.File cacheFile(String dir, String f) {
        if (dir == null || f == null || dir.length() == 0 || f.length() == 0) return null;
        String safe = f.replaceAll("[^0-9a-zA-Z_-]", "_");
        java.io.File folder = new java.io.File(new java.io.File(getFilesDir(), "decache"), tag(dir));
        if (!folder.exists()) folder.mkdirs();
        return new java.io.File(folder, safe + ".txt");
    }

    private static void deleteTree(java.io.File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            java.io.File[] cs = f.listFiles();
            if (cs != null) for (java.io.File c : cs) deleteTree(c);
        }
        f.delete();
    }

    void startSaveDoc(final String fileName, final String b64) {
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                try {
                    String safe = (fileName == null ? "export.txt" : fileName)
                            .replaceAll("[\\\\/:*?\"<>|]", "_");
                    byte[] data = Base64.decode(b64 == null ? "" : b64, Base64.DEFAULT);
                    java.io.File dir = new java.io.File(getFilesDir(), "export");
                    if (!dir.exists()) dir.mkdirs();
                    java.io.File f = new java.io.File(dir, safe);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                    fos.write(data);
                    fos.close();
                    final String dest = "/storage/emulated/0/Download/番茄书库/导出/" + safe;
                    String script =
                            "mkdir -p '/storage/emulated/0/Download/番茄书库/导出' 2>&1\n" +
                            "cp -f '" + f.getAbsolutePath() + "' '" + dest + "' 2>&1\n" +
                            "chown media_rw:media_rw '" + dest + "' 2>/dev/null\n" +
                            "chmod 0644 '" + dest + "' 2>/dev/null\n" +
                            "echo @@OK\n";
                    Root.Result r = Root.run(script, 30000);
                    String out = r.out == null ? "" : r.out;
                    msg = out.contains("@@OK") ? ("已保存到 " + dest)
                            : ("保存失败：" + (r.err == null ? "root 不可写" : r.err.trim()));
                } catch (Throwable t) {
                    msg = "保存失败：" + t;
                }
                post("window.onSaveDoc&&onSaveDoc(" + JSONObject.quote(msg) + ")");
            }
        }).start();
    }

    void startExport(final String dir, final String bid, final String name, final int chapters,
                     final String listB64) {
        new Thread(new Runnable() {
            @Override public void run() {
                String meta = "番茄书库元数据\n书ID: " + bid + "\n书名: " + name +
                        "\n章节数: " + chapters + "\n源目录: " + dir +
                        "\n说明: 正文为 App 私有加密缓存，mtime/size 为原文件属性\n";
                String b64;
                try { b64 = android.util.Base64.encodeToString(
                        meta.getBytes("UTF-8"), android.util.Base64.NO_WRAP); }
                catch (Throwable t) { b64 = ""; }
                final String msg = Scanner.export(dir, bid, name, b64, listB64);
                post("window.onExport&&onExport(" + JSONObject.quote(msg) + ")");
            }
        }).start();
    }

    /** WebView 与窗口底色一起改，切浅色皮肤/系统深色切换时不再闪黑帧。 */
    private void applyBg(String css) {
        int c;
        try {
            c = Color.parseColor(css == null ? DEF_BG : css.trim());
        } catch (Throwable t) {
            c = Color.parseColor(DEF_BG);
        }
        try { web.setBackgroundColor(c); } catch (Throwable ignored) {}
        try { getWindow().getDecorView().setBackgroundColor(c); } catch (Throwable ignored) {}
        try {
            // 状态栏与页面同色，图标按亮度取黑/白
            getWindow().setStatusBarColor(c);
            getWindow().setNavigationBarColor(c);
            boolean light = Color.luminance(c) > 0.55f;
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            if (light) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | 0x00000010;
            } else {
                flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | 0x00000010);
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        } catch (Throwable ignored) {}
    }

    void startDecrypt(final String dir, final String f) {
        new Thread(new Runnable() {
            @Override public void run() {
                final String json = Decrypt.decrypt(dir, f);
                post("window.onDecrypt&&onDecrypt(" + JSONObject.quote(json) + ","
                        + JSONObject.quote(dir) + "," + JSONObject.quote(f) + ")");
            }
        }).start();
    }

    void post(final String js) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try { web.evaluateJavascript(js, null); } catch (Throwable ignored) {}
            }
        });
    }

    /* ---------------- JS 桥 ---------------- */

    public class Bridge {

        @JavascriptInterface
        public void scan() { startScan(); }

        @JavascriptInterface
        public void chapters(String dir) { startChapters(dir); }

        @JavascriptInterface
        public void export(String dir, String bid, String name, int chapters, String listB64) {
            startExport(dir, bid, name == null ? "" : name, chapters, listB64);
        }

        /** 导出文本类文档：JS 拼好内容传 base64，落到 Download/番茄书库/导出。 */
        @JavascriptInterface
        public void saveDoc(String fileName, String b64) {
            startSaveDoc(fileName, b64);
        }

        /* ---- 解密正文缓存：存在 App 私有目录，重启不丢，不受 WebView 存储限制 ---- */
        @JavascriptInterface
        public String cacheGet(String dir, String f) {
            try {
                java.io.File file = cacheFile(dir, f);
                if (file == null || !file.exists()) return "";
                byte[] b = new byte[(int) file.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(file);
                int n = in.read(b);
                in.close();
                return n <= 0 ? "" : new String(b, 0, n, "UTF-8");
            } catch (Throwable t) { return ""; }
        }

        @JavascriptInterface
        public void cachePut(String dir, String f, String text) {
            final String d = dir, ff = f, t = text == null ? "" : text;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        java.io.File file = cacheFile(d, ff);
                        if (file == null) return;
                        java.io.File parent = file.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        java.io.FileOutputStream out = new java.io.FileOutputStream(file);
                        out.write(t.getBytes("UTF-8"));
                        out.close();
                    } catch (Throwable ignored) {}
                }
            }).start();
        }

        @JavascriptInterface
        public String cacheStat(String dir) {
            try {
                java.io.File base = new java.io.File(getFilesDir(), "decache");
                java.io.File folder = (dir == null || dir.length() == 0) ? base
                        : new java.io.File(base, tag(dir));
                if (!folder.exists()) return "{\"n\":0,\"kb\":0}";
                java.io.File[] fs = folder.listFiles();
                int n = 0; long b = 0;
                if (fs != null) for (java.io.File x : fs) { n++; b += x.length(); }
                return "{\"n\":" + n + ",\"kb\":" + Math.round(b / 1024) + "}";
            } catch (Throwable t) { return "{\"n\":0,\"kb\":0}"; }
        }

        @JavascriptInterface
        public void cacheClear(String dir) {
            try {
                java.io.File base = new java.io.File(getFilesDir(), "decache");
                java.io.File target = (dir == null || dir.length() == 0) ? base
                        : new java.io.File(base, tag(dir));
                deleteTree(target);
            } catch (Throwable ignored) {}
        }

        /** WebUI 换肤时把当前 --bg 推过来，落盘留到下次启动用。 */
        @JavascriptInterface
        public void setBg(String css) {
            if (css == null) return;
            final String v = css.trim();
            if (v.length() == 0) return;
            sp.edit().putString("ui_bg", v).apply();
            runOnUiThread(new Runnable() {
                @Override public void run() { applyBg(v); }
            });
        }

        /** 把解码后的正文写成 TXT，交给系统里已安装的阅读器（如「阅读」）打开。 */
        @JavascriptInterface
        public void openWith(String fileName, String b64) {
            try {
                String name = (fileName == null || fileName.length() == 0)
                        ? ("番茄书库_" + System.currentTimeMillis()) : fileName;
                name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                if (!name.toLowerCase().endsWith(".txt")) name = name + ".txt";
                java.io.File dirf = new java.io.File(getCacheDir(), "share");
                if (!dirf.exists()) dirf.mkdirs();
                java.io.File out = new java.io.File(dirf, name);
                java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
                fo.write(Base64.decode(b64, Base64.DEFAULT));
                fo.close();

                Uri u = new Uri.Builder().scheme("content").authority(ShareProvider.AUTHORITY)
                        .appendPath(name).build();
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_STREAM, u);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "选择阅读器打开"));
            } catch (Throwable t) {
                android.widget.Toast.makeText(MainActivity.this,
                        "分享失败：" + t.getClass().getSimpleName(),
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        }

        @JavascriptInterface
        public void decrypt(String dir, String f) { startDecrypt(dir, f); }

        /** 复制到剪贴板；返回是否成功，并弹一条系统 Toast，避免"点了没反应"。 */
        @JavascriptInterface
        public boolean copy(String text) {
            boolean ok = false;
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("番茄书库", text));
                ok = true;
            } catch (Throwable ignored) {}
            final boolean okFinal = ok;
            final int len = text == null ? 0 : text.length();
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        android.widget.Toast.makeText(MainActivity.this,
                                okFinal ? "已复制到剪贴板（" + len + " 字）" : "复制失败：剪贴板不可用",
                                android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                }
            });
            return ok;
        }

        @JavascriptInterface
        public String getStr(String k, String d) {
            return sp.getString(k, d);
        }

        @JavascriptInterface
        public void setStr(String k, String v) {
            sp.edit().putString(k, v).apply();
        }

        @JavascriptInterface
        public String info() {
            String ver = "";
            try {
                ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Throwable ignored) {}
            return "{\"pkg\":\"com.dragon.read\",\"dataRoot\":\"" + Root.root() +
                    "\",\"ver\":" + JSONObject.quote(ver) + "}";
        }
    }
}
