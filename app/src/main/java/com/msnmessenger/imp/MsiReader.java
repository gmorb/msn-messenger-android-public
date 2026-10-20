package com.msnmessenger.imp;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal reader for Compound File Binary (OLE2) files such as Windows Installer .msi packages.
 * Lists top-level streams with MSI name decoding and copies a stream to an OutputStream.
 * Reads sectors on demand from disk, so large installers do not need to fit in memory.
 * Pure Java (no Android classes).
 */
public final class MsiReader implements Closeable {
    private static final int FREESECT = 0xFFFFFFFF, ENDOFCHAIN = 0xFFFFFFFE;

    public static final class Entry {
        public final String name;   // decoded MSI name
        public final String rawName;
        public final int type, start;
        public final long size;
        Entry(String raw, int type, int start, long size) {
            this.rawName = raw; this.name = decodeMsiName(raw); this.type = type; this.start = start; this.size = size;
        }
    }

    private final RandomAccessFile f;
    private final long base;          // offset of the CFB header inside the file (0 for a plain .msi)
    private final int secSize, miniSize, miniCutoff;
    private final int[] fat;
    private int[] miniFat = new int[0];
    private final List<Entry> entries = new ArrayList<>();
    private Entry root;

    public MsiReader(RandomAccessFile f, long base) throws IOException {
        this.f = f; this.base = base;
        byte[] h = new byte[512];
        readAt(base, h, 0, 512);
        if ((h[0] & 255) != 0xD0 || (h[1] & 255) != 0xCF || (h[2] & 255) != 0x11 || (h[3] & 255) != 0xE0)
            throw new IOException("not a compound file");
        secSize = 1 << u16(h, 30);
        miniSize = 1 << u16(h, 32);
        int nFat = s32(h, 44), dirStart = s32(h, 48);
        miniCutoff = s32(h, 56);
        int miniFatStart = s32(h, 60), nMiniFat = s32(h, 64);
        int difatStart = s32(h, 68), nDifat = s32(h, 72);
        if (secSize < 512 || secSize > 65536 || nFat < 0 || nFat > 1 << 20) throw new IOException("bad header");

        // DIFAT: 109 entries in the header, then a chain of DIFAT sectors
        int[] fatSecs = new int[nFat];
        int k = 0;
        for (int i = 0; i < 109 && k < nFat; i++) fatSecs[k++] = s32(h, 76 + 4 * i);
        int per = secSize / 4 - 1;
        byte[] sec = new byte[secSize];
        int ds = difatStart;
        for (int d = 0; d < nDifat && k < nFat && ds != ENDOFCHAIN && ds != FREESECT; d++) {
            readSector(ds, sec);
            for (int i = 0; i < per && k < nFat; i++) fatSecs[k++] = s32(sec, 4 * i);
            ds = s32(sec, 4 * per);
        }
        // FAT
        int epp = secSize / 4;
        fat = new int[nFat * epp];
        for (int i = 0; i < nFat; i++) {
            readSector(fatSecs[i], sec);
            for (int j = 0; j < epp; j++) fat[i * epp + j] = s32(sec, 4 * j);
        }
        // Directory
        byte[] dir = readChain(dirStart, -1);
        for (int o = 0; o + 128 <= dir.length; o += 128) {
            int nl = u16(dir, o + 64);
            int type = dir[o + 66] & 255;
            if (type == 0 || nl < 2 || nl > 64) continue;
            StringBuilder s = new StringBuilder();
            for (int c = 0; c < nl / 2 - 1; c++) s.append((char) u16(dir, o + 2 * c));
            long size = s32(dir, o + 120) & 0xffffffffL;
            if (secSize == 512) size &= 0xffffffffL;
            Entry e = new Entry(s.toString(), type, s32(dir, o + 116), size);
            if (type == 5) root = e; else entries.add(e);
        }
        if (nMiniFat > 0 && miniFatStart != ENDOFCHAIN) {
            byte[] mf = readChain(miniFatStart, -1);
            miniFat = new int[mf.length / 4];
            for (int i = 0; i < miniFat.length; i++) miniFat[i] = s32(mf, 4 * i);
        }
    }

    public List<Entry> entries() { return entries; }

    /** Finds a stream by its decoded MSI name (case-insensitive), e.g. "MsgrCore.cab". */
    public Entry find(String name) {
        for (Entry e : entries) if (e.type == 2 && e.name.equalsIgnoreCase(name)) return e;
        return null;
    }

    /** Copies a stream's bytes to out. */
    public void copy(Entry e, OutputStream out) throws IOException {
        if (e.size < miniCutoff) { out.write(readMini(e.start, (int) e.size)); return; }
        byte[] sec = new byte[secSize];
        long left = e.size; int s = e.start, guard = fat.length + 1;
        while (left > 0 && s >= 0 && s < fat.length && guard-- > 0) {
            readSector(s, sec);
            int n = (int) Math.min(left, secSize);
            out.write(sec, 0, n); left -= n;
            s = fat[s];
        }
        if (left > 0) throw new IOException("truncated stream " + e.name);
    }

    private byte[] readMini(int start, int size) throws IOException {
        if (root == null) throw new IOException("no root entry");
        byte[] ministream = readChain(root.start, root.size);
        byte[] out = new byte[size];
        int s = start, o = 0, guard = miniFat.length + 1;
        while (o < size && s >= 0 && s < miniFat.length && guard-- > 0) {
            int n = Math.min(miniSize, size - o);
            int src = s * miniSize;
            if (src + n > ministream.length) throw new IOException("bad mini stream");
            System.arraycopy(ministream, src, out, o, n); o += n;
            s = miniFat[s];
        }
        if (o < size) throw new IOException("truncated mini stream");
        return out;
    }

    private byte[] readChain(int start, long size) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] sec = new byte[secSize];
        int s = start, guard = fat.length + 1;
        while (s >= 0 && s < fat.length && guard-- > 0) {
            readSector(s, sec); b.write(sec, 0, secSize);
            if (size >= 0 && b.size() >= size) break;
            s = fat[s];
        }
        byte[] r = b.toByteArray();
        return size >= 0 && r.length > size ? java.util.Arrays.copyOf(r, (int) size) : r;
    }

    private void readSector(int s, byte[] dst) throws IOException {
        readAt(base + (long) (s + 1) * secSize, dst, 0, secSize);
    }

    private void readAt(long pos, byte[] dst, int off, int len) throws IOException {
        f.seek(pos);
        f.readFully(dst, off, len);
    }

    /** Decodes Windows Installer packed stream names (two base-64 chars per UTF-16 unit). */
    public static String decodeMsiName(String raw) {
        final String T = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz._";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            int c = raw.charAt(i);
            if (c >= 0x3800 && c < 0x4800) { c -= 0x3800; s.append(T.charAt(c & 0x3f)).append(T.charAt((c >> 6) & 0x3f)); }
            else if (c >= 0x4800 && c < 0x4840) s.append(T.charAt(c - 0x4800));
            else if (c == 0x4840) s.append('!');
            else s.append((char) c);
        }
        return s.toString();
    }

    private static int u16(byte[] b, int o) { return (b[o] & 255) | (b[o + 1] & 255) << 8; }
    private static int s32(byte[] b, int o) { return (b[o] & 255) | (b[o + 1] & 255) << 8 | (b[o + 2] & 255) << 16 | (b[o + 3] & 255) << 24; }

    @Override public void close() throws IOException { f.close(); }
}
