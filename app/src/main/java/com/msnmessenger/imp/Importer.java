package com.msnmessenger.imp;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turns the user's own MSN Messenger 8.0 installer into the app's assets.
 * Accepted inputs: "MSN Messenger 8.exe" (LZX cabinet, via NativeCab), MsnMsgs.msi, or a .zip containing either.
 * Chain: input -> MsnMsgs.msi -> stream MsgrCore.cab -> msgsres.dll + 10 WMA sounds -> outDir (AssetMap names).
 * Output is written to a staging folder and only moved into place when every item succeeded.
 * Pure Java: image decoding/encoding and WMA conversion are supplied by a {@link Platform}.
 */
public final class Importer {

    /** Platform services (Android: Bitmap/BitmapFactory + WmaDecoder; host test: ImageIO). */
    public interface Platform {
        /** Decodes PNG bytes; returns ARGB pixels and stores width/height in wh[0], wh[1]. */
        int[] decodePng(byte[] png, int[] wh) throws IOException;
        /** Encodes ARGB pixels as a PNG file. */
        void writePng(File out, int[] argb, int w, int h) throws IOException;
        /** Converts a WMA file to WAV; 0 on success. */
        int wmaToWav(File wma, File wav);
        /** Extracts a named file from a cabinet embedded in an .exe; 0 on success. */
        int extractFromExe(File exe, String name, File out);
    }

    public interface Progress { void step(int done, int total, String what); }

    public static final String MARKER = "import.ok";
    public static final int VERSION = 1;
    /** msgslang.dll inside MsgrCore.cab (MSI file key); holds the default winks. */
    static final String CAB_MSGSLANG = "msgslangdll";

    private final Platform pf;
    private final Progress progress;
    private int done;

    public Importer(Platform pf, Progress progress) { this.pf = pf; this.progress = progress; }

    /** True if outDir holds a complete import of the current version. */
    public static boolean isImported(File outDir) {
        File m = new File(outDir, MARKER);
        if (!m.isFile()) return false;
        try (BufferedReader r = new BufferedReader(new FileReader(m))) {
            String l = r.readLine();
            return l != null && l.trim().equals(Integer.toString(VERSION));
        } catch (IOException e) { return false; }
    }

    /**
     * Runs the import. work is a scratch folder (deleted afterwards), outDir the final asset folder.
     * Throws IOException with a user-readable message on failure; outDir is left unchanged then.
     */
    public void run(File input, File work, File outDir) throws IOException {
        deleteTree(work);
        if (!work.mkdirs() && !work.isDirectory()) throw new IOException("Cannot create work folder");
        File stage = new File(outDir.getParentFile(), outDir.getName() + ".new");
        deleteTree(stage);
        stage.mkdirs();
        try {
            File msi = findMsi(input, work);
            File cab = new File(work, "MsgrCore.cab");
            try (RandomAccessFile f = new RandomAccessFile(msi, "r"); MsiReader mr = new MsiReader(f, 0)) {
                MsiReader.Entry e = mr.find("MsgrCore.cab");
                if (e == null) throw new IOException("This installer doesn't contain MSN Messenger 8 files (MsgrCore.cab missing)");
                try (OutputStream o = new BufferedOutputStream(new FileOutputStream(cab))) { mr.copy(e, o); }
            }
            List<String> want = new ArrayList<>();
            want.add(AssetMap.CAB_MSGSRES);
            want.add(CAB_MSGSLANG);
            for (String[] s : AssetMap.SOUND) want.add(s[0]);
            File cabOut = new File(work, "cab");
            Map<String, File> got;
            try (RandomAccessFile f = new RandomAccessFile(cab, "r"); CabReader cr = new CabReader(f, 0)) {
                got = lower(cr.extract(want, cabOut));
            }
            File dll = got.get(AssetMap.CAB_MSGSRES);
            if (dll == null) throw new IOException("msgsres.dll not found in the installer");
            done = 0;
            images(new PeResources(dll), stage);
            sounds(got, stage);
            // Keep winks imported separately (WinkImporter) across installer re-imports
            File oldWinks = new File(outDir, "winks"), newWinks = new File(stage, "winks");
            if (oldWinks.isDirectory() && !newWinks.exists()) oldWinks.renameTo(newWinks);
            // The 15 default winks are embedded in msgslang.dll; merge them in (optional, never fails the import)
            File lang = got.get(CAB_MSGSLANG);
            if (lang != null) {
                try { WinkImporter.fromDll(lang, new File(work, "winkwork"), newWinks); }
                catch (IOException ignored) {}
            }
            try (Writer w = new FileWriter(new File(stage, MARKER))) { w.write(VERSION + "\n"); }
            File old = new File(outDir.getParentFile(), outDir.getName() + ".old");
            deleteTree(old);
            if (outDir.exists() && !outDir.renameTo(old)) throw new IOException("Cannot replace previous import");
            if (!stage.renameTo(outDir)) { old.renameTo(outDir); throw new IOException("Cannot save imported files"); }
            deleteTree(old);
        } finally {
            deleteTree(stage);
            deleteTree(work);
        }
    }

