package com.msnmessenger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

public class MainActivity extends Activity {
    static class Buddy {
        String id, name, status, psm, peer;
        boolean isRoom;
        int members;
        Buddy(String id, String name, String status, String psm) { this.id = id; this.name = name; this.status = status; this.psm = psm; }
        boolean online() { return !"Offline".equals(status); }
    }

    ListView listView;
    Button btnNewChat;
    TextView btnLogout, tvMyName, tvMyStatus;
    EditText etSearch;
    String server, token, userId;
    String myStatus = "Online";
    boolean isDemo;
    final ArrayList<Buddy> buddies = new ArrayList<>();
    final ArrayList<Object> rows = new ArrayList<>();
    boolean showOnline = true, showOffline = true, showRooms = true;
    BaseAdapter adapter;
    volatile boolean running = true;
    String since;
    final java.util.concurrent.ConcurrentHashMap<String, String> presence = new java.util.concurrent.ConcurrentHashMap<>();
    final java.util.concurrent.ConcurrentHashMap<String, String> psms = new java.util.concurrent.ConcurrentHashMap<>();
    final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    static final List<String> STATUSES = Arrays.asList("Online", "Busy", "Be Right Back", "Away", "In a Call", "Out to Lunch");

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        I18n.init(this); Session.init(this);
        try {
            setContentView(R.layout.activity_main);
            AssetStore.applyTags(findViewById(android.R.id.content));
            listView = findViewById(R.id.listView);
            btnNewChat = findViewById(R.id.btnNewChat);
            btnLogout = findViewById(R.id.btnLogout);
            tvMyName = findViewById(R.id.tvMyName);
            tvMyStatus = findViewById(R.id.tvMyStatus);
            etSearch = findViewById(R.id.etSearch);

            SharedPreferences prefs = getSharedPreferences("msn", MODE_PRIVATE);
            token = prefs.getString("token", null);
            userId = prefs.getString("user", null);
            server = prefs.getString("server", "");
            isDemo = prefs.getBoolean("isDemo", false);
            myStatus = prefs.getString("my_status", "Online");

            if (token == null) {
                startActivity(new Intent(this, LoginActivity.class));
                finish();
                return;
            }
            tvMyName.setText(isDemo ? I18n.tr("Me") : displayFromId(userId));
            if (!isDemo) { NotifyService.start(this); askNotifyPermissions(); }
            tvMyPsm = findViewById(R.id.tvMyPsm);
            showMyPsm();
            tvMyPsm.setOnClickListener(v -> editPsm());
            tvMyStatus.setText(" (" + I18n.tr(myStatus) + ") \u25BE"); frameFor((View) findViewById(R.id.ivMyTile).getParent(), myStatus);

            adapter = new RosterAdapter();
            listView.setAdapter(adapter);
            listView.setOnItemClickListener((p, v, pos, id) -> {
                Object o = rows.get(pos);
                if (o instanceof String) {
                    String gh = (String) o; if (gh.startsWith("Online")) showOnline = !showOnline; else if (gh.startsWith("Chat Rooms")) showRooms = !showRooms; else showOffline = !showOffline;
                    rebuildRows();
                    return;
                }
                Buddy bd = (Buddy) o;
                Intent i = new Intent(this, ChatActivity.class);
                i.putExtra("roomId", bd.id);
                i.putExtra("roomName", bd.name);
                i.putExtra("peer", bd.peer);
                i.putExtra("status", bd.status);
                i.putExtra("server", server);
                i.putExtra("token", token);
                i.putExtra("userId", userId);
                i.putExtra("isDemo", isDemo);
                startActivity(i);
            });

            tvMyStatus.setOnClickListener(v -> chooseStatus());
            findViewById(R.id.ivMyTile).setOnClickListener(v -> choosePicture());
            refreshMyPicture();
            View.OnClickListener newChat = v -> {
                if (isDemo) { Toast.makeText(this, I18n.tr("Demo mode: no new chats"), Toast.LENGTH_SHORT).show(); return; }
                new AlertDialog.Builder(this).setTitle(I18n.tr("Add"))
                    .setItems(I18n.tr(new String[]{"Add a Contact", "Create a Chat Room", "Join a Chat Room"}), (d, w) -> {
                        if (w == 0) showNewChatDialog(); else if (w == 1) createRoomDialog(); else joinRoomDialog();
                    }).setNegativeButton(I18n.tr("Cancel"), null).show();
            };
            btnNewChat.setOnClickListener(newChat);
            findViewById(R.id.btnAddContact).setOnClickListener(newChat);
            btnLogout.setOnClickListener(v -> {
                Session.signOut(this);
                NotifyService.stop(this);
                SharedPreferences spOut = getSharedPreferences("msn", MODE_PRIVATE);
                java.util.Map<String, ?> allOut = spOut.getAll();
                SharedPreferences.Editor edOut = spOut.edit().clear();
                for (String kOut : KEEP_ON_SIGNOUT) {
                    Object vOut = allOut.get(kOut);
                    if (vOut instanceof Boolean) edOut.putBoolean(kOut, (Boolean) vOut);
                    else if (vOut instanceof Integer) edOut.putInt(kOut, (Integer) vOut);
                    else if (vOut instanceof String) edOut.putString(kOut, (String) vOut);
                }
                edOut.apply();
                startActivity(new Intent(this, LoginActivity.class).putExtra("signedOut", true));
                finish();
            });
            etSearch.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
                public void onTextChanged(CharSequence s, int a, int c, int d) {}
                public void afterTextChanged(Editable s) { rebuildRows(); }
            });

            if (isDemo) loadDemoConversations(); else loadConversations();
            INSTANCE = this; LAST_INPUT = System.currentTimeMillis();
            ui.postDelayed(idleTick, 30000);
            if (getIntent().getBooleanExtra("signedIn", false)) playSound("online");
        } catch (Exception e) {
            showCrash(e);
        }
    }

    static final java.util.concurrent.ConcurrentHashMap<String, String> NAMES = new java.util.concurrent.ConcurrentHashMap<>();

    /** Turns a typed username into a full Matrix ID on the signed-in user's server. */
    String fullId(String typed) {
        if (typed == null) return "";
        String t = typed.trim();
        if (t.isEmpty()) return "";
        if ((t.startsWith("@") || t.startsWith("!") || t.startsWith("#")) && t.contains(":")) return t;
        if (t.startsWith("@")) t = t.substring(1);
        if (t.contains(":")) return "@" + t;
        int at = t.indexOf('@');
        if (at > 0) t = t.substring(0, at);
        String domain = null;
        if (userId != null && userId.indexOf(':') > 0) domain = userId.substring(userId.indexOf(':') + 1);
        if (domain == null && server != null) domain = server.replaceAll("^https?://", "").replaceAll("/.*$", "");
        return "@" + t.toLowerCase(Locale.US) + ":" + domain;
    }

    static String plainUser(String id) {
        if (id == null) return null;
        String s = id.startsWith("@") ? id.substring(1) : id;
        int c = s.indexOf(':');
        return c > 0 ? s.substring(0, c) : s;
    }

    static String displayFromId(String id) {
        if (id == null) return "Me";
        String known = NAMES.get(id);
        if (known != null && !known.isEmpty()) return known;
        String s = id.startsWith("@") ? id.substring(1) : id;
        int c = s.indexOf(':');
        return c > 0 ? s.substring(0, c) : s;
    }

    TextView tvMyPsm;

    /** Shows your personal message, or the MSN placeholder when it's empty. */
    void showMyPsm() {
        if (tvMyPsm == null) return;
        String m = getSharedPreferences("msn", MODE_PRIVATE).getString("my_psm", "");
        if (m.isEmpty()) { tvMyPsm.setText(I18n.tr("<Type a personal message>")); tvMyPsm.setTextColor(0xFF63614E); }
        else { tvMyPsm.setText(Emoticons.render(this, m, Math.round(tvMyPsm.getLineHeight() * 1.05f))); tvMyPsm.setTextColor(0xFF333333); }
    }

    void editPsm() {
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setHint(I18n.tr("Type a personal message"));
        in.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(129)});
        in.setText(I18n.tr(getSharedPreferences("msn", MODE_PRIVATE).getString("my_psm", "")));
        in.setSelection(in.getText().length());
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        FrameLayout fl = new FrameLayout(this);
        fl.setPadding(pad, pad / 2, pad, 0);
        fl.addView(in);
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle(I18n.tr("Personal message")).setView(fl)
            .setPositiveButton(I18n.tr("OK"), (d, w) -> savePsm(in.getText().toString().replace('\n', ' ').trim()))
            .setNeutralButton(I18n.tr("Clear"), (d, w) -> savePsm(""))
            .setNegativeButton(I18n.tr("Cancel"), null).create();
        in.setOnEditorActionListener((v, a, e) -> { savePsm(in.getText().toString().replace('\n', ' ').trim()); dlg.dismiss(); return true; });
        dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
    }

    void savePsm(String m) {
        getSharedPreferences("msn", MODE_PRIVATE).edit().putString("my_psm", m).apply();
        showMyPsm();
        sendPresence(myStatus);
    }

    /** Matrix status text: "[Busy] message", or just the message when Online. */
    static String statusMsg(String st, String psm) {
        psm = psm == null ? "" : psm.trim();
        if (st == null || "Online".equals(st) || "Appear Offline".equals(st)) return psm;
        return psm.isEmpty() ? st : "[" + st + "] " + psm;
    }

    static String statusPart(String m) {
        if (m == null) return null;
        if (STATUSES.contains(m)) return m;
        if (m.startsWith("[")) { int e = m.indexOf(']'); if (e > 1 && STATUSES.contains(m.substring(1, e))) return m.substring(1, e); }
        return null;
    }

    static String psmPart(String m) {
        if (m == null || STATUSES.contains(m)) return "";
        if (m.startsWith("[")) { int e = m.indexOf(']'); if (e > 1 && STATUSES.contains(m.substring(1, e))) return m.substring(e + 1).trim(); }
        return m;
    }

    void chooseStatus() {
        final String[] st = {"Online", "Busy", "Be Right Back", "Away", "In a Call", "Out to Lunch", "Appear Offline"};
        new AlertDialog.Builder(this).setTitle(I18n.tr("My Status:")).setItems(I18n.tr(st), (d, w) -> {
            myStatus = st[w];
            getSharedPreferences("msn", MODE_PRIVATE).edit().putString("my_status", myStatus).putBoolean("auto_away", false).apply();
            tvMyStatus.setText(" (" + I18n.tr(myStatus) + ") \u25BE"); frameFor((View) findViewById(R.id.ivMyTile).getParent(), myStatus);
            sendPresence(myStatus);
        }).setNeutralButton(getString(R.string.qw_options), (d, w) -> awayOptions()).show();
    }

    /** Matrix set_presence value for an MSN status, so sync never resets it to online. */
    static String presParam(String st) {
        if ("Appear Offline".equals(st)) return "offline";
        if (st == null || "Online".equals(st)) return "online";
        return "unavailable";
    }

    static final String[] KEEP_ON_SIGNOUT = {"remember_me", "remember_pass", "auto_signin", "login_user", "login_pass", "login_server",
        "show_emoticons", "auto_away_on", "auto_away_min", "font_face", "font_b", "font_i", "font_u", "font_s", "font_pt", "font_color", "pic_kind", "pic_index"};

    // ---- Automatic Away (8.5: Show me as "Away" when I'm inactive for N minutes)
    static volatile long LAST_INPUT = System.currentTimeMillis();
    static MainActivity INSTANCE;

    static void userActive() {
        LAST_INPUT = System.currentTimeMillis();
        MainActivity m = INSTANCE;
        if (m != null && m.getSharedPreferences("msn", MODE_PRIVATE).getBoolean("auto_away", false)) m.ui.post(m::checkIdle);
    }

    @Override
    public void onUserInteraction() { super.onUserInteraction(); userActive(); }

    final Runnable idleTick = new Runnable() {
        public void run() { if (isFinishing() || isDestroyed()) return; checkIdle(); ui.postDelayed(this, 30000); }
    };

    void checkIdle() {
        if (isFinishing() || isDestroyed()) return;
        SharedPreferences p = getSharedPreferences("msn", MODE_PRIVATE);
        boolean auto = p.getBoolean("auto_away", false);
        myStatus = p.getString("my_status", myStatus);
        boolean idle = p.getBoolean("auto_away_on", true)
            && System.currentTimeMillis() - LAST_INPUT > Math.max(1, p.getInt("auto_away_min", 5)) * 60000L;
        if (idle && "Online".equals(myStatus) && !auto) setMyStatus("Away", true);
        else if (!idle && auto) setMyStatus("Online", false);
    }

    void setMyStatus(String st, boolean auto) {
        myStatus = st;
        getSharedPreferences("msn", MODE_PRIVATE).edit().putString("my_status", st).putBoolean("auto_away", auto).apply();
        tvMyStatus.setText(" (" + I18n.tr(myStatus) + ") \u25BE"); frameFor((View) findViewById(R.id.ivMyTile).getParent(), myStatus);
        sendPresence(myStatus);
    }

    void awayOptions() {
        final SharedPreferences p = getSharedPreferences("msn", MODE_PRIVATE);
        float dd = getResources().getDisplayMetrics().density; int pad = Math.round(16 * dd);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(pad, pad, pad, 0);
        final android.widget.CheckBox cb = new android.widget.CheckBox(this); cb.setText(R.string.qw_away); cb.setChecked(p.getBoolean("auto_away_on", true));
        col.addView(cb);
        LinearLayout row = new LinearLayout(this); row.setGravity(android.view.Gravity.CENTER_VERTICAL); row.setPadding(Math.round(32 * dd), 0, 0, 0);
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); et.setEms(3); et.setText(String.valueOf(p.getInt("auto_away_min", 5)));
        row.addView(et);
        TextView tm = new TextView(this); tm.setText(R.string.qw_minutes); tm.setPadding(Math.round(6 * dd), 0, 0, 0); row.addView(tm);
        col.addView(row);
        new AlertDialog.Builder(this).setTitle(getString(R.string.qw_options)).setView(col)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                int m = 5; try { m = Integer.parseInt(et.getText().toString().trim()); } catch (Exception ignored) {}
                m = Math.max(1, Math.min(99, m));
                p.edit().putBoolean("auto_away_on", cb.isChecked()).putInt("auto_away_min", m).apply();
                checkIdle();
            }).setNegativeButton(android.R.string.cancel, null).show();
    }

    static int mixColor(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t), g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t), bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** MSN 8 display-picture frame: green online, red busy, orange away, grey offline. */
    static void frameFor(View frame, String status) {
        if (frame == null) return;
        int c;
        switch (status == null ? "Online" : status) {
            case "Busy": case "In a Call": c = 0xFFD9342B; break;
            case "Away": case "Be Right Back": case "Out to Lunch": c = 0xFFF0A020; break;
            case "Offline": case "Appear Offline": c = 0xFFA0A7AE; break;
            default: c = 0xFF3DB43C;
        }
        float d = frame.getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{mixColor(c, 0xFFFFFFFF, 0.55f), c, mixColor(c, 0xFF000000, 0.15f)});
        g.setCornerRadius(6 * d);
        g.setStroke(Math.max(1, Math.round(d)), mixColor(c, 0xFF000000, 0.3f));
        frame.setBackground(g);
        int p = Math.round(4 * d);
        frame.setPadding(p, p, p, p);
    }

    static int presenceIcon(String status) {
        if (status == null) return R.drawable.presence_online;
        switch (status) {
            case "Busy": case "In a Call": return R.drawable.presence_busy;
            case "Away": case "Be Right Back": case "Out to Lunch": return R.drawable.presence_away;
            case "Offline": return R.drawable.presence_offline;
            default: return R.drawable.presence_online;
        }
    }

    void rebuildRows() {
        String q = etSearch == null ? "" : etSearch.getText().toString().trim().toLowerCase(Locale.US);
        ArrayList<Buddy> on = new ArrayList<>(), off = new ArrayList<>(), rms = new ArrayList<>();
        for (Buddy bd : buddies) {
            if (!q.isEmpty() && !bd.name.toLowerCase(Locale.US).contains(q)) continue;
            if (bd.isRoom) rms.add(bd); else (bd.online() ? on : off).add(bd);
        }
        rows.clear();
        rows.add("Online (" + on.size() + ")");
        if (showOnline) rows.addAll(on);
        rows.add("Offline (" + off.size() + ")");
        if (showOffline) rows.addAll(off);
        rows.add("Chat Rooms (" + rms.size() + ")");
        if (showRooms) rows.addAll(rms);
        adapter.notifyDataSetChanged();
    }

    class RosterAdapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int p) { return rows.get(p); }
        public long getItemId(int p) { return p; }
        public int getViewTypeCount() { return 2; }
        public int getItemViewType(int p) { return rows.get(p) instanceof String ? 0 : 1; }
        public View getView(int p, View v, ViewGroup parent) {
            Object o = rows.get(p);
            LayoutInflater inf = LayoutInflater.from(MainActivity.this);
            if (o instanceof String) {
                if (v == null) { v = inf.inflate(R.layout.row_group, parent, false); AssetStore.set((ImageView) v.findViewById(R.id.ivArrow), R.drawable.group_arrow); }
                String g = (String) o;
                boolean open = g.startsWith("Online") ? showOnline : g.startsWith("Chat Rooms") ? showRooms : showOffline;
                ((TextView) v.findViewById(R.id.tvGroup)).setText(I18n.tr(g));
                v.findViewById(R.id.ivArrow).setRotation(open ? 90f : 0f);
                return v;
            }
            if (v == null) v = inf.inflate(R.layout.row_buddy, parent, false);
            Buddy bd = (Buddy) o;
            if (bd.isRoom) {
                AssetStore.set(((ImageView) v.findViewById(R.id.ivPresence)), R.drawable.tb_invite);
                TextView rn = v.findViewById(R.id.tvBuddyName);
                rn.setText(bd.name);
                rn.setTextColor(0xFF000000);
                TextView rp = v.findViewById(R.id.tvBuddyPsm);
                rp.setText(I18n.tr(bd.members == 1 ? "1 member" : bd.members > 1 ? bd.members + " members" : "Chat room"));
                rp.setVisibility(View.VISIBLE);
                return v;
            }
            AssetStore.set(((ImageView) v.findViewById(R.id.ivPresence)), presenceIcon(bd.status));
            TextView name = v.findViewById(R.id.tvBuddyName);
            String label = bd.name + ("Online".equals(bd.status) || "Offline".equals(bd.status) ? "" : " (" + I18n.tr(bd.status) + ")");
            name.setText(label);
            name.setTextColor(bd.online() ? 0xFF000000 : 0xFF8C8C8C);
            TextView psm = v.findViewById(R.id.tvBuddyPsm);
            psm.setText(I18n.tr(Emoticons.render(MainActivity.this, bd.psm == null ? "" : bd.psm, Math.round(psm.getLineHeight() * 1.05f))));
            psm.setVisibility(bd.psm == null || bd.psm.isEmpty() ? View.GONE : View.VISIBLE);
            return v;
        }
    }

    static final int PICK_PIC = 77;

    void refreshMyPicture() {
        Avatars.showMine(this, (ImageView) findViewById(R.id.ivMyTile), server, token, userId);
    }

    void choosePicture() {
        final String[] opts = isDemo
            ? new String[]{"Original MSN pictures", "Choose a photo..."}
            : new String[]{"Original MSN pictures", "Choose a photo...", "Use my Matrix profile picture"};
        new AlertDialog.Builder(this).setTitle(I18n.tr("Change Display Picture")).setItems(I18n.tr(opts), (d, w) -> {
            if (w == 0) pickBuiltinPicture();
            else if (w == 1) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("image/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                try { startActivityForResult(Intent.createChooser(i, "Choose a Picture"), PICK_PIC); }
                catch (Exception e) { Toast.makeText(this, I18n.tr("No photo app found."), Toast.LENGTH_SHORT).show(); }
            } else {
                getSharedPreferences("msn", MODE_PRIVATE).edit().putString("pic_kind", "matrix").apply();
                refreshMyPicture();
            }
        }).setNegativeButton(I18n.tr("Cancel"), null).show();
    }

    void pickBuiltinPicture() {
        final GridView grid = new GridView(this);
        final float dp = getResources().getDisplayMetrics().density;
        final int cell = Math.round(72 * dp);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(cell);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(Math.round(8 * dp));
        grid.setPadding(16, 16, 16, 16);
        grid.setAdapter(new BaseAdapter() {
            public int getCount() { return Avatars.PICS.length; }
            public Object getItem(int p) { return p; }
            public long getItemId(int p) { return p; }
            public View getView(int p, View v, ViewGroup parent) {
                LinearLayout ll = new LinearLayout(MainActivity.this);
                ll.setOrientation(LinearLayout.VERTICAL);
                ll.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                ImageView iv = new ImageView(MainActivity.this);
                iv.setLayoutParams(new LinearLayout.LayoutParams(Math.round(56 * dp), Math.round(56 * dp)));
                iv.setBackgroundResource(R.drawable.bg_tile_frame);
                iv.setPadding(3, 3, 3, 3);
                AssetStore.set(iv, Avatars.PICS[p]);
                TextView tv = new TextView(MainActivity.this);
                tv.setText(Avatars.PIC_NAMES[p]); tv.setTextSize(11); tv.setGravity(android.view.Gravity.CENTER);
                tv.setTextColor(0xFF333333); tv.setSingleLine(true);
                ll.addView(iv); ll.addView(tv);
                return ll;
            }
        });
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle(I18n.tr("My Display Picture")).setView(grid)
            .setNegativeButton(I18n.tr("Cancel"), null).create();
        grid.setOnItemClickListener((p, v, pos, id) -> {
            dlg.dismiss();
            getSharedPreferences("msn", MODE_PRIVATE).edit().putString("pic_kind", "builtin").putInt("pic_index", pos).apply();
            refreshMyPicture();
            uploadPicture(Avatars.builtinPng(this, pos));
        });
        dlg.show();
    }

    void uploadPicture(final byte[] png) {
        if (isDemo || token == null || png == null) return;
        new Thread(() -> {
            String mxc = Avatars.uploadAsProfile(server, token, userId, png, "image/png");
            runOnUiThread(() -> Toast.makeText(this, I18n.tr(mxc != null ? "Display picture updated." : "Couldn't update your picture on the server."), Toast.LENGTH_SHORT).show());
        }).start();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_PIC || res != RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri uri = data.getData();
        new Thread(() -> {
            try {
                byte[] raw = Avatars.readAll(getContentResolver().openInputStream(uri));
                final byte[] png = Avatars.squarePng(raw);
                if (png == null) throw new IOException("Not an image");
                try (FileOutputStream o = new FileOutputStream(Avatars.customFile(this))) { o.write(png); }
                getSharedPreferences("msn", MODE_PRIVATE).edit().putString("pic_kind", "custom").apply();
                runOnUiThread(this::refreshMyPicture);
                uploadPicture(png);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, I18n.tr("That picture couldn't be used."), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    void loadDemoConversations() {
        buddies.add(new Buddy("demo-1", "John", "Online", "Coffee later? (C)"));
        buddies.add(new Buddy("demo-2", "Sarah", "Away", "Party Saturday!! (^) :D"));
        buddies.add(new Buddy("demo-3", "Mike", "Busy", "Working... do not disturb :-|"));
        buddies.add(new Buddy("demo-4", "Lisa", "Online", "\u266B Listening to: Nelly Furtado - Maneater"));
        buddies.add(new Buddy("demo-5", "Alex", "Offline", ""));
        runOnUiThread(this::rebuildRows);
        ui.postDelayed(() -> {
            if (!running) return;
            for (Buddy bd : buddies) if ("demo-5".equals(bd.id)) { bd.status = "Online"; bd.psm = "Back online! :)"; }
            rebuildRows();
            msnAlert(R.drawable.pic_soccer, I18n.tr("Alex has just signed in."), "online", "");
        }, 4000);
        ui.postDelayed(() -> { if (running) msnAlert(R.mipmap.ic_launcher, I18n.tr("Sarah says:\nDon't forget Saturday! (^)"), "type"); }, 11000);
        ui.postDelayed(() -> { if (running) msnAlert(R.mipmap.ic_launcher, I18n.tr("You have 1 new e-mail message."), "newemail"); }, 18000);
    }

    /** True while the contact list is on screen; it shows in-app alerts, so notifications are skipped. */
    static volatile boolean FOREGROUND = false;

    @Override
    protected void onResume() { super.onResume(); FOREGROUND = true; }

    @Override
    protected void onPause() { FOREGROUND = false; super.onPause(); }

    void askNotifyPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 33
            && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 91);
        SharedPreferences p = getSharedPreferences("msn", MODE_PRIVATE);
        if (p.getBoolean("asked_battery", false)) return;
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        p.edit().putBoolean("asked_battery", true).apply();
        new AlertDialog.Builder(this).setTitle(I18n.tr("MSN Messenger"))
            .setMessage(I18n.tr("To get messages, nudges and winks when MSN Messenger is closed, allow it to keep running in the background."))
            .setPositiveButton(I18n.tr("Allow"), (d, w) -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    try { startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception ignored) {}
                }
            })
            .setNegativeButton(I18n.tr("Not now"), null).show();
    }

    @Override
    protected void onDestroy() {
        if (INSTANCE == this) INSTANCE = null;
        running = false;
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    void playSound(String name) { AssetStore.play(this, name); }

    /** MSN-style pop-up alert (bottom right) with the original sound. */
    void msnAlert(int icon, String text, String sound) { msnAlert(icon, text, sound, null); }

    /** who != null: show the contact picture in a green frame, like the 8.5 sign-in toast. */
    void msnAlert(int icon, String text, String sound, String who) {
        text = I18n.tr(text);
        playSound(sound);
        try {
            float dp = getResources().getDisplayMetrics().density;
            int p = Math.round(12 * dp);
            LinearLayout ll = new LinearLayout(this);
            ll.setOrientation(LinearLayout.HORIZONTAL);
            ll.setGravity(android.view.Gravity.CENTER_VERTICAL);
            ll.setPadding(p, p, p, p);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFFFFFFFF, 0xFFD6E6F8});
            bg.setStroke(Math.max(1, Math.round(dp)), 0xFF7A96C2);
            bg.setCornerRadius(4 * dp);
            ll.setBackground(bg);
            ImageView iv = new ImageView(this);
            AssetStore.set(iv, icon);
            if (who != null) {
                android.widget.FrameLayout fr = new android.widget.FrameLayout(this);
                fr.addView(iv, new android.widget.FrameLayout.LayoutParams(Math.round(40 * dp), Math.round(40 * dp)));
                frameFor(fr, "Online");
                if (!isDemo && !who.isEmpty()) Avatars.showUser(this, iv, server, token, who, R.drawable.tile_default);
                ll.addView(fr);
            } else ll.addView(iv, new LinearLayout.LayoutParams(Math.round(32 * dp), Math.round(32 * dp)));
            TextView tv = new TextView(this);
            tv.setText(Emoticons.render(this, text, Math.round(18 * dp)));
            tv.setTextColor(0xFF1E3287);
            tv.setTextSize(14);
            tv.setPadding(p, 0, 0, 0);
            ll.addView(tv);
            Toast t = new Toast(this);
            t.setView(ll);
            t.setDuration(Toast.LENGTH_LONG);
            t.setGravity(android.view.Gravity.BOTTOM | android.view.Gravity.END, Math.round(16 * dp), Math.round(80 * dp));
            t.show();
        } catch (Exception e) {
            Toast.makeText(this, I18n.tr(text), Toast.LENGTH_LONG).show();
        }
    }

    void sendPresence(final String st) {
        if (isDemo || token == null) return;
        new Thread(() -> {
            try {
                String pr = "Appear Offline".equals(st) ? "offline" : "Online".equals(st) ? "online" : "unavailable";
                JSONObject body = new JSONObject();
                body.put("presence", pr);
                body.put("status_msg", statusMsg(st, getSharedPreferences("msn", MODE_PRIVATE).getString("my_psm", "")));
                String url = server.replaceAll("/$", "") + "/_matrix/client/v3/presence/" + java.net.URLEncoder.encode(userId, "UTF-8") + "/status";
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("PUT");
                conn.setRequestProperty("Authorization", Session.bearer(token));
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.getOutputStream().write(body.toString().getBytes("UTF-8"));
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    static String statusFrom(JSONObject c) {
        String p = c.optString("presence", "offline");
        String m = c.optString("status_msg", "");
        if ("offline".equals(p)) return "Offline";
        String sp = statusPart(m);
        if (sp != null) return sp;
        return "online".equals(p) ? "Online" : "Away";
    }

    void loadConversations() {
        rebuildRows();
        new Thread(() -> {
            boolean first = true, warned = false;
            while (running) {
                try {
                    String filter = "{\"room\":{\"timeline\":{\"limit\":" + (first ? 1 : 10) + "}}}";
                    String url = server.replaceAll("/$", "") + "/_matrix/client/v3/sync?timeout=" + (first ? 0 : 25000)
                        + "&set_presence=" + presParam(myStatus)
                        + "&filter=" + java.net.URLEncoder.encode(filter, "UTF-8")
                        + (since != null ? "&since=" + java.net.URLEncoder.encode(since, "UTF-8") : "");
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(45000);
                    conn.setRequestProperty("Authorization", Session.bearer(token));
                    if (conn.getResponseCode() != 200) { conn.disconnect(); Thread.sleep(5000); continue; }
                    InputStream is = conn.getInputStream();
                    String text = new Scanner(is, "UTF-8").useDelimiter("\\A").next();
                    is.close();
                    handleSync(new JSONObject(text), first);
                    first = false;
                } catch (Exception e) {
                    if (!running) return;
                    if (!warned) {
                        warned = true;
                        final String m = String.valueOf(e.getMessage());
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, I18n.tr(m), Toast.LENGTH_SHORT).show());
                    }
                    try { Thread.sleep(5000); } catch (InterruptedException ie) { return; }
                }
            }
        }).start();
    }

    void handleSync(JSONObject j, boolean first) throws Exception {
        since = j.optString("next_batch", since);
        final ArrayList<Object[]> alerts = new ArrayList<>();
        JSONObject pr = j.optJSONObject("presence");
        JSONArray pev = pr == null ? null : pr.optJSONArray("events");
        if (pev != null) for (int i = 0; i < pev.length(); i++) {
            JSONObject e = pev.getJSONObject(i);
            String who = e.optString("sender");
            JSONObject c = e.optJSONObject("content");
            if (c == null || who.isEmpty() || who.equals(userId)) continue;
            String st = statusFrom(c);
            String m = c.optString("status_msg", "");
            psms.put(who, psmPart(m));
            String old = presence.put(who, st);
            if (!first && !"Offline".equals(st) && (old == null || "Offline".equals(old)))
                alerts.add(new Object[]{R.drawable.tile_default, displayFromId(who) + " has just signed in.", "online", who});
        }
        final ArrayList<Buddy> found = new ArrayList<>();
        JSONObject rooms = j.optJSONObject("rooms");
        JSONObject joined = rooms == null ? null : rooms.optJSONObject("join");
        if (joined != null) {
            Iterator<String> keys = joined.keys();
            while (keys.hasNext()) {
                String rid = keys.next();
                JSONObject r = joined.getJSONObject(rid);
                String peer = null, rname = null;
                JSONObject sum = r.optJSONObject("summary");
                JSONArray heroes = sum == null ? null : sum.optJSONArray("m.heroes");
                if (heroes != null) for (int h = 0; h < heroes.length(); h++) {
                    String u = heroes.optString(h, null);
                    if (u != null && !u.equals(userId)) { peer = u; break; }
                }
                for (String sec : new String[]{"state", "timeline"}) {
                    JSONObject so = r.optJSONObject(sec);
                    JSONArray se = so == null ? null : so.optJSONArray("events");
                    if (se == null) continue;
                    for (int s = 0; s < se.length(); s++) {
                        JSONObject e = se.getJSONObject(s);
                        String ty = e.optString("type");
                        JSONObject c = e.optJSONObject("content");
                        if (c == null) continue;
                        if ("m.room.name".equals(ty) && !c.optString("name", "").isEmpty()) rname = c.optString("name");
                        else if ("m.room.member".equals(ty)) {
                            String who = e.optString("state_key", "");
                            if (who.isEmpty()) continue;
                            String dn = c.optString("displayname", "");
                            if (!dn.isEmpty() && !dn.startsWith("@")) NAMES.put(who, dn);
                            String ms = c.optString("membership", "");
                            if (peer == null && !who.equals(userId) && ("join".equals(ms) || "invite".equals(ms))) peer = who;
                        }
                    }
                }
                int jc = sum == null ? -1 : sum.optInt("m.joined_member_count", -1);
                int ic = sum == null ? 0 : sum.optInt("m.invited_member_count", 0);
                Buddy bd = new Buddy(rid, rname, "Online", "");
                bd.isRoom = rname != null || (jc >= 0 && jc + ic > 2);
                bd.members = jc;
                bd.peer = peer;
                found.add(bd);
                JSONObject tl = r.optJSONObject("timeline");
                JSONArray ev = tl == null ? null : tl.optJSONArray("events");
                if (first || ev == null || rid.equals(ChatActivity.OPEN_ROOM)) continue;
                for (int i = 0; i < ev.length(); i++) {
                    JSONObject e = ev.getJSONObject(i);
                    if (!"m.room.message".equals(e.optString("type")) || e.optString("sender").equals(userId)) continue;
                    JSONObject c = e.optJSONObject("content");
                    String body = c == null ? "" : c.optString("body");
                    String who = displayFromId(e.optString("sender"));
                    if (body.equals("*nudge*")) alerts.add(new Object[]{R.drawable.ic_nudge, who + " has just sent you a nudge.", "nudge"});
                    else if (body.startsWith("*wink:")) alerts.add(new Object[]{R.drawable.ic_wink, who + " sent you a wink.", "type"});
                    else alerts.add(new Object[]{R.mipmap.ic_launcher, who + " says:\n" + body, "type"});
                }
            }
        }
        final boolean replace = first;
        runOnUiThread(() -> {
            if (!running) return;
            if (replace) buddies.clear();
            for (Buddy nb : found) {
                Buddy ex = null;
                for (Buddy b : buddies) if (b.id.equals(nb.id)) { ex = b; break; }
                if (ex == null) {
                    if (nb.name == null) nb.name = nb.peer != null ? displayFromId(nb.peer) : "Empty conversation";
                    buddies.add(nb);
                } else {
                    if (nb.peer != null) ex.peer = nb.peer;
                    if (nb.name != null) ex.name = nb.name;
                    else if (ex.peer != null) ex.name = displayFromId(ex.peer);
                }
            }
            for (Buddy b : buddies) {
                if (b.peer == null) continue;
                String st = presence.get(b.peer);
                if (st != null) b.status = st;
                String m = psms.get(b.peer);
                if (m != null) b.psm = m;
            }
            rebuildRows();
            int from = FOREGROUND ? Math.max(0, alerts.size() - 3) : alerts.size();
            for (int i = from; i < alerts.size(); i++) {
                Object[] a = alerts.get(i);
                msnAlert((Integer) a[0], (String) a[1], (String) a[2], a.length > 3 ? (String) a[3] : null);
            }
        });
    }

    String parseName(JSONObject room) {
        try {
            JSONObject tl = room.optJSONObject("timeline");
            JSONArray events = tl == null ? null : tl.optJSONArray("events");
            if (events != null) {
                for (int i = 0; i < events.length(); i++) {
                    JSONObject e = events.getJSONObject(i);
                    if ("m.room.name".equals(e.optString("type"))) {
                        JSONObject c = e.optJSONObject("content");
                        String n = c == null ? null : c.optString("name");
                        if (n != null && !n.isEmpty()) return n;
                    }
                }
            }
        } catch (Exception ex) {}
        return null;
    }

    /** POSTs JSON to the homeserver; returns {code, body}. */
    String[] postJson(String path, JSONObject body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(server.replaceAll("/$", "") + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000); conn.setReadTimeout(30000);
        conn.setRequestProperty("Authorization", Session.bearer(token));
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.getOutputStream().write(body.toString().getBytes("UTF-8"));
        int code = conn.getResponseCode();
        InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        String text = "";
        if (is != null) { Scanner sc = new Scanner(is, "UTF-8").useDelimiter("\\A"); text = sc.hasNext() ? sc.next() : ""; sc.close(); }
        return new String[]{String.valueOf(code), text};
    }

    static String serverError(String body) {
        try { String e = new JSONObject(body).optString("error", ""); if (!e.isEmpty()) return e; } catch (Exception ignored) {}
        return "The server didn't accept the request.";
    }

    void addRoomAndOpen(String rid, String name, int members) {
        Buddy ex = null;
        for (Buddy b : buddies) if (b.id.equals(rid)) { ex = b; break; }
        if (ex == null) { ex = new Buddy(rid, name, "Online", ""); buddies.add(ex); }
        ex.isRoom = true; ex.name = name; if (members > 0) ex.members = members;
        showRooms = true;
        rebuildRows();
        showChat(rid, name);
    }

    void createRoomDialog() {
        final float dp = getResources().getDisplayMetrics().density;
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * dp); ll.setPadding(pad, pad / 2, pad, 0);
        final EditText name = new EditText(this); name.setHint(I18n.tr("Chat room name")); name.setSingleLine(true);
        final EditText invites = new EditText(this); invites.setHint(I18n.tr("Invite usernames (optional, comma separated)")); invites.setSingleLine(true);
        final CheckBox pub = new CheckBox(this); pub.setText(I18n.tr("Anyone on the server can join"));
        ll.addView(name); ll.addView(invites); ll.addView(pub);
        new AlertDialog.Builder(this).setTitle(I18n.tr("Create a Chat Room")).setView(ll)
            .setPositiveButton(I18n.tr("Create"), (d, w) -> {
                final String n = name.getText().toString().trim();
                if (n.isEmpty()) { Toast.makeText(this, I18n.tr("Type a name for the chat room."), Toast.LENGTH_SHORT).show(); return; }
                final ArrayList<String> who = new ArrayList<>();
                for (String s : invites.getText().toString().split(",")) { String id = fullId(s); if (!id.isEmpty()) who.add(id); }
                final boolean isPublic = pub.isChecked();
                new Thread(() -> {
                    try {
                        JSONObject body = new JSONObject();
                        body.put("name", n);
                        body.put("preset", isPublic ? "public_chat" : "private_chat");
                        if (isPublic) body.put("room_alias_name", n.toLowerCase(Locale.US).replaceAll("[^a-z0-9._=-]+", "-").replaceAll("^-+|-+$", ""));
                        JSONArray inv = new JSONArray(); for (String u : who) inv.put(u);
                        body.put("invite", inv);
                        String[] r = postJson("/_matrix/client/v3/createRoom", body);
                        if (!"200".equals(r[0])) { final String m = serverError(r[1]); runOnUiThread(() -> Toast.makeText(this, I18n.tr("Couldn't create the room: " + m), Toast.LENGTH_LONG).show()); return; }
                        final String rid = new JSONObject(r[1]).getString("room_id");
                        runOnUiThread(() -> addRoomAndOpen(rid, n, 1));
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_SHORT).show());
                    }
                }).start();
            }).setNegativeButton(I18n.tr("Cancel"), null).show();
    }

    void joinRoomDialog() {
        final EditText input = new EditText(this);
        input.setHint(I18n.tr("Room name, #alias or room ID"));
        input.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle(I18n.tr("Join a Chat Room")).setView(input)
            .setPositiveButton(I18n.tr("Join"), (d, w) -> {
                String t = input.getText().toString().trim();
                if (t.isEmpty()) return;
                if (!t.startsWith("!") && !(t.startsWith("#") && t.contains(":"))) {
                    String local = t.startsWith("#") ? t.substring(1) : t;
                    if (local.contains(":")) t = "#" + local;
                    else {
                        String domain = userId != null && userId.indexOf(':') > 0 ? userId.substring(userId.indexOf(':') + 1)
                            : server.replaceAll("^https?://", "").replaceAll("/.*$", "");
                        t = "#" + local.toLowerCase(Locale.US).replaceAll("[^a-z0-9._=-]+", "-") + ":" + domain;
                    }
                }
                final String target = t;
                new Thread(() -> {
                    try {
                        String[] r = postJson("/_matrix/client/v3/join/" + java.net.URLEncoder.encode(target, "UTF-8"), new JSONObject());
                        if (!"200".equals(r[0])) { final String m = serverError(r[1]); runOnUiThread(() -> Toast.makeText(this, I18n.tr("Couldn't join: " + m), Toast.LENGTH_LONG).show()); return; }
                        final String rid = new JSONObject(r[1]).getString("room_id");
                        final String label = target.startsWith("#") ? target.substring(1, target.indexOf(':') > 0 ? target.indexOf(':') : target.length()) : "Chat room";
                        runOnUiThread(() -> addRoomAndOpen(rid, label, 0));
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_SHORT).show());
                    }
                }).start();
            }).setNegativeButton(I18n.tr("Cancel"), null).show();
    }

    void showNewChatDialog() {
        EditText input = new EditText(this);
        input.setHint(I18n.tr("Username"));
        new AlertDialog.Builder(this).setTitle(I18n.tr("Add a Contact")).setView(input)
            .setPositiveButton(I18n.tr("OK"), (d, w) -> {
                final String target = fullId(input.getText().toString().trim());
                if (target.isEmpty()) return;
                new Thread(() -> {
                    try {
                        String url = server.replaceAll("/$", "") + "/_matrix/client/v3/createRoom";
                        JSONObject body = new JSONObject();
                        body.put("preset", "private_chat");
                        body.put("invite", new JSONArray().put(target));
                        body.put("is_direct", true);
                        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                        conn.setRequestMethod("POST");
                        conn.setRequestProperty("Authorization", Session.bearer(token));
                        conn.setRequestProperty("Content-Type", "application/json");
                        conn.setDoOutput(true);
                        conn.getOutputStream().write(body.toString().getBytes("UTF-8"));
                        if (conn.getResponseCode() == 200) {
                            String text = new Scanner(conn.getInputStream()).useDelimiter("\\A").next();
                            String rid = new JSONObject(text).getString("room_id");
                            runOnUiThread(() -> showChat(rid, target));
                        }
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_SHORT).show());
                    }
                }).start();
            }).setNegativeButton(I18n.tr("Cancel"), null).show();
    }

    void showChat(String roomId, String roomName) {
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("roomId", roomId);
        if (roomName != null && roomName.startsWith("@")) {
            i.putExtra("peer", roomName);
            i.putExtra("roomName", displayFromId(roomName));
        } else i.putExtra("roomName", roomName);
        i.putExtra("status", "Online");
        i.putExtra("server", server);
        i.putExtra("token", token);
        i.putExtra("userId", userId);
        i.putExtra("isDemo", false);
        startActivity(i);
    }

    void showCrash(Exception e) {
        new AlertDialog.Builder(this).setTitle(I18n.tr("Error"))
            .setMessage(android.util.Log.getStackTraceString(e))
            .setPositiveButton(I18n.tr("OK"), (d, w) -> finish()).show();
    }
}
