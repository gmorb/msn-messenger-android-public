package com.msnmessenger.imp;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports the user's own MSN Messenger winks into winksDir (files/msn/winks).
 * Accepted inputs: one .mco wink package, one .swf, or a .zip containing .mco and/or .swf files.
 * An .mco is a cabinet (MSZip) holding content.xml plus an inner cabinet with the SWF, thumbnail and content.xml.
 * Output layout (read by Winks.java): winksDir/index.tsv (id, name, thumb file) and winksDir/<id>/wink.swf + thumb.png.
 * New winks are merged with any already imported; a wink with the same id is replaced.
 * Pure Java, no native code.
 */
public final class WinkImporter {

    public interface Progress { void step(int done, int total, String what); }

    private WinkImporter() {}

    /** Returns the number of winks imported. Throws IOException with a user-readable message if none were found. */
    public static int run(File input, File work, File winksDir, Progress progress) throws IOException {
        USED.clear();
        Importer.deleteTree(work);
        if (!work.mkdirs() && !work.isDirectory()) throw new IOException("Cannot create work folder");
        try {
            List<File> mcos = new ArrayList<>();
            List<String[]> swfs = new ArrayList<>(); // {path, display name}
            byte[] h = head(input);
            if (isCab(h)) mcos.add(input);
            else if (isSwf(h)) swfs.add(new String[]{input.getPath(), "Wink"});
            else if (h[0] == 'P' && h[1] == 'K') unzip(input, work, mcos, swfs);
            else throw new IOException("Please choose a wink (.mco), a Flash wink (.swf) or a .zip with winks");
            if (mcos.isEmpty() && swfs.isEmpty()) throw new IOException("No winks found in this file");

            if (!winksDir.isDirectory() && !winksDir.mkdirs()) throw new IOException("Cannot create winks folder");
            LinkedHashMap<String, String[]> index = readIndex(new File(winksDir, "index.tsv"));
            int total = mcos.size() + swfs.size(), done = 0, added = 0;
            int n = 0;
            for (File m : mcos) {
                try {
                    File d = new File(work, "m" + (n++));
                    String[] w = fromMco(m, d);
                    if (w != null && store(w[0], w[1], w[2], winksDir, index)) added++;
                } catch (IOException ignored) { /* skip broken package, keep going */ }
                if (progress != null) progress.step(++done, total, m.getName());
            }
            for (String[] s : swfs) {
                if (store(s[1], s[0], null, winksDir, index)) added++;
                if (progress != null) progress.step(++done, total, s[1]);
            }
            if (added == 0) throw new IOException("No winks could be read from this file");
            writeIndex(new File(winksDir, "index.tsv"), index);
            return added;
        } finally {
            Importer.deleteTree(work);
        }
    }

    // ---------- inputs ----------

