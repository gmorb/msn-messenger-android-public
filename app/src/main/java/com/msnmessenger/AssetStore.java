package com.msnmessenger;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.widget.ImageView;
import com.msnmessenger.imp.AssetMap;
import com.msnmessenger.imp.Importer;
import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * Loads the original MSN Messenger 8 artwork and sounds.
 * Public build: files imported from the user's own installer into files/msn/ (see imp/Importer).
 * Private build: the same names are bundled as resources, used as the fallback.
 * Imported images keep the density of the resource folder they replace, so they show at the same size:
 * xxxhdpi = 4x-enlarged icons and emoticons, mdpi = spinning-buddy frames, nodpi = everything else.
 */
public final class AssetStore {
    private AssetStore() {}

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static Set<String> xxxhdpi;

    /** Folder holding the imported assets. */
    public static File dir(Context c) { return new File(c.getFilesDir(), "msn"); }

    /** True if a complete import is present. */
    public static boolean imported(Context c) { return Importer.isImported(dir(c)); }

    /** True if the app has the original assets, either imported or bundled. */
    public static boolean ready(Context c) {
        return imported(c) || bundled(c);
    }

    /** True for the private build, which ships the original assets (public build: placeholders only). */
    public static boolean bundled(Context c) {
        int id = c.getResources().getIdentifier("bundled_assets", "bool", c.getPackageName());
        try { return id != 0 && c.getResources().getBoolean(id); } catch (Throwable t) { return false; }
    }

    /** Clears cached bitmaps, e.g. after a new import. */
    public static void reset() { CACHE.evictAll(); }

    private static synchronized boolean isXxxhdpi(String name) {
        if (xxxhdpi == null) {
            Set<String> s = new HashSet<>();
            for (String[] e : AssetMap.RLE4X) s.add(e[3]);
            xxxhdpi = s;
        }
        return name.startsWith("emo_") || xxxhdpi.contains(name);
    }

    private static int densityFor(Context c, String name) {
        if (name.startsWith("signin_buddy_")) return DisplayMetrics.DENSITY_MEDIUM;
        if (isXxxhdpi(name)) return DisplayMetrics.DENSITY_XXXHIGH;
        return c.getResources().getDisplayMetrics().densityDpi; // nodpi: no scaling
    }

    /** Imported bitmap for name (without .png), or null. */
    public static Bitmap bitmap(Context c, String name) {
        Bitmap b = CACHE.get(name);
        if (b != null) return b;
        File f = new File(dir(c), name + ".png");
        if (!f.isFile()) return null;
        try { b = BitmapFactory.decodeFile(f.getAbsolutePath()); } catch (Throwable t) { b = null; }
        if (b == null) return null;
        b.setDensity(densityFor(c, name));
        CACHE.put(name, b);
        return b;
    }

    /** Drawable for an asset name: imported file first, then the bundled resource. Null if neither exists. */
    public static Drawable drawable(Context c, String name) {
        Bitmap b = bitmap(c, name);
        if (b != null) {
            BitmapDrawable d = new BitmapDrawable(c.getResources(), b);
            d.setTargetDensity(c.getResources().getDisplayMetrics());
            return d;
        }
        int id = c.getResources().getIdentifier(name, "drawable", c.getPackageName());
        if (id == 0) return null;
        try { return c.getDrawable(id); } catch (Throwable t) { return null; }
    }

    /** Sets an ImageView to the named asset; leaves it unchanged if the asset is missing. */
    public static boolean set(ImageView iv, String name) {
        if (iv == null) return false;
        Drawable d = drawable(iv.getContext(), name);
        if (d == null) return false;
        iv.setImageDrawable(d);
        return true;
    }

    /** Resource entry name for a drawable ID (e.g. R.drawable.pic_duck -> "pic_duck"), or null. */
    public static String nameOf(Context c, int resId) {
        if (resId == 0) return null;
        try { return c.getResources().getResourceEntryName(resId); } catch (Throwable t) { return null; }
    }

