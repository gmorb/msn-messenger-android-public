package com.msnmessenger.imp;

/**
 * What the importer extracts from the user's MSN Messenger 8.0 installer (MsnMsgs.msi -> MsgrCore cab),
 * and the file name each item gets in the app's private asset folder (getFilesDir()/msn/).
 * Every entry was verified pixel-for-pixel against the private build's bundled resources.
 * Pure Java (no Android classes).
 */
public final class AssetMap {
    private AssetMap() {}

    /** CAB entry names (the cab stores names without dots). */
    public static final String CAB_MSGSRES = "msgsresdll";
    public static final String CAB_MSNMSGR = "msnmsgrexe";

    /** Plain PNG skin images: msgsres.dll type 4000 id -> output name. Bytes are copied unchanged. */
    public static final String[][] PNG = {
        {"994", "bubble_play"}, {"995", "bubble_stop"}, {"965", "buddy_small"}, {"976", "check_green"},
        {"580", "dp_frame"}, {"1015", "dp_frame_empty"}, {"537", "dp_frame_small"},
        {"20143", "glass_h1"}, {"20144", "glass_h2"}, {"20145", "glass_h3"}, {"20146", "glass_h4"},
        {"20139", "glass_v1"}, {"20140", "glass_v2"}, {"1013", "group_arrow"},
        {"20147", "orb1"}, {"20147", "orb2"}, {"20149", "orb3"}, {"20147", "orb4"},
        {"237", "pic_beach"}, {"236", "pic_chess"}, {"250", "pic_dog"}, {"239", "pic_duck"},
        {"234", "pic_flower"}, {"253", "pic_horses"}, {"251", "pic_moto"}, {"252", "pic_palms"},
        {"238", "pic_shuttle"}, {"254", "pic_skater"}, {"235", "pic_soccer"},
        {"1010", "signal1"}, {"1011", "signal2"}, {"1012", "signal3"},
        {"284", "swirl_bg"}, {"20092", "swirl_header"}, {"20055", "swirl_mid"}, {"20091", "swirl_strip"},
        {"166", "tab_alerts"}, {"190", "tab_contacts"}, {"179", "tab_mail"}, {"176", "tab_today"},
        {"1529", "tile_default"}, {"597", "tile_default_small"},
        {"1472", "wlm_banner"}, {"1075", "wlm_logo"}, {"664", "wlm_logo_small"}, {"682", "wlm_logo_white"},
    };

    /** Windows icon groups in msgsres.dll: group id, pixel size, output name. */
    public static final String[][] ICON = {
        {"1", "48", "app_icon"}, {"683", "48", "signed_out"}, {"714", "48", "tb_alert"},
        {"712", "48", "tb_message"}, {"685", "16", "tray_online"},
    };

    /**
     * Skin RLE images enlarged 4x with hq4x: resource id, sub-image index, recipe, output name.
     * Recipe "S" = swap(hq4x(swap(c))) (R/B exchanged around hq4x), "P" = hq4x(c). Reproduces the private build exactly.
     */
    public static final String[][] RLE4X = {
        {"410", "0", "S", "ic_background"}, {"412", "0", "S", "ic_emoticon"}, {"411", "0", "S", "ic_font"},
        {"414", "0", "S", "ic_nudge"}, {"1487", "0", "S", "ic_wink"}, {"459", "0", "P", "ic_voiceclip"},
        {"1111", "0", "S", "presence_online"}, {"1111", "1", "P", "presence_offline"},
        {"1111", "2", "S", "presence_busy"}, {"1111", "3", "S", "presence_away"}, {"1111", "5", "S", "presence_mobile"},
        {"1448", "0", "S", "tb_activities"}, {"326", "0", "S", "tb_block"}, {"1447", "0", "S", "tb_call"},
        {"1443", "0", "S", "tb_games"}, {"1441", "0", "S", "tb_invite"}, {"1024", "0", "S", "tb_sendfiles"},
        {"1024", "0", "S", "tb_sharing"}, {"1445", "0", "S", "tb_webcam"},
    };

    /** Emoticon strip: type 4000 PNG, 79 slots of 19x19, enlarged 4x -> emo_00..emo_78. */
    public static final String EMO_STRIP = "202";
    public static final int EMO_COUNT = 79, EMO_SIZE = 19;

    /** Spinning-buddy strip: type 4000 PNG, 32 frames of 48x36 -> signin_buddy_00..31, pixels unchanged. */
    public static final String BUDDY_STRIP = "208";
    public static final int BUDDY_FRAMES = 32, BUDDY_W = 48, BUDDY_H = 36;

    /** Launcher: msnmsgr.exe icon group 1 at 48 px, drawn on a white rounded tile at runtime. */
    public static final String LAUNCHER_GROUP = "1";

    /** Sounds: CAB entry -> output name (decoded WMA -> WAV with the bundled native decoder). */
    public static final String[][] SOUND = {
        {"hangupwma", "hangup"}, {"newalertwma", "newalert"}, {"newemailwma", "newemail"},
        {"nudgewma", "nudge"}, {"onlinewma", "online"}, {"outgoingwma", "outgoing"},
        {"phonewma", "phone"}, {"ringwma", "ring"}, {"typewma", "type"}, {"vimdonewma", "vimdone"},
    };

    /** Total number of output files, for progress and completeness checks. */
    public static int total() {
        return PNG.length + ICON.length + RLE4X.length + EMO_COUNT + BUDDY_FRAMES + SOUND.length;
    }
}
