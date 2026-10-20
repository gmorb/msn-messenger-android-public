package com.msnmessenger.imp;

/**
 * Converts a Windows icon group (RT_GROUP_ICON, type 14) to pixels.
 * Picks the entry with the requested size and the highest colour depth, then decodes
 * the RT_ICON (type 3) data: 32-bit BGRA DIBs, paletted 1/4/8-bit DIBs with an AND mask,
 * or embedded PNG (returned raw so the caller can decode it with the platform).
 * Pure Java (no Android classes).
 */
public final class IconDecoder {
    private IconDecoder() {}

    public static final class Result {
        public final int width, height;
        public final int[] argb;   // null when png != null
        public final byte[] png;   // raw PNG entry, or null
        Result(int w, int h, int[] argb, byte[] png) { this.width = w; this.height = h; this.argb = argb; this.png = png; }
    }

    /** Returns the best entry of size wanted x wanted (or the largest available if none matches), or null. */
    public static Result decode(PeResources pe, String groupId, int wanted) {
        byte[] g = pe.get("14", groupId);
        if (g == null || g.length < 6) return null;
        int count = u16(g, 4);
        int bestId = -1, bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < count && 6 + 14 * (i + 1) <= g.length; i++) {
            int o = 6 + 14 * i;
            int w = g[o] & 255; if (w == 0) w = 256;
            int bpp = u16(g, o + 6);
            int id = u16(g, o + 12);
            int score = (w == wanted ? 100000 : -Math.abs(w - wanted) * 100) + bpp;
            if (score > bestScore) { bestScore = score; bestId = id; }
        }
        if (bestId < 0) return null;
        byte[] d = pe.get(3, bestId);
        return d == null ? null : decodeEntry(d);
    }

    static Result decodeEntry(byte[] d) {
        if (d.length > 8 && (d[0] & 255) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            int w = s32be(d, 16), h = s32be(d, 20);
            return new Result(w, h, null, d);
        }
        int hs = s32(d, 0);
        int w = s32(d, 4), h = s32(d, 8) / 2;
        int bpp = u16(d, 14);
        int clrUsed = s32(d, 32);
        if (w <= 0 || h <= 0 || w > 512 || h > 512) return null;
        int palN = bpp <= 8 ? (clrUsed != 0 ? clrUsed : 1 << bpp) : 0;
        int pal = hs;
        int xor = pal + 4 * palN;
        int xorStride = ((w * bpp + 31) / 32) * 4;
        int and = xor + xorStride * h;
        int andStride = ((w + 31) / 32) * 4;
        boolean hasAnd = and + andStride * h <= d.length;
        int[] out = new int[w * h];
        boolean anyAlpha = false;
        for (int y = 0; y < h; y++) {
            int row = xor + (h - 1 - y) * xorStride;
            for (int x = 0; x < w; x++) {
                int c;
                if (bpp == 32) {
                    int p = row + 4 * x;
                    c = (d[p + 3] & 255) << 24 | (d[p + 2] & 255) << 16 | (d[p + 1] & 255) << 8 | (d[p] & 255);
                    if ((c >>> 24) != 0) anyAlpha = true;
                } else if (bpp == 24) {
                    int p = row + 3 * x;
                    c = 0xff000000 | (d[p + 2] & 255) << 16 | (d[p + 1] & 255) << 8 | (d[p] & 255);
                } else {
                    int bit = x * bpp;
                    int v = ((d[row + bit / 8] & 255) >> (8 - bpp - bit % 8)) & ((1 << bpp) - 1);
                    int q = pal + 4 * v;
                    c = 0xff000000 | (d[q + 2] & 255) << 16 | (d[q + 1] & 255) << 8 | (d[q] & 255);
                }
                out[y * w + x] = c;
            }
        }
        // AND mask: used for <32 bpp, and for 32 bpp icons that carry no alpha
        if (hasAnd && (bpp < 32 || !anyAlpha)) {
            for (int y = 0; y < h; y++) {
                int row = and + (h - 1 - y) * andStride;
                for (int x = 0; x < w; x++) {
                    boolean transparent = ((d[row + x / 8] >> (7 - x % 8)) & 1) != 0;
                    int i = y * w + x;
                    out[i] = transparent ? 0 : (out[i] | 0xff000000);
                }
            }
        }
        return new Result(w, h, out, null);
    }

    private static int u16(byte[] b, int o) { return (b[o] & 255) | (b[o + 1] & 255) << 8; }
    private static int s32(byte[] b, int o) { return (b[o] & 255) | (b[o + 1] & 255) << 8 | (b[o + 2] & 255) << 16 | (b[o + 3] & 255) << 24; }
    private static int s32be(byte[] b, int o) { return (b[o] & 255) << 24 | (b[o + 1] & 255) << 16 | (b[o + 2] & 255) << 8 | (b[o + 3] & 255); }
}
