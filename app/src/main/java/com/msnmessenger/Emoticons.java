package com.msnmessenger;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;

/** Original MSN Messenger emoticon set (msgsres.dll resource 202, 79 frames of 19x19). */
public final class Emoticons {
    private Emoticons() {}
    public static final int COUNT = 79;
    public static final String[] NAMES = {"Smile", "Open-mouthed smile", "Winking smile", "Surprised smile", "Smile with tongue out", "Hot smile", "Angry smile", "Embarrassed smile", "Confused smile", "Sad smile", "Crying face", "Disappointed smile", "Devil", "Angel", "Red heart", "Broken heart", "Messenger", "Cat face", "Dog face", "Sleeping half-moon", "Star", "Filmstrip", "Note", "E-mail", "Red rose", "Wilted rose", "Clock", "Red lips", "Gift with a bow", "Birthday cake", "Camera", "Light bulb", "Coffee cup", "Telephone receiver", "Left hug", "Right hug", "Beer mug", "Martini glass", "Boy", "Girl", "Thumbs up", "Thumbs down", "Vampire bat", "Goat", "Handcuffs", "Sun", "Rainbow", "Don't tell anyone", "Baring teeth", "Nerd", "Sarcastic", "Secret telling", "Sick", "Snail", "Turtle", "Plate", "Bowl", "Pizza", "Soccer ball", "Auto", "Airplane", "Umbrella", "Island with a palm tree", "Computer", "Mobile Phone", "Thinking", "Stormy cloud", "Fingers crossed", "High five", "Money", "Black sheep", "Eye-rolling", "I don't know", "Lightning", "Party", "Wondering", "Cigarette", "Sleepy", "Xbox"};
    /** Primary shortcut per frame, inserted by the picker. */
    public static final String[] SHORTCUTS = {":)", ":D", ";)", ":O", ":P", "(H)", ":@", ":$", ":S", ":(", ":'(", ":|", "(6)", "(A)", "(L)", "(U)", "(M)", "(@)", "(&)", "(S)", "(*)", "(~)", "(8)", "(E)", "(F)", "(W)", "(O)", "(K)", "(G)", "(^)", "(P)", "(I)", "(C)", "(T)", "({)", "(})", "(B)", "(D)", "(Z)", "(X)", "(Y)", "(N)", ":[", "(nah)", "(%)", "(#)", "(R)", ":-#", "8o|", "8-|", "^o)", ":-*", "+o(", "(sn)", "(tu)", "(pl)", "(||)", "(pi)", "(so)", "(au)", "(ap)", "(um)", "(ip)", "(co)", "(mp)", "*-)", "(st)", "(yn)", "(h5)", "(mo)", "(bah)", "8-)", ":^)", "(li)", "<:o)", "(ww)", "(ci)", "|-)", "(xx)"};
    /** All shortcuts, longest first, with their frame index. */
    static final String[] CODES = {"(nah)", "(bah)", "(sn)", "(tu)", "(pl)", "(||)", "(pi)", "(so)", "(au)", "(ap)", "(um)", "(ip)", "(co)", "(mp)", "(st)", "(yn)", "(h5)", "(mo)", "(li)", "<:o)", "(ww)", "(ci)", "(xx)", ":-)", ":-D", ";-)", ":-O", ":-P", "(H)", ":-@", ":-$", ":-S", ":-(", ":'(", ":-|", "(6)", "(A)", "(L)", "(U)", "(M)", "(@)", "(&)", "(S)", "(*)", "(~)", "(8)", "(E)", "(F)", "(W)", "(O)", "(K)", "(G)", "(^)", "(P)", "(I)", "(C)", "(T)", "({)", "(})", "(B)", "(D)", "(Z)", "(X)", "(Y)", "(N)", ":-[", "(%)", "(#)", "(R)", ":-#", "8o|", "8-|", "^o)", ":-*", "+o(", "*-)", "8-)", ":^)", "|-)", ":)", ":D", ";)", ":O", ":P", ":@", ":$", ":S", ":(", ":|", ":["};
    static final int[] FRAMES = {43, 70, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 66, 67, 68, 69, 73, 74, 75, 76, 78, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 44, 45, 46, 47, 48, 49, 50, 51, 52, 65, 71, 72, 77, 0, 1, 2, 3, 4, 6, 7, 8, 9, 11, 42};

    public static int resId(Context c, int frame) {
        return c.getResources().getIdentifier(String.format(java.util.Locale.US, "emo_%02d", frame), "drawable", c.getPackageName());
    }

    /** Asset name of an emoticon frame, e.g. emo_00. */
    public static String name(int frame) { return String.format(java.util.Locale.US, "emo_%02d", frame); }

    /** Emoticon image: imported from the user's installer, or the bundled resource (private build). */
    public static Drawable drawable(Context c, int frame, int sizePx) {
        Drawable d = AssetStore.drawable(c, name(frame));
        if (d != null) d.setBounds(0, 0, sizePx, sizePx);
        return d;
    }

    /** Replaces MSN text shortcuts with the original emoticon images. Letter codes are case-insensitive, like the original. */
    public static CharSequence render(Context c, CharSequence text, int sizePx) {
        if (text == null) return "";
        if (!c.getSharedPreferences("msn", Context.MODE_PRIVATE).getBoolean("show_emoticons", true)) return text.toString();
        String s = text.toString();
        SpannableStringBuilder sb = new SpannableStringBuilder(s);
        int i = 0;
        while (i < s.length()) {
            int hit = -1;
            for (int k = 0; k < CODES.length; k++) {
                String code = CODES[k];
                if (s.regionMatches(true, i, code, 0, code.length())) { hit = k; break; }
            }
            if (hit < 0) { i++; continue; }
            Drawable d = drawable(c, FRAMES[hit], sizePx);
            if (d != null) sb.setSpan(new ImageSpan(d, ImageSpan.ALIGN_BOTTOM), i, i + CODES[hit].length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            i += CODES[hit].length();
        }
        return sb;
    }
}
