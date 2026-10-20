package com.msnmessenger;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Original MSN Messenger Flash winks (.mco packages), played with the bundled Ruffle Flash emulator. */
public final class Winks {
    static final String HOST = "appassets.androidplatform.net";
    static final String BASE = "https://" + HOST + "/";

    private static String[] IDS = new String[0], NAMES = new String[0], THUMBS = new String[0];
    private static boolean loaded;
    private static final Map<String, Bitmap> CACHE = new HashMap<>();

    private Winks() {}

    /** Opens p (e.g. "winks/index.tsv") from the imported files/msn/ folder first, then bundled assets. */
    static InputStream open(Context c, String p) throws java.io.IOException {
        java.io.File f = new java.io.File(AssetStore.dir(c), p);
        if (!p.contains("..") && f.isFile()) return new java.io.FileInputStream(f);
        return c.getAssets().open(p);
    }

    /** Forget the cached wink list, e.g. after importing a winks archive. */
    static synchronized void reload() { loaded = false; CACHE.clear(); }

    /** Reads assets/winks/index.tsv (id, display name, thumbnail file). */
    static synchronized void load(Context c) {
        if (loaded) return;
        ArrayList<String> ids = new ArrayList<>(), names = new ArrayList<>(), thumbs = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(open(c, "winks/index.tsv"), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t", -1);
                if (f.length < 2 || f[0].isEmpty()) continue;
                ids.add(f[0]);
                names.add(f[1].isEmpty() ? f[0].replace('_', ' ') : f[1]);
                thumbs.add(f.length > 2 ? f[2] : "");
            }
        } catch (Exception ignored) {}
        IDS = ids.toArray(new String[0]);
        NAMES = names.toArray(new String[0]);
        THUMBS = thumbs.toArray(new String[0]);
        loaded = true;
    }

    static int count() { return IDS.length; }
    static String name(int i) { return i >= 0 && i < NAMES.length ? NAMES[i] : "Wink"; }

    static int index(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equalsIgnoreCase(name)) return i;
        return -1;
    }

    /** Fallback picker icon when a wink has no thumbnail. */
    static int icon(Activity a, int i) { return Emoticons.resId(a, 0); }

    static Bitmap thumb(Context c, int i) {
        if (i < 0 || i >= IDS.length || THUMBS[i].isEmpty()) return null;
        String key = IDS[i];
        if (CACHE.containsKey(key)) return CACHE.get(key);
        Bitmap b = null;
        try (InputStream in = open(c, "winks/" + IDS[i] + "/" + THUMBS[i])) {
            b = BitmapFactory.decodeStream(in);
        } catch (Exception ignored) {}
        CACHE.put(key, b);
        return b;
    }

    static void setThumb(ImageView iv, int i) {
        Bitmap b = thumb(iv.getContext(), i);
        if (b != null) iv.setImageBitmap(b);
        else AssetStore.set(iv, Emoticons.name(0));
    }

    static String mime(String p) {
        String l = p.toLowerCase();
        if (l.endsWith(".wasm")) return "application/wasm";
        if (l.endsWith(".js")) return "text/javascript";
        if (l.endsWith(".swf")) return "application/x-shockwave-flash";
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".gif")) return "image/gif";
        if (l.endsWith(".jpg") || l.endsWith(".jpeg")) return "image/jpeg";
        if (l.endsWith(".html")) return "text/html";
        return "application/octet-stream";
    }

    static String html(String id) {
        return "<!doctype html><html><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>html,body{margin:0;height:100%;background:transparent;overflow:hidden}"
            + "#p,ruffle-player{width:100%;height:100%;display:block}</style>"
            + "<script>window.RufflePlayer=window.RufflePlayer||{};window.RufflePlayer.config={"
            + "autoplay:'on',unmuteOverlay:'hidden',splashScreen:false,letterbox:'on',wmode:'transparent',"
            + "backgroundColor:null,contextMenu:'off',publicPath:'/ruffle/',warnOnUnsupportedContent:false,"
            + "showSwfDownload:false,logLevel:'error'};</script>"
            + "<script src='/ruffle/ruffle.js'></script></head><body><div id='p'></div><script>"
            + "window.addEventListener('load',function(){try{"
            + "var pl=window.RufflePlayer.newest().createPlayer();document.getElementById('p').appendChild(pl);"
            + "pl.addEventListener('loadedmetadata',function(){try{var m=(pl.ruffle?pl.ruffle():pl).metadata||pl.metadata;"
            + "var d=m&&m.frameRate?m.numFrames/m.frameRate:0;W.dur(d);"
            + "if(d>=2){setTimeout(function(){try{(pl.ruffle?pl.ruffle():pl).pause();}catch(e){}"
            + "try{pl.pause();}catch(e){}pl.style.visibility='hidden';W.end();},Math.max(500,d*1000-150));}"
            + "}catch(e){W.dur(0);}});"
            + "(pl.ruffle?pl.ruffle():pl).load({url:'/winks/" + id + "/wink.swf'});"
            + "}catch(e){W.fail(''+e);}});</script></body></html>";
    }

    /** Plays original wink i full screen over the activity; tap to dismiss. */
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    static void play(final Activity a, int i) { play(a, i, null); }

    /** Plays wink i; done (may be null) runs once when it closes. */
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    static void play(final Activity a, int i, final Runnable done) {
        load(a);
        if (i < 0 || i >= IDS.length || a.isFinishing()) { if (done != null) done.run(); return; }
        final String id = IDS[i];
        final Handler ui = new Handler(Looper.getMainLooper());
        final ViewGroup root = (ViewGroup) a.getWindow().getDecorView();
        final FrameLayout ov = new FrameLayout(a);
        ov.setBackgroundColor(0x66000000);
        ov.setClickable(true);

        final WebView wv = new WebView(a);
        wv.setBackgroundColor(0x00000000);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setDomStorageEnabled(true);
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String p = u.getPath() == null ? "" : u.getPath();
                if (p.startsWith("/")) p = p.substring(1);
                Map<String, String> h = new HashMap<>();
                h.put("Access-Control-Allow-Origin", "*");
                try {
                    return new WebResourceResponse(mime(p), null, 200, "OK", h, open(a, p));
                } catch (Exception e) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", h, new ByteArrayInputStream(new byte[0]));
                }
            }
        });

        final boolean[] gone = {false};
        final Runnable close = new Runnable() {
            public void run() {
                if (gone[0]) return;
                gone[0] = true;
                try { wv.loadUrl("about:blank"); } catch (Exception ignored) {}
                root.removeView(ov);
                try { wv.destroy(); } catch (Exception ignored) {}
                if (done != null) done.run();
            }
        };
        wv.addJavascriptInterface(new Object() {
            @JavascriptInterface public void dur(double sec) {
                // Fallback only; the page calls end() just before the wink loops.
                long ms = (long) (sec * 1000) + 1500;
                if (sec < 2) ms = 8000;
                if (ms > 16000) ms = 16000;
                ui.removeCallbacks(close);
                ui.postDelayed(close, ms);
            }
            @JavascriptInterface public void fail(String err) { ui.post(close); }
            @JavascriptInterface public void end() { ui.removeCallbacks(close); ui.post(close); }
        }, "W");

        int sw = a.getResources().getDisplayMetrics().widthPixels;
        int sh = a.getResources().getDisplayMetrics().heightPixels;
        int w = Math.min(sw, sh * 4 / 3);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, w * 3 / 4, Gravity.CENTER);
        ov.addView(wv, lp);

        TextView hint = new TextView(a);
        hint.setText(I18n.tr("Tap to close"));
        hint.setTextColor(0xCCFFFFFF);
        hint.setTextSize(12);
        hint.setPadding(0, 0, 0, Math.round(24 * a.getResources().getDisplayMetrics().density));
        ov.addView(hint, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        ov.setOnClickListener(v -> close.run());
        wv.setOnTouchListener((v, e) -> { if (e.getAction() == android.view.MotionEvent.ACTION_UP) close.run(); return true; });
        root.addView(ov, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ui.postDelayed(close, 20000);
        wv.loadDataWithBaseURL(BASE, html(id), "text/html", "utf-8", null);
    }
}