    /**
     * Imports the default winks embedded in the installer's msgslang.dll (one MSZip cabinet per wink:
     * name.swf, name.png/jpg thumbnail, content.xml). Theme/background cabinets hold no SWF and are skipped.
     * Existing imported winks are kept. Returns the number of winks stored.
     */
    public static int fromDll(File dll, File work, File winksDir) throws IOException {
        USED.clear();
        Importer.deleteTree(work);
        if (!work.mkdirs() && !work.isDirectory()) throw new IOException("Cannot create work folder");
        try {
            byte[] b = new byte[(int) dll.length()];
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(dll)))) { in.readFully(b); }
            TreeMap<String, String[]> found = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            int n = 0;
            for (int i = 0; i + 36 <= b.length; i++) {
                if (b[i] != 'M' || b[i + 1] != 'S' || b[i + 2] != 'C' || b[i + 3] != 'F') continue;
                long size = (b[i + 8] & 0xffL) | (b[i + 9] & 0xffL) << 8 | (b[i + 10] & 0xffL) << 16 | (b[i + 11] & 0xffL) << 24;
                if (size < 36 || i + size > b.length) continue;
                File d = new File(work, "d" + n), cab = new File(work, "d" + n + ".cab");
                n++;
                d.mkdirs();
                try (OutputStream o = new FileOutputStream(cab)) { o.write(b, i, (int) size); }
                try {
                    String[] w = fromMco(cab, d);
                    if (w != null) {
                        w[0] = prettyName(new File(w[1]).getName());
                        if (!found.containsKey(w[0])) found.put(w[0], w);
                    }
                } catch (IOException ignored) { /* not a wink package */ }
                i += (int) size - 1;
            }
            if (found.isEmpty()) return 0;
            if (!winksDir.isDirectory() && !winksDir.mkdirs()) throw new IOException("Cannot create winks folder");
            LinkedHashMap<String, String[]> index = readIndex(new File(winksDir, "index.tsv"));
            int added = 0;
            for (String[] w : found.values()) if (store(w[0], w[1], w[2], winksDir, index)) added++;
            if (added > 0) writeIndex(new File(winksDir, "index.tsv"), index);
            return added;
        } finally {
            Importer.deleteTree(work);
        }
    }

    private static void unzip(File zip, File work, List<File> mcos, List<String[]> swfs) throws IOException {
        int n = 0;
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String full = e.getName().replace('\\', '/');
                String base = full.substring(full.lastIndexOf('/') + 1);
                String lo = base.toLowerCase(Locale.ROOT);
                if (!(lo.endsWith(".mco") || lo.endsWith(".swf"))) continue;
                File o = new File(work, "z" + (n++) + (lo.endsWith(".mco") ? ".mco" : ".swf"));
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(o))) { copy(z, out); }
                byte[] h = head(o);
                if (isCab(h)) mcos.add(o);
                else if (isSwf(h)) swfs.add(new String[]{o.getPath(), prettyName(base)});
            }
        }
    }

    /** Unpacks one .mco; returns {name, swf path, thumb path or null}, or null if it holds no SWF. */
    static String[] fromMco(File mco, File dir) throws IOException {
        List<File> files = new ArrayList<>(unCab(mco, new File(dir, "outer")));
        int k = 0;
        for (File f : new ArrayList<>(files)) {
            if (f.getName().toLowerCase(Locale.ROOT).endsWith(".cab") && isCab(head(f)))
                files.addAll(unCab(f, new File(dir, "inner" + (k++))));
        }
        String name = null, anim = null, thumb = null;
        // Prefer the inner (wink) content.xml; it carries wink:name and the item roles
        for (int pass = 0; pass < 2 && anim == null; pass++) {
            for (File f : files) {
                if (!f.getName().equalsIgnoreCase("content.xml")) continue;
                boolean inner = f.getParentFile().getName().startsWith("inner");
                if ((pass == 0) != inner) continue;
                String xml = readText(f);
                Matcher mn = Pattern.compile("wink:name\\s*=\\s*\"([^\"]*)\"").matcher(xml);
                if (mn.find() && name == null) name = unescape(mn.group(1)).trim();
                Matcher it = Pattern.compile("<item\\b[^>]*>").matcher(xml);
                while (it.find()) {
                    String tag = it.group();
                    String type = attr(tag, "type"), file = attr(tag, "file");
                    if (file == null) continue;
                    if ("animation".equalsIgnoreCase(type) && anim == null) anim = file;
                    else if ("thumbnail".equalsIgnoreCase(type) && thumb == null) thumb = file;
                }
            }
        }
        File swf = byName(files, anim), png = byName(files, thumb);
        if (swf == null || !isSwf(head(swf))) {
            swf = null;
            for (File f : files) if (f.getName().toLowerCase(Locale.ROOT).endsWith(".swf") && isSwf(head(f))) { swf = f; break; }
        }
        if (swf == null) return null;
        if (png == null) for (File f : files) if (f.getName().toLowerCase(Locale.ROOT).endsWith(".png")) { png = f; break; }
        if (name == null || name.isEmpty()) name = prettyName(mco.getName());
        return new String[]{name, swf.getPath(), png == null ? null : png.getPath()};
    }

    private static List<File> unCab(File cab, File out) throws IOException {
        out.mkdirs();
        try (RandomAccessFile f = new RandomAccessFile(cab, "r"); CabReader cr = new CabReader(f, 0)) {
            List<String> names = new ArrayList<>();
            for (CabReader.FileEntry e : cr.files()) names.add(e.name);
            return new ArrayList<>(cr.extract(names, out).values());
        }
    }

    // ---------- output ----------

    /** Ids written during the current run: same id + identical SWF = duplicate (skipped), different SWF = numbered id. */
    private static final Set<String> USED = new HashSet<>();

    private static boolean store(String name, String swfPath, String thumbPath, File winksDir, Map<String, String[]> index) throws IOException {
        String base = slug(name);
        if (base.isEmpty()) base = "wink";
        String id = base;
        for (int k = 2; USED.contains(id); k++) {
            if (sameFile(new File(swfPath), new File(new File(winksDir, id), "wink.swf"))) return false;
            id = base + "_" + k;
            if (k > 2) name = name.replaceAll(" \\(\\d+\\)$", "");
            name = name + " (" + k + ")";
        }
        USED.add(id);
        File d = new File(winksDir, id);
        Importer.deleteTree(d);
        if (!d.mkdirs()) return false;
        copyFile(new File(swfPath), new File(d, "wink.swf"));
        String thumbName = "";
        if (thumbPath != null && new File(thumbPath).isFile()) { copyFile(new File(thumbPath), new File(d, "thumb.png")); thumbName = "thumb.png"; }
        index.put(id, new String[]{id, name.replace('\t', ' ').replace('\n', ' '), thumbName});
        return true;
    }

    private static LinkedHashMap<String, String[]> readIndex(File f) {
        LinkedHashMap<String, String[]> m = new LinkedHashMap<>();
        if (!f.isFile()) return m;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            String l;
            while ((l = r.readLine()) != null) {
                String[] p = l.split("\t", -1);
                if (p.length >= 2 && !p[0].isEmpty()) m.put(p[0], new String[]{p[0], p[1], p.length > 2 ? p[2] : ""});
            }
        } catch (IOException ignored) {}
        return m;
    }

    private static void writeIndex(File f, Map<String, String[]> m) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8")) {
            for (String[] e : m.values()) w.write(e[0] + "\t" + e[1] + "\t" + e[2] + "\n");
        }
        if (f.exists() && !f.delete()) throw new IOException("Cannot update the wink list");
        if (!tmp.renameTo(f)) throw new IOException("Cannot save the wink list");
    }

    // ---------- helpers ----------

    static String slug(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) b.append(c);
            else if (b.length() > 0 && b.charAt(b.length() - 1) != '_') b.append('_');
        }
        while (b.length() > 0 && b.charAt(b.length() - 1) == '_') b.setLength(b.length() - 1);
        return b.length() > 60 ? b.substring(0, 60) : b.toString();
    }

    static String prettyName(String file) {
        String s = file;
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        s = s.replace('_', ' ').trim();
        if (s.isEmpty()) return "Wink";
        // Title case, matching the bundled wink list ("bouncy_ball" -> "Bouncy Ball")
        StringBuilder b = new StringBuilder(s.length());
        boolean up = true;
        for (char c : s.toCharArray()) {
            b.append(up ? Character.toUpperCase(c) : c);
            up = c == ' ';
        }
        // Acronyms stay in capitals ("ufo" -> "UFO", as in the original wink list)
        String[] words = b.toString().split(" ");
        for (int i = 0; i < words.length; i++)
            if (ACRONYMS.contains(words[i].toUpperCase(Locale.ROOT))) words[i] = words[i].toUpperCase(Locale.ROOT);
        return String.join(" ", words);
    }

    private static final Set<String> ACRONYMS = new HashSet<>(Arrays.asList("UFO", "MSN", "LOL", "OMG"));

    private static String attr(String tag, String a) {
        Matcher m = Pattern.compile("(?:^|\\s)" + Pattern.quote(a) + "\\s*=\\s*\"([^\"]*)\"").matcher(tag);
        return m.find() ? unescape(m.group(1)) : null;
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    private static File byName(List<File> files, String name) {
        if (name == null) return null;
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        for (File f : files) if (f.getName().equalsIgnoreCase(n)) return f;
        return null;
    }

    private static String readText(File f) throws IOException {
        byte[] b = new byte[(int) Math.min(f.length(), 1 << 20)];
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) { in.readFully(b); }
        if (b.length >= 2 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xfe) return new String(b, 2, b.length - 2, "UTF-16LE");
        if (b.length >= 2 && (b[0] & 0xff) == 0xfe && (b[1] & 0xff) == 0xff) return new String(b, 2, b.length - 2, "UTF-16BE");
        int off = (b.length >= 3 && (b[0] & 0xff) == 0xef && (b[1] & 0xff) == 0xbb && (b[2] & 0xff) == 0xbf) ? 3 : 0;
        return new String(b, off, b.length - off, "UTF-8");
    }

    private static byte[] head(File f) throws IOException {
        byte[] h = new byte[8];
        try (InputStream in = new FileInputStream(f)) { int r = in.read(h); if (r < 4) Arrays.fill(h, (byte) 0); }
        return h;
    }

    private static boolean isCab(byte[] h) { return h[0] == 'M' && h[1] == 'S' && h[2] == 'C' && h[3] == 'F'; }

    /** True if both files exist and have identical bytes. */
    private static boolean sameFile(File a, File b) throws IOException {
        if (!a.isFile() || !b.isFile() || a.length() != b.length()) return false;
        try (InputStream x = new BufferedInputStream(new FileInputStream(a)); InputStream y = new BufferedInputStream(new FileInputStream(b))) {
            int p, q;
            do { p = x.read(); q = y.read(); if (p != q) return false; } while (p != -1);
            return true;
        }
    }

    private static boolean isSwf(byte[] h) { return (h[0] == 'C' || h[0] == 'F' || h[0] == 'Z') && h[1] == 'W' && h[2] == 'S'; }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static void copyFile(File a, File b) throws IOException {
        try (InputStream in = new FileInputStream(a); OutputStream out = new BufferedOutputStream(new FileOutputStream(b))) { copy(in, out); }
    }
}
