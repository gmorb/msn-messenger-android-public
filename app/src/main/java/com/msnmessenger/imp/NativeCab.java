package com.msnmessenger.imp;

import java.io.File;

/**
 * Extracts one file (e.g. MsnMsgs.msi) from the cabinet embedded in the user's own
 * MSN Messenger 8 installer (.exe self-extractor with an LZX cabinet, or a plain .cab).
 * Backed by libmsnwma.so (cabjni.c + libmspack 0.11alpha, LGPL-2.1).
 * The library is loaded lazily, so a missing native library never crashes app startup.
 */
public final class NativeCab {
    private static Boolean loaded;

    private NativeCab() {}

    private static native int extract(String in, String name, String out);

    /** True if the native extractor could be loaded on this device. */
    public static synchronized boolean available() {
        if (loaded == null) {
            try { System.loadLibrary("msnwma"); loaded = Boolean.TRUE; }
            catch (Throwable t) { loaded = Boolean.FALSE; }
        }
        return loaded;
    }

    /**
     * Extracts the named file (case-insensitive) from the first cabinet in {@code in} that contains it.
     * @return 0 ok, -2 no cabinet found, -3 file not in cabinet, &lt;= -10 decompression error,
     *         -1 bad arguments, -100 native library unavailable, -101 native call failed
     */
    public static int extractFile(File in, String name, File out) {
        if (!available()) return -100;
        if (in == null || !in.isFile() || name == null || out == null) return -1;
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        int r;
        try { r = extract(in.getAbsolutePath(), name, out.getAbsolutePath()); }
        catch (Throwable t) { r = -101; }
        if (r != 0) out.delete();
        return r;
    }
}