    /**
     * Drawable for a resource ID: the imported file with the same name first, then the resource itself.
     * In the public build the resource is only a transparent placeholder, so the import supplies the real image.
     */
    public static Drawable drawable(Context c, int resId) {
        String n = nameOf(c, resId);
        if (n != null) {
            Bitmap b = bitmap(c, n);
            if (b != null) {
                BitmapDrawable d = new BitmapDrawable(c.getResources(), b);
                d.setTargetDensity(c.getResources().getDisplayMetrics());
                return d;
            }
        }
        if (resId == 0) return null;
        try { return c.getDrawable(resId); } catch (Throwable t) { return null; }
    }

    /** Drop-in replacement for ImageView.setImageResource that prefers the imported image. */
    public static void set(ImageView iv, int resId) {
        if (iv == null) return;
        Drawable d = drawable(iv.getContext(), resId);
        if (d != null) iv.setImageDrawable(d); else iv.setImageResource(resId);
    }

    /**
     * Original spinning-buddy sign-in animation: 32 imported frames at 42 ms each (same as res/drawable/signin_anim.xml).
     * Falls back to the bundled animation-list when the frames were not imported (private build).
     */
    public static Drawable signinAnim(Context c) {
        if (imported(c)) {
            android.graphics.drawable.AnimationDrawable a = new android.graphics.drawable.AnimationDrawable();
            for (int i = 0; i < AssetMap.BUDDY_FRAMES; i++) {
                Drawable f = drawable(c, String.format(java.util.Locale.US, "signin_buddy_%02d", i));
                if (f == null) { a = null; break; }
                a.addFrame(f, 42);
            }
            if (a != null) { a.setOneShot(false); return a; }
        }
        int id = c.getResources().getIdentifier("signin_anim", "drawable", c.getPackageName());
        if (id == 0) return null;
        try { return c.getDrawable(id); } catch (Throwable t) { return null; }
    }

    /**
     * Swaps every ImageView tagged android:tag="asset:NAME" in a layout for the imported image NAME.
     * Call right after setContentView. No-op when nothing was imported (private build keeps its resources).
     */
    public static void applyTags(android.view.View root) {
        if (root == null || !imported(root.getContext())) return;
        java.util.ArrayDeque<android.view.View> q = new java.util.ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            android.view.View v = q.poll();
            Object t = v.getTag();
            if (v instanceof ImageView && t instanceof String && ((String) t).startsWith("asset:")) {
                Bitmap b = bitmap(v.getContext(), ((String) t).substring(6));
                if (b != null) {
                    BitmapDrawable d = new BitmapDrawable(v.getResources(), b);
                    d.setTargetDensity(v.getResources().getDisplayMetrics());
                    ((ImageView) v).setImageDrawable(d);
                }
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) q.add(g.getChildAt(i));
            }
        }
    }

    /** Imported sound file (.wav), or null. */
    public static File soundFile(Context c, String name) {
        File f = new File(dir(c), name + ".wav");
        return f.isFile() ? f : null;
    }

    /** Creates a MediaPlayer for a sound name (imported WAV first, then res/raw). Null if unavailable. */
    public static MediaPlayer player(Context c, String name) {
        try {
            File f = soundFile(c, name);
            if (f != null) return MediaPlayer.create(c, Uri.fromFile(f));
            int id = c.getResources().getIdentifier(name, "raw", c.getPackageName());
            return id == 0 ? null : MediaPlayer.create(c, id);
        } catch (Throwable t) { return null; }
    }

    /** Plays a sound once and releases the player afterwards. */
    public static void play(Context c, String name) {
        MediaPlayer mp = player(c, name);
        if (mp == null) return;
        mp.setOnCompletionListener(MediaPlayer::release);
        try { mp.start(); } catch (Throwable t) { mp.release(); }
    }
}
