package com.msnmessenger.imp;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal Windows PE resource reader (PE32 and PE32+).
 * Resources are keyed by type and name: numeric IDs as decimal strings ("4000", "14"),
 * named entries as their upper-case name. Only the first language of each resource is kept.
 * Pure Java (no Android classes).
 */
public final class PeResources {
    private final byte[] b;
    private final int[][] sections; // {virtualAddress, virtualSize, rawPtr, rawSize}
    private final Map<String, Map<String, int[]>> res = new LinkedHashMap<>(); // type -> name -> {fileOffset, size}

    public PeResources(File file) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            long len = f.length();
            if (len > 64L << 20) throw new IOException("file too large");
            b = new byte[(int) len];
            f.readFully(b);
        }
        if (b.length < 64 || b[0] != 'M' || b[1] != 'Z') throw new IOException("not a PE file");
        int pe = s32(0x3c);
        if (pe < 0 || pe + 24 > b.length || b[pe] != 'P' || b[pe + 1] != 'E' || b[pe + 2] != 0 || b[pe + 3] != 0)
            throw new IOException("no PE header");
        int nSec = u16(pe + 6), optSize = u16(pe + 20);
        int opt = pe + 24;
        int magic = u16(opt);
        int dd = opt + (magic == 0x20b ? 112 : 96);
        int resRva = s32(dd + 2 * 8);
        int secTab = opt + optSize;
        sections = new int[nSec][];
        for (int i = 0; i < nSec; i++) {
            int s = secTab + 40 * i;
            if (s + 40 > b.length) throw new IOException("bad section table");
            sections[i] = new int[]{s32(s + 12), s32(s + 8), s32(s + 20), s32(s + 16)};
        }
        if (resRva == 0) return;
        int root = rvaToOff(resRva);
        if (root < 0) throw new IOException("resource section not found");
        walkTypes(root);
    }

    private void walkTypes(int root) {
        for (int[] t : dirEntries(root, root)) {
            String type = entryName(root, t[0]);
            if ((t[1] & 0x80000000) == 0) continue;
            int nameDir = root + (t[1] & 0x7fffffff);
            Map<String, int[]> names = new LinkedHashMap<>();
            for (int[] n : dirEntries(root, nameDir)) {
                String name = entryName(root, n[0]);
                int leaf = n[1];
                int guard = 4;
                // descend through the language level (take the first language)
                while ((leaf & 0x80000000) != 0 && guard-- > 0) {
                    List<int[]> langs = dirEntries(root, root + (leaf & 0x7fffffff));
                    if (langs.isEmpty()) { leaf = -1; break; }
                    leaf = langs.get(0)[1];
                }
                if (leaf == -1 || (leaf & 0x80000000) != 0) continue;
                int de = root + leaf;
                if (de + 16 > b.length) continue;
                int off = rvaToOff(s32(de)), size = s32(de + 4);
                if (off < 0 || size < 0 || off + (long) size > b.length) continue;
                names.put(name, new int[]{off, size});
            }
            res.put(type, names);
        }
    }

    private List<int[]> dirEntries(int root, int dir) {
        List<int[]> l = new ArrayList<>();
        if (dir < 0 || dir + 16 > b.length) return l;
        int n = u16(dir + 12) + u16(dir + 14);
        for (int i = 0; i < n && i < 65536; i++) {
            int e = dir + 16 + 8 * i;
            if (e + 8 > b.length) break;
            l.add(new int[]{s32(e), s32(e + 4)});
        }
        return l;
    }

    private String entryName(int root, int id) {
        if ((id & 0x80000000) == 0) return Integer.toString(id & 0xffff);
        int p = root + (id & 0x7fffffff);
        if (p + 2 > b.length) return "?";
        int n = u16(p);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n && p + 4 + 2 * i <= b.length; i++) s.append((char) u16(p + 2 + 2 * i));
        return s.toString().toUpperCase(java.util.Locale.US);
    }

    private int rvaToOff(int rva) {
        for (int[] s : sections) {
            int vs = Math.max(s[1], s[3]);
            if (rva >= s[0] && rva < s[0] + vs) {
                int off = rva - s[0] + s[2];
                return off >= 0 && off < b.length ? off : -1;
            }
        }
        return -1;
    }

    /** Resource types present, e.g. "3", "14", "4000". */
    public List<String> types() { return new ArrayList<>(res.keySet()); }

    /** Resource names of one type. */
    public List<String> names(String type) {
        Map<String, int[]> m = res.get(type.toUpperCase(java.util.Locale.US));
        return m == null ? new ArrayList<String>() : new ArrayList<>(m.keySet());
    }

    /** Returns the resource bytes, or null if absent. */
    public byte[] get(String type, String name) {
        Map<String, int[]> m = res.get(type.toUpperCase(java.util.Locale.US));
        if (m == null) return null;
        int[] r = m.get(name.toUpperCase(java.util.Locale.US));
        return r == null ? null : java.util.Arrays.copyOfRange(b, r[0], r[0] + r[1]);
    }

    public byte[] get(int type, int id) { return get(Integer.toString(type), Integer.toString(id)); }

    private int u16(int o) { return o + 1 < b.length && o >= 0 ? (b[o] & 255) | (b[o + 1] & 255) << 8 : 0; }
    private int s32(int o) { return o + 3 < b.length && o >= 0 ? (b[o] & 255) | (b[o + 1] & 255) << 8 | (b[o + 2] & 255) << 16 | (b[o + 3] & 255) << 24 : 0; }
}
