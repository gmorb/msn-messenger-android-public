package com.msnmessenger;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

/**
 * Public build: the APK ships a neutral launcher icon. After the user imports their own installer,
 * this offers a pinned home-screen shortcut that uses the original MSN icon taken from that installer.
 */
public final class HomeShortcut {
    static final String ID = "msn_home";
    private static final int SIZE = 192;

    private HomeShortcut() {}

    /** True when the original icon was imported and the launcher accepts pinned shortcuts. */
    public static boolean available(Context c) {
        return AssetStore.imported(c) && AssetStore.bitmap(c, "app_icon") != null
            && ShortcutManagerCompat.isRequestPinShortcutSupported(c);
    }

    /** Requests the pinned shortcut; returns false if it could not be requested. */
    public static boolean request(Context c) {
        try {
            Bitmap src = AssetStore.bitmap(c, "app_icon");
            if (src == null) return false;
            Intent open = new Intent(c, LoginActivity.class).setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(c, ID)
                .setShortLabel("MSN Messenger")
                .setLongLabel("MSN Messenger")
                .setIcon(android.os.Build.VERSION.SDK_INT >= 26
                    ? IconCompat.createWithAdaptiveBitmap(adaptive(src))
                    : IconCompat.createWithBitmap(tile(src)))
                .setIntent(open)
                .build();
            return ShortcutManagerCompat.requestPinShortcut(c, info, null);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Android 8+ adaptive icon: full-bleed white 108dp-style square; the launcher masks it (circle/squircle).
     * The buddy fills the centre half so it stays inside the 66/108 safe zone yet appears large.
     */
    static Bitmap adaptive(Bitmap src) {
        final int s = 432;
        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        cv.drawColor(0xFFFFFFFF);
        int pad = Math.round(s * 0.25f);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        cv.drawBitmap(src, new Rect(0, 0, src.getWidth(), src.getHeight()), new Rect(pad, pad, s - pad, s - pad), p);
        return out;
    }

    /** Original 48 px icon centred on a white rounded tile, matching the private build's launcher icon. */
    static Bitmap tile(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xFFFFFFFF);
        float r = SIZE * 0.22f;
        cv.drawRoundRect(new RectF(0, 0, SIZE, SIZE), r, r, bg);
        int pad = Math.round(SIZE * 0.14f);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        cv.drawBitmap(src, new Rect(0, 0, src.getWidth(), src.getHeight()), new Rect(pad, pad, SIZE - pad, SIZE - pad), p);
        return out;
    }
}
