package com.msnmessenger;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates UI text using the phone's language.
 * res/values/i18n.xml holds the English keys (i18n_en) and res/values-xx/i18n.xml the matching
 * translations (i18n_tr, same order). Keys containing %1$s are templates: the English text is
 * matched, the translated template is filled with the original values (status names inside are
 * translated too). Unknown text is returned unchanged.
 */
public final class I18n {
    private I18n() {}

    static final HashMap<String, String> EXACT = new HashMap<>();
    static final ArrayList<Object[]> TEMPLATES = new ArrayList<>(); // {Pattern, int[] groupToArg, String translated, Integer literalLen}
    static volatile boolean ready;
    static String loc = "";
    /** Only these captured values are translated inside templates; names and messages never are. */
    static final java.util.Set<String> ARG_WORDS = new java.util.HashSet<>(java.util.Arrays.asList(
        "Online", "Busy", "Be Right Back", "Away", "In a Call", "Out to Lunch", "Appear Offline", "Offline",
        "webcam conversation", "voice conversation"));
    static final Pattern ARG = Pattern.compile("%(\\d)\\$s");

    public static synchronized void init(Context c) {
        String l = String.valueOf(c.getResources().getConfiguration().locale);
        if (ready && l.equals(loc)) return;
        loc = l;
        EXACT.clear();
        TEMPLATES.clear();
        try {
            String[] en = c.getResources().getStringArray(R.array.i18n_en);
            String[] tr = c.getResources().getStringArray(R.array.i18n_tr);
            for (int i = 0; i < en.length; i++) {
                String k = en[i];
                String v = i < tr.length && tr[i] != null && !tr[i].isEmpty() ? tr[i] : k;
                if (ARG.matcher(k).find()) addTemplate(k, v);
                else EXACT.put(k.replace("%%", "%"), v.replace("%%", "%"));
            }
            // Most specific templates first (longest fixed text).
            Collections.sort(TEMPLATES, (a, b) -> (Integer) b[3] - (Integer) a[3]);
        } catch (Exception ignored) {}
        ready = true;
    }

    /** Called when the phone's language changes. */
    public static synchronized void reload(Context c) {
        ready = false;
        init(c);
    }

    private static void addTemplate(String k, String v) {
        StringBuilder rx = new StringBuilder();
        ArrayList<Integer> order = new ArrayList<>();
        Matcher m = ARG.matcher(k);
        int last = 0, lit = 0;
        while (m.find()) {
            String part = k.substring(last, m.start()).replace("%%", "%");
            rx.append(Pattern.quote(part));
            lit += part.length();
            rx.append("(.*?)");
            order.add(Integer.parseInt(m.group(1)));
            last = m.end();
        }
        String tail = k.substring(last).replace("%%", "%");
        rx.append(Pattern.quote(tail));
        lit += tail.length();
        int[] g = new int[order.size()];
        for (int i = 0; i < g.length; i++) g[i] = order.get(i);
        TEMPLATES.add(new Object[]{Pattern.compile(rx.toString(), Pattern.DOTALL), g, v, lit});
    }

    public static String tr(String s) {
        if (s == null || !ready || s.isEmpty()) return s;
        String e = EXACT.get(s);
        if (e != null) return e;
        for (Object[] t : TEMPLATES) {
            Matcher m = ((Pattern) t[0]).matcher(s);
            if (!m.matches()) continue;
            int[] g = (int[]) t[1];
            String[] args = new String[10];
            for (int i = 0; i < g.length; i++) {
                String cap = m.group(i + 1);
                String ct = ARG_WORDS.contains(cap) ? EXACT.get(cap) : null;
                args[g[i]] = ct != null ? ct : cap;
            }
            String out = (String) t[2];
            Matcher a = ARG.matcher(out);
            StringBuffer sb = new StringBuffer();
            while (a.find()) {
                String val = args[Integer.parseInt(a.group(1))];
                a.appendReplacement(sb, Matcher.quoteReplacement(val == null ? "" : val));
            }
            a.appendTail(sb);
            return sb.toString().replace("%%", "%");
        }
        return s;
    }

    /** Styled text (emoticons) is already user content; plain strings are translated. */
    public static CharSequence tr(CharSequence cs) {
        if (cs instanceof String) return tr((String) cs);
        return cs;
    }

    public static String[] tr(String[] a) {
        if (a == null) return null;
        String[] o = new String[a.length];
        for (int i = 0; i < a.length; i++) o[i] = tr(a[i]);
        return o;
    }

    public static CharSequence[] tr(CharSequence[] a) {
        if (a == null) return null;
        CharSequence[] o = new CharSequence[a.length];
        for (int i = 0; i < a.length; i++) o[i] = tr(a[i]);
        return o;
    }
}
