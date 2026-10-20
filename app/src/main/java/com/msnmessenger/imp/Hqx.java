package com.msnmessenger.imp;

/**
 * Java port of FFmpeg's hq4x magnification (libavfilter/vf_hqx.c, LGPL-2.1+, n6.1.1).
 * Works on ARGB ints (same layout as FFmpeg's RGB32 on little-endian).
 * The RGB->YUV values are computed directly with the same integer arithmetic as
 * FFmpeg's 16M-entry lookup table, so results match without the 64 MB table.
 * Pure Java (no Android classes).
 */
public final class Hqx {
    private Hqx() {}

    static int rgb2yuv(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        int rg = r - g, bg = b - g;
        // FFmpeg fills the table per (rg,bg) from startg upwards, adding 1 to Y per step
        int startg = Math.max(Math.max(-bg, -rg), 0);
        int y = (299 * rg + 1000 * startg + 114 * bg) / 1000 + (g - startg);
        int u = (-169 * rg + 500 * bg) / 1000 + 128;
        int v = (500 * rg - 81 * bg) / 1000 + 128;
        return (y << 16) + (u << 8) + v;
    }

    static boolean yuvDiff(int a, int b) {
        return Math.abs((a & 0xff0000) - (b & 0xff0000)) > (48 << 16)
            || Math.abs((a & 0x00ff00) - (b & 0x00ff00)) > (7 << 8)
            || Math.abs((a & 0x0000ff) - (b & 0x0000ff)) > 6;
    }

    private static boolean wdiff(int c1, int c2) { return yuvDiff(rgb2yuv(c1), rgb2yuv(c2)); }

    static int i2(int c1, int w1, int c2, int w2, int s) {
        return ((((c1 & 0xff00ff00) >>> 8) * w1 + ((c2 & 0xff00ff00) >>> 8) * w2) << (8 - s) & 0xff00ff00)
             | ((((c1 & 0x00ff00ff)) * w1 + ((c2 & 0x00ff00ff)) * w2) >>> s & 0x00ff00ff);
    }

    static int i3(int c1, int w1, int c2, int w2, int c3, int w3, int s) {
        return ((((c1 & 0xff00ff00) >>> 8) * w1 + ((c2 & 0xff00ff00) >>> 8) * w2 + ((c3 & 0xff00ff00) >>> 8) * w3) << (8 - s) & 0xff00ff00)
             | ((((c1 & 0x00ff00ff)) * w1 + ((c2 & 0x00ff00ff)) * w2 + ((c3 & 0x00ff00ff)) * w3) >>> s & 0x00ff00ff);
    }

    private static int drop4(int z) { return z > 4 ? z - 1 : z; }

