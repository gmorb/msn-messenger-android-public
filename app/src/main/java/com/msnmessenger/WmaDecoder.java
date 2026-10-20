package com.msnmessenger;

import java.io.File;

/**
 * Converts the user's own MSN Messenger 8 WMA sounds to 16-bit PCM WAV during import.
 * Backed by libmsnwma.so (cut-down FFmpeg n6.1.1, LGPL-2.1+: asf demuxer + wmav1/wmav2 only).
 * The library is loaded lazily, so a missing or broken native library never crashes app startup.
 */
public final class WmaDecoder {
    private static Boolean loaded;

    private WmaDecoder() {}

    private static native int decodeToWav(String in, String out);

    /** True if the native decoder could be loaded on this device. */
    public static synchronized boolean available() {
        if (loaded == null) {
            try { System.loadLibrary("msnwma"); loaded = Boolean.TRUE; }
            catch (Throwable t) { loaded = Boolean.FALSE; }
        }
        return loaded;
    }

    /**
     * Decodes a WMA file to a WAV file.
     * @return 0 on success, a negative code on failure (-100 if the decoder is unavailable)
     */
    public static int toWav(File in, File out) {
        if (!available()) return -100;
        if (in == null || out == null || !in.isFile()) return -1;
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        int r;
        try { r = decodeToWav(in.getAbsolutePath(), out.getAbsolutePath()); }
        catch (Throwable t) { r = -101; }
        if (r != 0) out.delete();
        return r;
    }
}