    // ---------- input detection ----------

    private File findMsi(File input, File work) throws IOException {
        byte[] h = head(input);
        if (isCfb(h)) return input;
        if (h[0] == 'M' && h[1] == 'Z') return fromExe(input, work);
        if (h[0] == 'P' && h[1] == 'K') return fromZip(input, work);
        throw new IOException("Please choose the MSN Messenger 8 installer (.exe, MsnMsgs.msi or a .zip with one of them)");
    }

    private File fromExe(File exe, File work) throws IOException {
        File msi = new File(work, "MsnMsgs.msi");
        int r = pf.extractFromExe(exe, "MsnMsgs.msi", msi);
        if (r == 0 && msi.isFile()) return msi;
        if (r == -2 || r == -3) throw new IOException("This .exe isn't the MSN Messenger 8 installer");
        throw new IOException("Couldn't unpack the installer (error " + r + ")");
    }

    private File fromZip(File zip, File work) throws IOException {
        File best = null;
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            int n = 0;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String nm = e.getName().toLowerCase(Locale.ROOT);
                if (!(nm.endsWith(".exe") || nm.endsWith(".msi"))) continue;
                File o = new File(work, "zip" + (n++) + (nm.endsWith(".msi") ? ".msi" : ".exe"));
                try (OutputStream out = new FileOutputStream(o)) { copy(z, out); }
                byte[] h = head(o);
                if (isCfb(h)) {
                    try (RandomAccessFile f = new RandomAccessFile(o, "r"); MsiReader mr = new MsiReader(f, 0)) {
                        if (mr.find("MsgrCore.cab") != null) return o;
                    } catch (IOException ignored) {}
                } else if (h[0] == 'M' && h[1] == 'Z' && o.length() > 5_000_000L && best == null) {
                    best = o;
                }
            }
        }
        if (best != null) return fromExe(best, work);
        throw new IOException("No MSN Messenger 8 installer found in this .zip");
    }

    // ---------- images ----------

    private void images(PeResources pe, File out) throws IOException {
        int total = AssetMap.total();
        for (String[] e : AssetMap.PNG) {
            byte[] b = need(pe.get("4000", e[0]), "image " + e[0]);
            writeBytes(new File(out, e[1] + ".png"), b);
            tick(total, e[1]);
        }
        for (String[] e : AssetMap.ICON) {
            IconDecoder.Result r = IconDecoder.decode(pe, e[0], Integer.parseInt(e[1]));
            if (r == null) throw new IOException("Icon " + e[0] + " missing");
            File o = new File(out, e[2] + ".png");
            if (r.png != null) writeBytes(o, r.png); else pf.writePng(o, r.argb, r.width, r.height);
            tick(total, e[2]);
        }
        for (String[] e : AssetMap.RLE4X) {
            RleDecoder.Img im = rle(pe, e[0], Integer.parseInt(e[1]));
            if (im == null) throw new IOException("Skin image " + e[0] + " missing");
            int[] px = "S".equals(e[2]) ? swap(Hqx.hq4x(swap(im.argb), im.w, im.h)) : Hqx.hq4x(im.argb, im.w, im.h);
            pf.writePng(new File(out, e[3] + ".png"), px, im.w * 4, im.h * 4);
            tick(total, e[3]);
        }
        int[] wh = new int[2];
        int[] strip = pf.decodePng(need(pe.get("4000", AssetMap.EMO_STRIP), "emoticons"), wh);
        int E = AssetMap.EMO_SIZE;
        if (wh[0] < E * AssetMap.EMO_COUNT || wh[1] < E) throw new IOException("Unexpected emoticon strip size");
        for (int i = 0; i < AssetMap.EMO_COUNT; i++) {
            int[] slot = crop(strip, wh[0], i * E, 0, E, E);
            String n = String.format(Locale.ROOT, "emo_%02d", i);
            pf.writePng(new File(out, n + ".png"), Hqx.hq4x(slot, E, E), E * 4, E * 4);
            tick(total, n);
        }
        int[] bs = pf.decodePng(need(pe.get("4000", AssetMap.BUDDY_STRIP), "sign-in animation"), wh);
        int W = AssetMap.BUDDY_W, H = AssetMap.BUDDY_H;
        if (wh[0] < W * AssetMap.BUDDY_FRAMES || wh[1] < H) throw new IOException("Unexpected sign-in animation size");
        for (int i = 0; i < AssetMap.BUDDY_FRAMES; i++) {
            String n = String.format(Locale.ROOT, "signin_buddy_%02d", i);
            pf.writePng(new File(out, n + ".png"), crop(bs, wh[0], i * W, 0, W, H), W, H);
            tick(total, n);
        }
    }

    private static RleDecoder.Img rle(PeResources pe, String id, int sub) {
        for (String t : new String[]{"4000", "4006", "4002", "4003", "4004", "4005"}) {
            byte[] b = pe.get(t, id);
            if (b == null || (b.length > 3 && (b[0] & 255) == 0x89)) continue;
            try {
                List<RleDecoder.Img> l = RleDecoder.decode(b);
                if (l.size() > sub) return l.get(sub);
            } catch (RuntimeException ignored) {}
        }
        return null;
    }

    // ---------- sounds ----------

    private void sounds(Map<String, File> got, File out) throws IOException {
        int total = AssetMap.total();
        for (String[] s : AssetMap.SOUND) {
            File wma = got.get(s[0]);
            if (wma == null) throw new IOException("Sound " + s[1] + " missing from the installer");
            int r = pf.wmaToWav(wma, new File(out, s[1] + ".wav"));
            if (r != 0) throw new IOException("Couldn't convert sound " + s[1] + " (error " + r + ")");
            tick(total, s[1]);
        }
    }

    // ---------- helpers ----------

    private void tick(int total, String what) { done++; if (progress != null) progress.step(done, total, what); }

    static int[] swap(int[] a) {
        int[] o = new int[a.length];
        for (int i = 0; i < a.length; i++) { int c = a[i]; o[i] = (c & 0xff00ff00) | ((c >> 16) & 255) | ((c & 255) << 16); }
        return o;
    }

    static int[] crop(int[] src, int sw, int x, int y, int w, int h) {
        int[] o = new int[w * h];
        for (int r = 0; r < h; r++) System.arraycopy(src, (y + r) * sw + x, o, r * w, w);
        return o;
    }

    private static byte[] need(byte[] b, String what) throws IOException {
        if (b == null) throw new IOException("Missing " + what + " in msgsres.dll");
        return b;
    }

    private static Map<String, File> lower(Map<String, File> m) {
        Map<String, File> o = new HashMap<>();
        for (Map.Entry<String, File> e : m.entrySet()) o.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        return o;
    }

    private static boolean isCfb(byte[] h) {
        return (h[0] & 255) == 0xD0 && (h[1] & 255) == 0xCF && (h[2] & 255) == 0x11 && (h[3] & 255) == 0xE0;
    }

    private static byte[] head(File f) throws IOException {
        byte[] h = new byte[8];
        try (InputStream in = new FileInputStream(f)) {
            int n = 0, r;
            while (n < 8 && (r = in.read(h, n, 8 - n)) > 0) n += r;
        }
        return h;
    }

    private static void writeBytes(File f, byte[] b) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) { o.write(b); }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] k = f.listFiles();
        if (k != null) for (File c : k) deleteTree(c);
        f.delete();
    }
}
