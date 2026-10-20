package com.msnmessenger;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;

/** Display pictures: original MSN pictures, a custom photo, or the Matrix profile picture. */
public class Avatars {
    public static final int[] PICS = {
        R.drawable.pic_duck, R.drawable.pic_soccer, R.drawable.pic_flower, R.drawable.pic_chess,
        R.drawable.pic_beach, R.drawable.pic_dog, R.drawable.pic_horses, R.drawable.pic_moto,
        R.drawable.pic_palms, R.drawable.pic_shuttle, R.drawable.pic_skater, R.drawable.tile_default
    };
    public static final String[] PIC_NAMES = {
        "Rubber Duck", "Soccer", "Flower", "Chess", "Beach", "Dog", "Horses", "Motorcycle",
        "Palm Trees", "Space Shuttle", "Skater", "Default"
    };

    /** userId -> mxc:// avatar URL, learned from sync member events and profiles. */
    public static final ConcurrentHashMap<String, String> MXC = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, Bitmap> MEM = new ConcurrentHashMap<>();

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("msn", Context.MODE_PRIVATE); }

    static File customFile(Context c) { return new File(c.getFilesDir(), "my_picture.img"); }

    /** Last Matrix profile picture shown, kept for the sign-in screen. */
    static File lastFile(Context c) { return new File(c.getFilesDir(), "last_picture.png"); }

    static void saveLast(Context c, Bitmap b) {
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(lastFile(c))) { b.compress(Bitmap.CompressFormat.PNG, 100, o); } catch (Exception ignored) {}
    }

    /** Shows the last used display picture on the sign-in screen, like Messenger 8.5. */
    public static boolean showLogin(Context c, ImageView iv) {
        if (iv == null) return false;
        SharedPreferences p = prefs(c);
        String kind = p.getString("pic_kind", "builtin");
        if ("custom".equals(kind) || "matrix".equals(kind)) {
            File f = "custom".equals(kind) ? customFile(c) : lastFile(c);
            Bitmap b = f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
            if (b != null) { iv.setImageBitmap(b); return true; }
            return false;
        }
        int i = p.getInt("pic_index", 0);
        AssetStore.set(iv, PICS[Math.max(0, Math.min(PICS.length - 1, i))]);
        return true;
    }

    /** Shows the signed-in user's chosen picture. */
    public static void showMine(Activity a, ImageView iv, String server, String token, String userId) {
        if (iv == null) return;
        SharedPreferences p = prefs(a);
        String kind = p.getString("pic_kind", "builtin");
        if ("custom".equals(kind)) {
            Bitmap b = BitmapFactory.decodeFile(customFile(a).getAbsolutePath());
            if (b != null) { iv.setImageBitmap(b); return; }
        }
        if ("matrix".equals(kind) && userId != null) {
            new Thread(() -> {
                String m0 = MXC.get(userId);
                if (m0 == null) m0 = profileAvatar(server, token, userId);
                if (m0 != null) { Bitmap bm = fetch(a, server, token, m0); if (bm != null) saveLast(a, bm); }
            }).start();
            String mxc = MXC.get(userId);
            if (mxc != null) { show(a, iv, server, token, mxc, PICS[0]); return; }
            AssetStore.set(iv, PICS[0]);
            new Thread(() -> {
                String m = profileAvatar(server, token, userId);
                if (m != null) { MXC.put(userId, m); a.runOnUiThread(() -> show(a, iv, server, token, m, PICS[0])); }
            }).start();
            return;
        }
        int i = p.getInt("pic_index", 0);
        AssetStore.set(iv, PICS[Math.max(0, Math.min(PICS.length - 1, i))]);
    }

    /** Shows a contact's Matrix picture, or the fallback MSN picture if they have none. */
    public static void showUser(Activity a, ImageView iv, String server, String token, String uid, int fallback) {
        if (iv == null) return;
        String mxc = uid == null ? null : MXC.get(uid);
        if (mxc != null) { show(a, iv, server, token, mxc, fallback); return; }
        AssetStore.set(iv, fallback);
        if (uid == null || server == null || token == null) return;
        new Thread(() -> {
            String m = profileAvatar(server, token, uid);
            if (m != null) { MXC.put(uid, m); a.runOnUiThread(() -> show(a, iv, server, token, m, fallback)); }
        }).start();
    }

    public static void show(Activity a, ImageView iv, String server, String token, String mxc, int fallback) {
        Bitmap b = MEM.get(mxc);
        if (b != null) { iv.setImageBitmap(b); return; }
        AssetStore.set(iv, fallback);
        iv.setTag(mxc);
        new Thread(() -> {
            Bitmap bm = fetch(a, server, token, mxc);
            if (bm == null) return;
            MEM.put(mxc, bm);
            a.runOnUiThread(() -> { if (mxc.equals(iv.getTag())) iv.setImageBitmap(bm); });
        }).start();
    }

    static Bitmap fetch(Context c, String server, String token, String mxc) {
        return fetch(c, server, token, mxc, "?width=96&height=96&method=crop", "avatars");
    }

    /** Decodes image bytes, shrinking very large pictures so they cannot exhaust memory. */
    public static Bitmap decodeSampled(byte[] data, int max) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int s = 1;
        while (o.outWidth / (s * 2) >= max || o.outHeight / (s * 2) >= max) s *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = s;
        return BitmapFactory.decodeByteArray(data, 0, data.length, d);
    }

    /** Shows a picture sent in a conversation, scaled to fit and cached separately from display pictures. */
    public static void showImage(Activity a, ImageView iv, String server, String token, String mxc, int placeholder) {
        showImage(a, iv, server, token, mxc, placeholder, 0, 0);
    }

    /** Same, but scales the picture to fill a maxW x maxH pixel box (up or down), keeping its shape. */
    public static void showImage(Activity a, ImageView iv, String server, String token, String mxc, int placeholder, final int maxW, final int maxH) {
        final String key = "img:" + mxc + "@" + maxW + "x" + maxH;
        final int dpi = a.getResources().getDisplayMetrics().densityDpi;
        iv.setTag(key);
        Bitmap b = MEM.get(key);
        if (b != null) { iv.setImageBitmap(b); return; }
        AssetStore.set(iv, placeholder);
        new Thread(() -> {
            Bitmap bm = fetch(a, server, token, mxc, "?width=800&height=800&method=scale", "images800");
            if (bm == null) return;
            if (maxW > 0 && maxH > 0) bm = fitBox(bm, maxW, maxH, dpi);
            final Bitmap fb = bm;
            MEM.put(key, fb);
            a.runOnUiThread(() -> { if (key.equals(iv.getTag())) iv.setImageBitmap(fb); });
        }).start();
    }

    static Bitmap fitBox(Bitmap b, int w, int h, int dpi) {
        float k = Math.min(w / (float) b.getWidth(), h / (float) b.getHeight());
        Bitmap s = Math.abs(k - 1f) < 0.01f ? b : Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
        s.setDensity(dpi);
        return s;
    }

    static Bitmap fetch(Context c, String server, String token, String mxc, String q, String sub) {
        try {
            if (!mxc.startsWith("mxc://")) return null;
            String path = mxc.substring(6);
            File dir = new File(c.getCacheDir(), sub); dir.mkdirs();
            File f = new File(dir, Integer.toHexString(mxc.hashCode()) + ".img");
            if (f.length() > 0) { Bitmap b = decodeSampled(readAll(new java.io.FileInputStream(f)), 1024); if (b != null) return b; }
            String base = server.replaceAll("/+$", "");
            String[] urls = { base + "/_matrix/client/v1/media/thumbnail/" + path + q, base + "/_matrix/media/v3/thumbnail/" + path + q,
                              base + "/_matrix/client/v1/media/download/" + path, base + "/_matrix/media/v3/download/" + path };
            for (String u : urls) {
                HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
                conn.setConnectTimeout(10000); conn.setReadTimeout(20000);
                conn.setRequestProperty("Authorization", "Bearer " + token);
                if (conn.getResponseCode() != 200) { conn.disconnect(); continue; }
                byte[] data = readAll(conn.getInputStream());
                Bitmap b = decodeSampled(data, 1024);
                if (b == null) continue;
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
                return b;
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static String profileAvatar(String server, String token, String uid) {
        try {
            String u = server.replaceAll("/+$", "") + "/_matrix/client/v3/profile/" + URLEncoder.encode(uid, "UTF-8") + "/avatar_url";
            HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
            conn.setConnectTimeout(10000); conn.setReadTimeout(15000);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            if (conn.getResponseCode() != 200) return null;
            String t = new Scanner(conn.getInputStream(), "UTF-8").useDelimiter("\\A").next();
            String m = new JSONObject(t).optString("avatar_url", "");
            return m.startsWith("mxc://") ? m : null;
        } catch (Exception e) { return null; }
    }

    /** Uploads an image and sets it as the Matrix profile picture. Returns the mxc URL or null. */
    public static String uploadAsProfile(String server, String token, String uid, byte[] data, String mime) {
        try {
            String base = server.replaceAll("/+$", "");
            HttpURLConnection up = (HttpURLConnection) new URL(base + "/_matrix/media/v3/upload?filename=display_picture").openConnection();
            up.setRequestMethod("POST"); up.setDoOutput(true);
            up.setConnectTimeout(15000); up.setReadTimeout(30000);
            up.setRequestProperty("Authorization", "Bearer " + token);
            up.setRequestProperty("Content-Type", mime == null ? "image/png" : mime);
            up.getOutputStream().write(data);
            if (up.getResponseCode() != 200) return null;
            String mxc = new JSONObject(new Scanner(up.getInputStream(), "UTF-8").useDelimiter("\\A").next()).optString("content_uri", "");
            if (!mxc.startsWith("mxc://")) return null;
            HttpURLConnection pr = (HttpURLConnection) new URL(base + "/_matrix/client/v3/profile/" + URLEncoder.encode(uid, "UTF-8") + "/avatar_url").openConnection();
            pr.setRequestMethod("PUT"); pr.setDoOutput(true);
            pr.setRequestProperty("Authorization", "Bearer " + token);
            pr.setRequestProperty("Content-Type", "application/json");
            pr.getOutputStream().write(new JSONObject().put("avatar_url", mxc).toString().getBytes("UTF-8"));
            if (pr.getResponseCode() != 200) return null;
            MXC.put(uid, mxc);
            return mxc;
        } catch (Exception e) { return null; }
    }

    /** Reads a built-in picture as PNG bytes for uploading. */
    public static byte[] builtinPng(Context c, int index) {
        Bitmap b = BitmapFactory.decodeResource(c.getResources(), PICS[index]);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, o);
        return o.toByteArray();
    }

    /** Scales a picked photo to a square 96x96 PNG, like an MSN display picture. */
    public static byte[] squarePng(byte[] raw) {
        Bitmap src = BitmapFactory.decodeByteArray(raw, 0, raw.length);
        if (src == null) return null;
        int s = Math.min(src.getWidth(), src.getHeight());
        Bitmap sq = Bitmap.createBitmap(src, (src.getWidth() - s) / 2, (src.getHeight() - s) / 2, s, s);
        Bitmap out = Bitmap.createScaledBitmap(sq, 192, 192, true);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.PNG, 100, o);
        return o.toByteArray();
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[16384]; int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        in.close();
        return o.toByteArray();
    }
}
