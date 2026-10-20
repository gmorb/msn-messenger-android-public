package com.msnmessenger.imp;

import java.util.ArrayList;
import java.util.List;

/**
 * Decoder for MSN Messenger 8.0 DirectUI "RLE" skin images (msgsres.dll resource types 4000-4006).
 * Format reconstructed from msncore.dll; Java port of msn_skin/rle_decode.py (8.0 layout).
 * Pure Java (no Android classes) so it can be tested on a desktop JVM.
 */
public final class RleDecoder {
    private static final int[] COUNT_TABLE = {1, 9, 3, 3, 6, 6, 6, 6};

    private RleDecoder() {}

    /** Decoded image: ARGB pixels, row-major, top row first. */
    public static final class Img {
        public final int w, h;
        public final int[] argb;
        Img(int w, int h) { this.w = w; this.h = h; this.argb = new int[w * h]; }
    }

    /** LSB-first bit reader with a 16-bit window, matching the original. */
    private static final class BS {
        final byte[] b; int p, bit;
        BS(byte[] b, int p) { this.b = b; this.p = p; }
        int get(int n) {
            int lo = p >= 0 && p < b.length ? b[p] & 255 : 0;
            int hi = p + 1 >= 0 && p + 1 < b.length ? b[p + 1] & 255 : 0;
            int v = ((lo | (hi << 8)) >>> bit) & ((1 << n) - 1);
            bit += n;
            if (bit >= 8) { p++; bit &= 7; }
            return v;
        }
    }

    /** Growable int list. */
    private static final class IntBuf {
        int[] a = new int[64]; int n;
        void add(int v) { if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2); a[n++] = v; }
        int last() { return a[n - 1]; }
        void clear() { n = 0; }
    }

    /** 2-bit type, N-bit length+1. 0 = fill, 3 = literal, 2 = delta minus, 1 = delta plus. Appends to out. */
    private static void colorRun(BS bs, int N, int prev, int vb, IntBuf out) {
        int sh = 8 - vb;
        int t = bs.get(2), L = bs.get(N) + 1;
        if (t == 0) {
            int v = (bs.get(vb) << sh) & 0xff;
            for (int i = 0; i < L; i++) out.add(v);
        } else if (t == 3) {
            for (int i = 0; i < L; i++) out.add((bs.get(vb) << sh) & 0xff);
        } else {
            int nb = bs.get(3) + 1, p = prev;
            for (int i = 0; i < L; i++) {
                int d = (bs.get(nb) << sh) & 0xff;
                p = t == 2 ? (p - d) & 0xff : (p + d) & 0xff;
                out.add(p);
            }
        }
    }

    private static long le(byte[] b, int o, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) { int k = o + i; v |= (long) (k >= 0 && k < b.length ? b[k] & 255 : 0) << (8 * i); }
        return v;
    }

    static Img decodeSub(byte[] b, int o) {
        long dw = le(b, o, 4);
        int w = (int) (dw & 0xfff), h = (int) ((dw >> 16) & 0xfff);
        int es = (int) ((dw >> 28) & 3) + 1;
        boolean hasA = ((dw >> 15) & 1) != 0;
        int mono = (int) ((dw >> 12) & 3);
        int nch = mono != 0 ? 1 : 3, vb = mono != 0 ? 8 : 5;
        int N = Math.max(1, w - 1 > 0 ? 32 - Integer.numberOfLeadingZeros(w - 1) : 0);
        int base = o + 4;
        long mask = es >= 4 ? 0xffffffffL : (1L << (8 * es)) - 1;
        Img img = new Img(w, h);
        IntBuf[] C = new IntBuf[nch];
        for (int c = 0; c < nch; c++) C[c] = new IntBuf();
        IntBuf al = new IntBuf();
        int[] prev = new int[nch];
        int[] v = new int[3];
        for (int y = 0; y < h; y++) {
            int p;
            if (y == 0) p = base + (h - 1) * es;
            else { int e = base + (y - 1) * es; p = (int) (e + (le(b, e, 4) & mask)); }
            BS bs = new BS(b, p);
            for (int c = 0; c < nch; c++) { C[c].clear(); prev[c] = 0; }
            int pa = 0, x = 0, cx = 0;
            while (x < w) {
                al.clear();
                if (hasA) {
                    int t = bs.get(2);
                    if (t == 1) { colorRun(bs, N, pa, 5, al); pa = al.last(); }
                    else {
                        int L = bs.get(N) + 1;
                        if (t == 0) { x += L; continue; }
                        for (int i = 0; i < L; i++) al.add(255);
                    }
                } else {
                    for (int i = x; i < w; i++) al.add(255);
                }
                for (int i = 0; i < al.n; i++) {
                    if (x >= w) break;
                    int a = al.a[i];
                    for (int c = 0; c < nch; c++) {
                        while (C[c].n <= cx) { int before = C[c].n; colorRun(bs, N, prev[c], vb, C[c]); if (C[c].n > before) prev[c] = C[c].last(); }
                        v[c] = C[c].a[cx];
                    }
                    if (nch == 1) { v[1] = v[0]; v[2] = v[0]; }
                    img.argb[y * w + x] = (Math.min(255, a) << 24) | (v[0] << 16) | (v[1] << 8) | v[2];
                    x++; cx++;
                }
            }
        }
        return img;
    }

    /** Decodes all sub-images of one RLE resource. Entries are null where a sub-image fails. */
    public static List<Img> decode(byte[] b) {
        List<Img> subs = new ArrayList<>();
        if (b == null || b.length < 5 || b[0] != 'R' || b[1] != 'L' || b[2] != 'E') return subs;
        int n = ((b[4] >> 3) & 3) + 1;
        int cnt = COUNT_TABLE[(b[4] & 255) >> 5] * Math.max(1, b[3] & 0x7f);
        int o = 5;
        for (int k = 0; k < cnt; k++) {
            if (o + n > b.length) break;
            int L = (int) le(b, o, n); o += n;
            try { subs.add(decodeSub(b, o)); }
            catch (RuntimeException | OutOfMemoryError e) { subs.add(null); }
            o += L;
        }
        return subs;
    }
}
