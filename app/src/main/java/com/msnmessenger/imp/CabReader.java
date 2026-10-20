package com.msnmessenger.imp;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Minimal Microsoft Cabinet (MSCF) reader supporting stored and MSZip folders.
 * MSZip = raw deflate blocks prefixed with "CK"; each block uses the previous block's output as dictionary.
 * Streams the wanted files to disk, so the whole folder never has to fit in memory.
 * Pure Java (no Android classes).
 */
public final class CabReader implements Closeable {
    public static final class FileEntry {
        public final String name;
        public final long size, offset;
        public final int folder;
        FileEntry(String name, long size, long offset, int folder) { this.name = name; this.size = size; this.offset = offset; this.folder = folder; }
    }

    private static final class Folder { long dataStart; int nData, comp; }

    private final RandomAccessFile f;
    private final long base;
    private final List<Folder> folders = new ArrayList<>();
    private final List<FileEntry> files = new ArrayList<>();
    private int cbData;

    public CabReader(RandomAccessFile f, long base) throws IOException {
        this.f = f; this.base = base;
        byte[] h = new byte[36];
        f.seek(base); f.readFully(h);
        if (h[0] != 'M' || h[1] != 'S' || h[2] != 'C' || h[3] != 'F') throw new IOException("not a cabinet");
        long coffFiles = u32(h, 16);
        int nFolders = u16(h, 26), nFiles = u16(h, 28), flags = u16(h, 30);
        long p = base + 36;
        int cbFolder = 0;
        if ((flags & 4) != 0) {
            byte[] r = new byte[4]; f.seek(p); f.readFully(r);
            int cbHeader = u16(r, 0); cbFolder = r[2] & 255; cbData = r[3] & 255;
            p += 4 + cbHeader;
        }
        if ((flags & 1) != 0) { p = skipSz(skipSz(p)); }
        if ((flags & 2) != 0) { p = skipSz(skipSz(p)); }
        byte[] fb = new byte[8];
        for (int i = 0; i < nFolders; i++) {
            f.seek(p); f.readFully(fb);
            Folder fo = new Folder();
            fo.dataStart = base + u32(fb, 0); fo.nData = u16(fb, 4); fo.comp = u16(fb, 6) & 0x0f;
            folders.add(fo);
            p += 8 + cbFolder;
        }
        p = base + coffFiles;
        byte[] e = new byte[16];
        for (int i = 0; i < nFiles; i++) {
            f.seek(p); f.readFully(e);
            StringBuilder s = new StringBuilder();
            int c; while ((c = f.read()) > 0 && s.length() < 512) s.append((char) c);
            files.add(new FileEntry(s.toString(), u32(e, 0), u32(e, 4), u16(e, 8)));
            p += 16 + s.length() + 1;
        }
    }

    public List<FileEntry> files() { return files; }

    /**
     * Extracts the named files (case-insensitive, as stored in the cabinet) into outDir.
     * Returns a map of stored name to the written file.
     */
    public Map<String, File> extract(List<String> wanted, File outDir) throws IOException {
        Map<String, File> done = new HashMap<>();
        outDir.mkdirs();
        for (int fi = 0; fi < folders.size(); fi++) {
            List<FileEntry> want = new ArrayList<>();
            for (FileEntry fe : files) if (fe.folder == fi) for (String w : wanted) if (fe.name.equalsIgnoreCase(w)) want.add(fe);
            if (want.isEmpty()) continue;
            long end = 0;
            for (FileEntry fe : want) end = Math.max(end, fe.offset + fe.size);
            OutputStream[] outs = new OutputStream[want.size()];
            try {
                for (int i = 0; i < want.size(); i++) {
                    File o = new File(outDir, want.get(i).name);
                    outs[i] = new FileOutputStream(o);
                    done.put(want.get(i).name, o);
                }
                decodeFolder(folders.get(fi), end, want, outs);
            } finally {
                for (OutputStream o : outs) if (o != null) try { o.close(); } catch (IOException ignored) {}
            }
            for (FileEntry fe : want) {
                File o = done.get(fe.name);
                if (o.length() != fe.size) throw new IOException("short file " + fe.name);
            }
        }
        return done;
    }

    private void decodeFolder(Folder fo, long end, List<FileEntry> want, OutputStream[] outs) throws IOException {
        if (fo.comp != 0 && fo.comp != 1) throw new IOException("unsupported cabinet compression " + fo.comp);
        Inflater inf = new Inflater(true);
        byte[] window = new byte[0];
        byte[] hdr = new byte[8];
        byte[] out = new byte[65536];
        long pos = 0, p = fo.dataStart;
        try {
            for (int b = 0; b < fo.nData && pos < end; b++) {
                f.seek(p); f.readFully(hdr);
                int cb = u16(hdr, 4), cbu = u16(hdr, 6);
                p += 8 + cbData;
                byte[] in = new byte[cb];
                f.seek(p); f.readFully(in);
                p += cb;
                int n;
                if (fo.comp == 0) { System.arraycopy(in, 0, out, 0, cb); n = cb; }
                else {
                    if (cb < 2 || in[0] != 'C' || in[1] != 'K') throw new IOException("bad MSZip block");
                    inf.reset();
                    if (window.length > 0) inf.setDictionary(window);
                    inf.setInput(in, 2, cb - 2);
                    n = 0;
                    try {
                        while (n < cbu && !inf.finished()) {
                            int k = inf.inflate(out, n, out.length - n);
                            if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                            n += k;
                        }
                    } catch (DataFormatException e) { throw new IOException("MSZip: " + e.getMessage()); }
                    if (n != cbu) throw new IOException("MSZip block size mismatch");
                    window = java.util.Arrays.copyOf(out, n);
                }
                for (int i = 0; i < want.size(); i++) {
                    FileEntry fe = want.get(i);
                    long s = Math.max(pos, fe.offset), e = Math.min(pos + n, fe.offset + fe.size);
                    if (s < e) outs[i].write(out, (int) (s - pos), (int) (e - s));
                }
                pos += n;
            }
        } finally { inf.end(); }
        if (pos < end) throw new IOException("cabinet folder ended early");
    }

    private long skipSz(long p) throws IOException {
        f.seek(p); int c; long q = p;
        do { c = f.read(); q++; } while (c > 0);
        return q;
    }

    private static int u16(byte[] b, int o) { return (b[o] & 255) | (b[o + 1] & 255) << 8; }
    private static long u32(byte[] b, int o) { return ((b[o] & 255) | (b[o + 1] & 255) << 8 | (b[o + 2] & 255) << 16 | (long) (b[o + 3] & 255) << 24); }

    @Override public void close() throws IOException { f.close(); }
}