    /** Scales src (w*h ARGB) by 4; returns (4w)*(4h) ARGB. */
    public static int[] hq4x(int[] src, int width, int height) {
        int ow = width * 4;
        int[] dst = new int[ow * height * 4];
        int[] w = new int[9];
        for (int y = 0; y < height; y++) {
            int prevline = y > 0 ? -width : 0, nextline = y < height - 1 ? width : 0;
            for (int x = 0; x < width; x++) {
                int o = y * width + x;
                int prevcol = x > 0 ? -1 : 0, nextcol = x < width - 1 ? 1 : 0;
                w[0] = src[o + prevcol + prevline]; w[1] = src[o + prevline]; w[2] = src[o + prevline + nextcol];
                w[3] = src[o + prevcol];            w[4] = src[o];            w[5] = src[o + nextcol];
                w[6] = src[o + prevcol + nextline]; w[7] = src[o + nextline]; w[8] = src[o + nextline + nextcol];
                int yuv1 = rgb2yuv(w[4]);
                int pattern = 0;
                int[] idx = {0, 1, 2, 3, 5, 6, 7, 8};
                for (int i = 0; i < 8; i++) {
                    int c = w[idx[i]];
                    if (w[4] != c && yuvDiff(yuv1, rgb2yuv(c))) pattern |= 1 << i;
                }
                int base = (y * 4) * ow + x * 4;
                interp(dst, ow, base, pattern, w, 0, 1, 2, 3, new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8});
                interp(dst, ow, base + 2, pattern, w, 1, 0, 3, 2, new int[]{2, 1, 0, 5, 4, 3, 8, 7, 6});
                interp(dst, ow, base + 2 * ow, pattern, w, 2, 3, 0, 1, new int[]{6, 7, 8, 3, 4, 5, 0, 1, 2});
                interp(dst, ow, base + 2 * ow + 2, pattern, w, 3, 2, 1, 0, new int[]{8, 7, 6, 5, 4, 3, 2, 1, 0});
            }
        }
        return dst;
    }

    private static void interp(int[] dst, int ls, int base, int k, int[] w,
                               int pos00, int pos01, int pos10, int pos11, int[] p) {
        int ks = 0;
        for (int n = 0; n < 9; n++) {
            if (n == 4) continue;
            ks |= ((k >> drop4(n)) & 1) << drop4(p[n]);
        }
        final int K = ks;
        int w0 = w[p[0]], w1 = w[p[1]], w3 = w[p[3]], w4 = w[p[4]];
        boolean d31 = wdiff(w3, w1);

        boolean cond00 = (P(K, 0xbf, 0x37) || P(K, 0xdb, 0x13)) && wdiff(w1, w[p[5]]);
        boolean cond01 = (P(K, 0xdb, 0x49) || P(K, 0xef, 0x6d)) && wdiff(w[p[7]], w3);
        boolean cond02 = (P(K, 0x6f, 0x2a) || P(K, 0x5b, 0x0a) || P(K, 0xbf, 0x3a) || P(K, 0xdf, 0x5a) || P(K, 0x9f, 0x8a)
                || P(K, 0xcf, 0x8a) || P(K, 0xef, 0x4e) || P(K, 0x3f, 0x0e) || P(K, 0xfb, 0x5a) || P(K, 0xbb, 0x8a)
                || P(K, 0x7f, 0x5a) || P(K, 0xaf, 0x8a) || P(K, 0xeb, 0x8a)) && d31;
        boolean cond03 = P(K, 0xdb, 0x49) || P(K, 0xef, 0x6d);
        boolean cond04 = P(K, 0xbf, 0x37) || P(K, 0xdb, 0x13);
        boolean cond05 = P(K, 0x1b, 0x03) || P(K, 0x4f, 0x43) || P(K, 0x8b, 0x83) || P(K, 0x6b, 0x43);
        boolean cond06 = P(K, 0x4b, 0x09) || P(K, 0x8b, 0x89) || P(K, 0x1f, 0x19) || P(K, 0x3b, 0x19);
        boolean cond07 = P(K, 0x0b, 0x08) || P(K, 0xf9, 0x68) || P(K, 0xf3, 0x62) || P(K, 0x6d, 0x6c) || P(K, 0x67, 0x66)
                || P(K, 0x3d, 0x3c) || P(K, 0x37, 0x36) || P(K, 0xf9, 0xf8) || P(K, 0xdd, 0xdc) || P(K, 0xf3, 0xf2)
                || P(K, 0xd7, 0xd6) || P(K, 0xdd, 0x1c) || P(K, 0xd7, 0x16) || P(K, 0x0b, 0x02);
        boolean cond08 = (P(K, 0x0f, 0x0b) || P(K, 0x2b, 0x0b) || P(K, 0xfe, 0x4a) || P(K, 0xfe, 0x1a)) && d31;
        boolean cond09 = P(K, 0x2f, 0x2f);
        boolean cond10 = P(K, 0x0a, 0x00);
        boolean cond11 = P(K, 0x0b, 0x09);
        boolean cond12 = P(K, 0x7e, 0x2a) || P(K, 0xef, 0xab);
        boolean cond13 = P(K, 0xbf, 0x8f) || P(K, 0x7e, 0x0e);
        boolean cond14 = P(K, 0x4f, 0x4b) || P(K, 0x9f, 0x1b) || P(K, 0x2f, 0x0b) || P(K, 0xbe, 0x0a) || P(K, 0xee, 0x0a)
                || P(K, 0x7e, 0x0a) || P(K, 0xeb, 0x4b) || P(K, 0x3b, 0x1b);
        boolean cond15 = P(K, 0x0b, 0x03);

        int v00, v01, v10, v11;
        if (cond00) v00 = i2(w4, 5, w3, 3, 3);
        else if (cond01) v00 = i2(w4, 5, w1, 3, 3);
        else if ((P(K, 0x0b, 0x0b) || P(K, 0xfe, 0x4a) || P(K, 0xfe, 0x1a)) && d31) v00 = w4;
        else if (cond02) v00 = i2(w4, 5, w0, 3, 3);
        else if (cond03) v00 = i2(w4, 3, w3, 1, 2);
        else if (cond04) v00 = i2(w4, 3, w1, 1, 2);
        else if (cond05) v00 = i2(w4, 5, w3, 3, 3);
        else if (cond06) v00 = i2(w4, 5, w1, 3, 3);
        else if (P(K, 0x0f, 0x0b) || P(K, 0x5e, 0x0a) || P(K, 0x2b, 0x0b) || P(K, 0xbe, 0x0a) || P(K, 0x7a, 0x0a) || P(K, 0xee, 0x0a))
            v00 = i2(w1, 1, w3, 1, 1);
        else if (cond07) v00 = i2(w4, 5, w0, 3, 3);
        else v00 = i3(w4, 2, w1, 1, w3, 1, 2);

        if (cond00) v01 = i2(w4, 7, w3, 1, 3);
        else if (cond08) v01 = w4;
        else if (cond02) v01 = i2(w4, 3, w0, 1, 2);
        else if (cond09) v01 = w4;
        else if (cond10) v01 = i3(w4, 5, w1, 2, w3, 1, 3);
        else if (P(K, 0x0b, 0x08)) v01 = i3(w4, 5, w1, 2, w0, 1, 3);
        else if (cond11) v01 = i2(w4, 5, w1, 3, 3);
        else if (cond04) v01 = i2(w1, 3, w4, 1, 2);
        else if (cond12) v01 = i3(w1, 2, w4, 1, w3, 1, 2);
        else if (cond13) v01 = i2(w1, 5, w3, 3, 3);
        else if (cond05) v01 = i2(w4, 7, w3, 1, 3);
        else if (P(K, 0xf3, 0x62) || P(K, 0x67, 0x66) || P(K, 0x37, 0x36) || P(K, 0xf3, 0xf2) || P(K, 0xd7, 0xd6)
                || P(K, 0xd7, 0x16) || P(K, 0x0b, 0x02)) v01 = i2(w4, 3, w0, 1, 2);
        else if (cond14) v01 = i2(w1, 1, w4, 1, 1);
        else v01 = i2(w4, 3, w1, 1, 2);

        if (cond01) v10 = i2(w4, 7, w1, 1, 3);
        else if (cond08) v10 = w4;
        else if (cond02) v10 = i2(w4, 3, w0, 1, 2);
        else if (cond09) v10 = w4;
        else if (cond10) v10 = i3(w4, 5, w3, 2, w1, 1, 3);
        else if (P(K, 0x0b, 0x02)) v10 = i3(w4, 5, w3, 2, w0, 1, 3);
        else if (cond15) v10 = i2(w4, 5, w3, 3, 3);
        else if (cond03) v10 = i2(w3, 3, w4, 1, 2);
        else if (cond13) v10 = i3(w3, 2, w4, 1, w1, 1, 2);
        else if (cond12) v10 = i2(w3, 5, w1, 3, 3);
        else if (cond06) v10 = i2(w4, 7, w1, 1, 3);
        else if (P(K, 0x0b, 0x08) || P(K, 0xf9, 0x68) || P(K, 0x6d, 0x6c) || P(K, 0x3d, 0x3c) || P(K, 0xf9, 0xf8)
                || P(K, 0xdd, 0xdc) || P(K, 0xdd, 0x1c)) v10 = i2(w4, 3, w0, 1, 2);
        else if (cond14) v10 = i2(w3, 1, w4, 1, 1);
        else v10 = i2(w4, 3, w3, 1, 2);

        if ((P(K, 0x7f, 0x2b) || P(K, 0xef, 0xab) || P(K, 0xbf, 0x8f) || P(K, 0x7f, 0x0f)) && d31) v11 = w4;
        else if (cond02) v11 = i2(w4, 7, w0, 1, 3);
        else if (cond15) v11 = i2(w4, 7, w3, 1, 3);
        else if (cond11) v11 = i2(w4, 7, w1, 1, 3);
        else if (P(K, 0x0a, 0x00) || P(K, 0x7e, 0x2a) || P(K, 0xef, 0xab) || P(K, 0xbf, 0x8f) || P(K, 0x7e, 0x0e))
            v11 = i3(w4, 6, w3, 1, w1, 1, 3);
        else if (cond07) v11 = i2(w4, 7, w0, 1, 3);
        else v11 = w4;

        dst[base + ls * (pos00 >> 1) + (pos00 & 1)] = v00;
        dst[base + ls * (pos01 >> 1) + (pos01 & 1)] = v01;
        dst[base + ls * (pos10 >> 1) + (pos10 & 1)] = v10;
        dst[base + ls * (pos11 >> 1) + (pos11 & 1)] = v11;
    }

    private static boolean P(int k, int m, int r) { return (k & m) == r; }
}
