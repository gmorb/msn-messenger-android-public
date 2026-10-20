package com.msnmessenger;

import android.app.Activity;
import android.app.AlertDialog;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.TranslateAnimation;
import android.view.animation.CycleInterpolator;
import android.text.style.ImageSpan;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;

public class ChatActivity extends Activity {
    ListView listMessages;
    EditText etMessage;
    Button btnSend;
    TextView btnBack, tvChatTitle, tvChatSub, tvTo, tvTyping;
    String roomId, server, token, userId, contactName = "Contact";
    boolean isDemo;
    int demoIndex = 0;
    ArrayList<String> messageTexts = new ArrayList<>();
    BaseAdapter adapter;
    final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        I18n.init(this);
        try {
            setContentView(R.layout.activity_chat);
            AssetStore.applyTags(findViewById(android.R.id.content));
            // Composer buttons use drawableLeft in XML; swap in the imported icons (public build)
            int[][] composer = {{R.id.btnEmo, R.drawable.ic_emoticon}, {R.id.btnWink, R.drawable.ic_wink},
                {R.id.btnNudge, R.drawable.ic_nudge}, {R.id.btnFont, R.drawable.ic_font}, {R.id.btnVoice, R.drawable.ic_voiceclip}};
            for (int[] cb : composer) {
                TextView tvb = findViewById(cb[0]);
                android.graphics.drawable.Drawable dr = AssetStore.drawable(this, cb[1]);
                if (tvb != null && dr != null) tvb.setCompoundDrawablesWithIntrinsicBounds(dr, null, null, null);
            }
            listMessages = findViewById(R.id.listMessages);
            etMessage = findViewById(R.id.etMessage);
            btnSend = findViewById(R.id.btnSend);
            btnBack = findViewById(R.id.btnBack);
            tvChatTitle = findViewById(R.id.tvChatTitle);
            tvChatSub = findViewById(R.id.tvChatSub);
            tvTo = findViewById(R.id.tvTo);
            tvTyping = findViewById(R.id.tvTyping);

            roomId = getIntent().getStringExtra("roomId");
            server = getIntent().getStringExtra("server");
            token = getIntent().getStringExtra("token");
            userId = getIntent().getStringExtra("userId");
            isDemo = getIntent().getBooleanExtra("isDemo", false);
            String roomName = getIntent().getStringExtra("roomName");
            String status = getIntent().getStringExtra("status");
            if (status == null) status = "Online";
            if (roomId == null) { finish(); return; }
            String peer = getIntent().getStringExtra("peer");
            String plainUser = peer != null ? MainActivity.plainUser(peer) : null;
            if (roomName != null && !roomName.startsWith("!") && !roomName.startsWith("@")) contactName = roomName;
            else if (peer != null) contactName = MainActivity.displayFromId(peer);
            else contactName = "Contact";
            String sub = isDemo ? contactName.toLowerCase(Locale.US) + "@hotmail.com" : (plainUser != null ? plainUser : contactName);
            tvChatTitle.setText(contactName + " (" + I18n.tr(status) + ")");
            tvChatSub.setText(I18n.tr(sub));
            tvTo.setText(I18n.tr(sub.equals(contactName) ? "To: " + contactName : "To: " + contactName + " <" + sub + ">"));
            AssetStore.set(((ImageView) findViewById(R.id.ivTitlePresence)), MainActivity.presenceIcon(status));
            setTheirTile();
            MainActivity.frameFor((View) findViewById(R.id.ivTheirTile).getParent(), status);
            MainActivity.frameFor((View) findViewById(R.id.ivMyTileChat).getParent(), getSharedPreferences("msn", MODE_PRIVATE).getString("my_status", "Online"));
            buildToolbar();

            adapter = new MsgAdapter();
            TextView secNote = new TextView(this);
            secNote.setText(R.string.qw_security); secNote.setTextColor(0xFF6D6D6D); secNote.setTextSize(12);
            secNote.setPadding(wdp(8), wdp(6), wdp(8), wdp(8));
            listMessages.addHeaderView(secNote, null, false);
            listMessages.setAdapter(adapter);

            btnBack.setOnClickListener(v -> finish());
            btnSend.setOnClickListener(v -> sendMessage());
            findViewById(R.id.btnNudge).setOnClickListener(v -> sendNudge());
            findViewById(R.id.btnEmo).setOnClickListener(v -> pickEmoticon());
            findViewById(R.id.btnWink).setOnClickListener(v -> pickWink());
            findViewById(R.id.btnFont).setOnClickListener(v -> pickFont());
            findViewById(R.id.btnVoice).setOnTouchListener((v, ev) -> {
                switch (ev.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN: v.setPressed(true); startVoice(); break;
                    case android.view.MotionEvent.ACTION_UP: v.setPressed(false); stopVoice(true); break;
                    case android.view.MotionEvent.ACTION_CANCEL: v.setPressed(false); stopVoice(false); break;
                }
                return true;
            });
            myFont().apply(etMessage);
            findViewById(R.id.btnSearch).setOnClickListener(v -> soon("Search"));

            etMessage.addTextChangedListener(new android.text.TextWatcher() {
                boolean busy; // emoWatcher
                public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
                public void onTextChanged(CharSequence s, int a, int c, int d) {}
                public void afterTextChanged(android.text.Editable s) {
                    MainActivity.userActive();
                    if (busy) return; busy = true;
                    for (ImageSpan sp : s.getSpans(0, s.length(), ImageSpan.class)) s.removeSpan(sp);
                    CharSequence r = Emoticons.render(ChatActivity.this, s.toString(), emoSize(etMessage));
                    if (r instanceof android.text.Spanned) for (ImageSpan sp : ((android.text.Spanned) r).getSpans(0, r.length(), ImageSpan.class))
                        s.setSpan(sp, ((android.text.Spanned) r).getSpanStart(sp), ((android.text.Spanned) r).getSpanEnd(sp), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    busy = false;
                }
            });
            if (isDemo) loadDemoHistory(); else loadHistory();
        } catch (Exception e) {
            showCrash(e);
        }
    }

    void setTheirTile() {
        int[] pics = {R.drawable.pic_soccer, R.drawable.pic_flower, R.drawable.pic_chess, R.drawable.pic_beach, R.drawable.pic_dog, R.drawable.pic_moto};
        int i = Math.abs(roomId.hashCode()) % pics.length;
        ImageView their = findViewById(R.id.ivTheirTile);
        if (isDemo) AssetStore.set(their, pics[i]);
        else Avatars.showUser(this, their, server, token, getIntent().getStringExtra("peer"), pics[i]);
        ImageView mine = findViewById(R.id.ivMyTileChat);
        if (mine != null) Avatars.showMine(this, mine, server, token, userId);
    }

    void buildToolbar() {
        LinearLayout tb = findViewById(R.id.toolbar);
        Object[][] items = {
            {"Invite", R.drawable.tb_invite}, {"Send Files", R.drawable.tb_sendfiles}, {"Webcam", R.drawable.tb_webcam},
            {"Call", R.drawable.tb_call}, {"Activities", R.drawable.tb_activities}, {"Games", R.drawable.tb_games}, {"Block", R.drawable.tb_block}};
        LayoutInflater inf = LayoutInflater.from(this);
        for (Object[] it : items) {
            View v = inf.inflate(R.layout.tb_button, tb, false);
            AssetStore.set(((ImageView) v.findViewById(R.id.tbIcon)), (Integer) it[1]);
            ((TextView) v.findViewById(R.id.tbText)).setText(I18n.tr((String) it[0]));
            final String label = (String) it[0];
            v.setOnClickListener(x -> toolbarAction(label));
            tb.addView(v);
        }
    }

    void soon(String what) { Toast.makeText(this, I18n.tr(what + " will be available in a later version"), Toast.LENGTH_SHORT).show(); }

    void playSound(String name) { AssetStore.play(this, name); }

    void shake() {
        View root = findViewById(R.id.chatRoot);
        TranslateAnimation a = new TranslateAnimation(0, 24, 0, 12);
        a.setDuration(900); a.setInterpolator(new CycleInterpolator(8));
        root.startAnimation(a);
    }

    void sendNudge() {
        addLine(I18n.tr("*You have just sent a nudge."));
        playSound("nudge"); shake();
        if (isDemo) ui.postDelayed(() -> { addLine(I18n.tr("*" + contactName + " has just sent you a nudge.")); playSound("nudge"); shake(); }, 2500);
        else sendText("*nudge*");
    }

    int emoSize(TextView tv) { return Math.round(tv.getLineHeight() * 1.05f); }

    void pickEmoticon() {
        final GridView grid = new GridView(this);
        final int cell = Math.round(40 * getResources().getDisplayMetrics().density);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(cell);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(12, 12, 12, 12);
        grid.setAdapter(new BaseAdapter() {
            public int getCount() { return Emoticons.COUNT; }
            public Object getItem(int p) { return Emoticons.SHORTCUTS[p]; }
            public long getItemId(int p) { return p; }
            public View getView(int p, View v, ViewGroup parent) {
                ImageView iv = v instanceof ImageView ? (ImageView) v : new ImageView(ChatActivity.this);
                iv.setLayoutParams(new AbsListView.LayoutParams(cell, cell));
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setPadding(cell / 8, cell / 8, cell / 8, cell / 8);
                AssetStore.set(iv, Emoticons.name(p));
                iv.setContentDescription(Emoticons.NAMES[p] + " " + Emoticons.SHORTCUTS[p]);
                return iv;
            }
        });
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle(I18n.tr("Emoticons")).setView(grid).setNegativeButton(I18n.tr("Cancel"), null).create();
        grid.setOnItemClickListener((p, v, pos, id) -> {
            int at = Math.max(0, etMessage.getSelectionStart());
            etMessage.getText().insert(at, Emoticons.SHORTCUTS[pos]);
            dlg.dismiss();
        });
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            Toast.makeText(this, I18n.tr(Emoticons.NAMES[pos] + "  " + Emoticons.SHORTCUTS[pos]), Toast.LENGTH_SHORT).show();
            return true;
        });
        dlg.show();
    }

    void pickWink() {
        Winks.load(this);
        final int cell = Math.round(72 * getResources().getDisplayMetrics().density);
        final GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(cell);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(12, 12, 12, 12);
        grid.setAdapter(new BaseAdapter() {
            public int getCount() { return Winks.count(); }
            public Object getItem(int p) { return Winks.name(p); }
            public long getItemId(int p) { return p; }
            public View getView(int p, View v, ViewGroup parent) {
                LinearLayout ll = new LinearLayout(ChatActivity.this);
                ll.setOrientation(LinearLayout.VERTICAL);
                ll.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                ll.setPadding(4, 8, 4, 8);
                ImageView iv = new ImageView(ChatActivity.this);
                Winks.setThumb(iv, p);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                ll.addView(iv, new LinearLayout.LayoutParams(cell / 2, cell / 2));
                TextView tv = new TextView(ChatActivity.this);
                tv.setText(Winks.name(p)); tv.setTextSize(11); tv.setTextColor(0xFF1E3287);
                tv.setGravity(android.view.Gravity.CENTER); tv.setSingleLine(true);
                ll.addView(tv);
                return ll;
            }
        });
        AlertDialog.Builder wb = new AlertDialog.Builder(this).setTitle(I18n.tr("Send a Wink"))
            .setNegativeButton(I18n.tr("Cancel"), null)
            .setNeutralButton(I18n.tr("Import winks..."), (d, w) -> importWinks());
        if (Winks.count() == 0) wb.setMessage(I18n.tr("No winks are installed. Tap Import winks... and choose your own wink files (.mco, .swf or a .zip of them)."));
        else wb.setView(grid);
        final AlertDialog dlg = wb.create();
        grid.setOnItemClickListener((p, v, pos, id) -> { dlg.dismiss(); sendWink(pos); });
        dlg.show();
    }

    void sendWink(int i) {
        final String n = Winks.name(i);
        addLine(winkLine("You", n));
        queueWink(i);
        if (!isDemo) sendText("*wink:" + n + "*");
    }

    // ---------- toolbar actions ----------
    interface Done { void run(int code, String body); }
    static final int PICK_FILE = 42, TAKE_PHOTO = 43, TAKE_VIDEO = 44, IMPORT_WINKS = 45;

    /** Lets the user pick their own winks (.mco, .swf or .zip) and imports them into files/msn/winks. */
    void importWinks() {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try { startActivityForResult(i, IMPORT_WINKS); }
        catch (Exception e) { Toast.makeText(this, I18n.tr("No file picker is available on this device."), Toast.LENGTH_LONG).show(); }
    }

    void runWinkImport(final android.net.Uri uri) {
        Toast.makeText(this, I18n.tr("Importing winks..."), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File input = new File(getCacheDir(), "wink_input.bin");
            File work = new File(getCacheDir(), "wink_work");
            String msg;
            try {
                try (java.io.InputStream in = getContentResolver().openInputStream(uri); java.io.OutputStream out = new java.io.FileOutputStream(input)) {
                    if (in == null) throw new java.io.IOException(I18n.tr("The file could not be read."));
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                int n = com.msnmessenger.imp.WinkImporter.run(input, work, new File(AssetStore.dir(this), "winks"), null);
                Winks.reload();
                msg = I18n.tr("Winks imported:") + " " + n;
            } catch (Throwable t) {
                msg = I18n.tr("Import failed:") + " " + (t.getMessage() == null ? t.toString() : t.getMessage());
            } finally {
                input.delete();
            }
            final String m = msg;
            runOnUiThread(() -> Toast.makeText(ChatActivity.this, m, Toast.LENGTH_LONG).show());
        }).start();
    }
    boolean tttBusy;

    static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; } }

    void matrix(final String method, final String path, final byte[] data, final String type, final Done done) {
        new Thread(() -> {
            int code = -1; String out = "";
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(server.replaceAll("/$", "") + path).openConnection();
                c.setRequestMethod(method);
                c.setConnectTimeout(15000); c.setReadTimeout(60000);
                c.setRequestProperty("Authorization", "Bearer " + token);
                if (data != null) { c.setRequestProperty("Content-Type", type); c.setDoOutput(true); c.getOutputStream().write(data); }
                code = c.getResponseCode();
                InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
                if (is != null) { Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A"); out = s.hasNext() ? s.next() : ""; is.close(); }
                c.disconnect();
            } catch (Exception e) { out = String.valueOf(e.getMessage()); }
            final int fc = code; final String fo = out;
            runOnUiThread(() -> { if (!isFinishing() && done != null) done.run(fc, fo); });
        }).start();
    }

    void toolbarAction(String what) {
        switch (what) {
            case "Invite": inviteContact(); break;
            case "Send Files": sendFilesMenu(); break;
            case "Webcam": startCall(true); break;
            case "Call": startCall(false); break;
            case "Activities": showActivities(); break;
            case "Games": playTicTacToe(); break;
            case "Block": toggleBlock(); break;
            default: soon(what);
        }
    }

    void inviteContact() {
        final EditText et = new EditText(this);
        et.setHint(I18n.tr(isDemo ? "friend@hotmail.com" : "@user:server"));
        et.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle(I18n.tr("Invite someone to this conversation")).setView(et)
            .setPositiveButton(I18n.tr("OK"), (d, w) -> {
                final String who = et.getText().toString().trim();
                if (who.isEmpty()) return;
                if (isDemo) { addLine(I18n.tr("*" + who + " has been invited to the conversation.")); return; }
                try {
                    JSONObject j = new JSONObject(); j.put("user_id", who);
                    matrix("POST", "/_matrix/client/v3/rooms/" + enc(roomId) + "/invite", j.toString().getBytes("UTF-8"), "application/json",
                        (code, body) -> addLine(I18n.tr(code == 200 ? "*" + who + " has been invited to the conversation." : "*" + who + " could not be invited.")));
                } catch (Exception e) { addLine(I18n.tr("*" + who + " could not be invited.")); }
            }).setNegativeButton(I18n.tr("Cancel"), null).show();
    }

    void pickFile() {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(android.content.Intent.createChooser(i, "Send a File or Photo"), PICK_FILE); }
        catch (Exception e) { Toast.makeText(this, I18n.tr("No file browser available"), Toast.LENGTH_SHORT).show(); }
    }

    /** Send Files menu: take a photo, record a video, or pick an existing file. */
    void sendFilesMenu() {
        final String[] opts = {"Take a Photo", "Record a Video", "Send a File or Photo"};
        new AlertDialog.Builder(this).setTitle(I18n.tr("Send Files")).setItems(I18n.tr(opts), (d, w) -> {
            if (w == 0) openCamera(false); else if (w == 1) openCamera(true); else pickFile();
        }).setNegativeButton(I18n.tr("Cancel"), null).show();
    }

    /** Opens the phone's camera app; the result is saved in the app cache and sent like a picked file. */
    void openCamera(boolean video) {
        try {
            File dir = new File(getCacheDir(), "camera");
            dir.mkdirs();
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) if (System.currentTimeMillis() - f.lastModified() > 86400000L) f.delete();
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File f = new File(dir, (video ? "VID_" : "IMG_") + stamp + (video ? ".mp4" : ".jpg"));
            android.net.Uri u = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            getSharedPreferences("msn", MODE_PRIVATE).edit().putString("cam_file", f.getAbsolutePath()).apply();
            android.content.Intent i = new android.content.Intent(video ? android.provider.MediaStore.ACTION_VIDEO_CAPTURE : android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, u);
            i.setClipData(android.content.ClipData.newRawUri("", u));
            i.addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION | android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (video) { i.putExtra(android.provider.MediaStore.EXTRA_SIZE_LIMIT, 45L * 1024 * 1024); i.putExtra(android.provider.MediaStore.EXTRA_VIDEO_QUALITY, 1); }
            startActivityForResult(i, video ? TAKE_VIDEO : TAKE_PHOTO);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, I18n.tr("No camera app available"), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, I18n.tr("The camera could not be opened."), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK) return;
        if (req == IMPORT_WINKS) { if (data != null && data.getData() != null) runWinkImport(data.getData()); return; }
        final android.net.Uri uri;
        if (req == TAKE_PHOTO || req == TAKE_VIDEO) {
            String path = getSharedPreferences("msn", MODE_PRIVATE).getString("cam_file", null);
            File cf = path == null ? null : new File(path);
            if (cf != null && cf.length() > 0) {
                try { uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", cf); }
                catch (Exception e) { addLine(I18n.tr("*The photo or video could not be saved.")); return; }
            } else if (data != null && data.getData() != null) uri = data.getData();
            else { addLine(I18n.tr("*The photo or video could not be saved.")); return; }
        } else if (req == PICK_FILE && data != null && data.getData() != null) uri = data.getData();
        else return;
        String name = "file"; long size = -1;
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (n >= 0) name = c.getString(n);
                int s = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s);
            }
        } catch (Exception ignored) {}
        final String fname = name == null ? "file" : name;
        String t = getContentResolver().getType(uri);
        final String mime = t == null ? "application/octet-stream" : t;
        addLine(I18n.tr("*You are sending \"" + fname + "\"" + (size > 0 ? " (" + Math.max(1, size / 1024) + " KB)" : "") + "."));
        if (isDemo) {
            ui.postDelayed(() -> { if (!isFinishing()) { addLine(I18n.tr("*Transfer of \"" + fname + "\" is complete.")); playSound("vimdone"); } }, 2000);
            return;
        }
        new Thread(() -> {
            byte[] bytes;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384]; int r;
                while ((r = is.read(buf)) > 0) bo.write(buf, 0, r);
                bytes = bo.toByteArray();
            } catch (Exception e) {
                runOnUiThread(() -> addLine(I18n.tr("*The file could not be read.")));
                return;
            }
            final byte[] fb = bytes;
            final JSONObject vinfo = mime.startsWith("video/") ? videoInfo(uri) : null;
            runOnUiThread(() -> matrix("POST", "/_matrix/media/v3/upload?filename=" + enc(fname), fb, mime, (code, body) -> {
                String mxc = null;
                try { mxc = new JSONObject(body).optString("content_uri", null); } catch (Exception ignored) {}
                if (code != 200 || mxc == null) { addLine(I18n.tr("*Transfer of \"" + fname + "\" failed.")); return; }
                try {
                    JSONObject m = new JSONObject();
                    m.put("msgtype", mime.startsWith("image/") ? "m.image" : mime.startsWith("video/") ? "m.video" : "m.file");
                    m.put("body", fname); m.put("url", mxc);
                    JSONObject info = vinfo != null ? vinfo : new JSONObject(); info.put("mimetype", mime); info.put("size", fb.length); m.put("info", info);
                    matrix("PUT", "/_matrix/client/v3/rooms/" + enc(roomId) + "/send/m.room.message/txn_" + System.currentTimeMillis(),
                        m.toString().getBytes("UTF-8"), "application/json", (c2, b2) -> {
                            addLine(I18n.tr(c2 == 200 ? "*Transfer of \"" + fname + "\" is complete." : "*Transfer of \"" + fname + "\" failed."));
                            if (c2 == 200) playSound("vimdone");
                        });
                } catch (Exception e) { addLine(I18n.tr("*Transfer of \"" + fname + "\" failed.")); }
            }));
        }).start();
    }

    void startCall(final boolean video) {
        final String what = video ? "webcam conversation" : "voice conversation";
        addLine(I18n.tr("*You have asked to start a " + what + " with " + contactName + ". Waiting for a response..."));
        final MediaPlayer[] mp = {null};
        try {
            mp[0] = AssetStore.player(this, "outgoing");
            if (mp[0] != null) { mp[0].setLooping(true); mp[0].start(); }
        } catch (Exception ignored) {}
        final AlertDialog[] dlg = {null};
        final Runnable stop = () -> {
            if (mp[0] != null) { try { mp[0].stop(); } catch (Exception ignored) {} mp[0].release(); mp[0] = null; }
        };
        final Runnable noAnswer = () -> {
            if (dlg[0] == null || !dlg[0].isShowing()) return;
            dlg[0].dismiss(); stop.run(); playSound("hangup");
            addLine(I18n.tr("*" + contactName + " did not answer the " + what + "."));
        };
        ImageView iv = new ImageView(this);
        AssetStore.set(iv, video ? R.drawable.tb_webcam : R.drawable.tb_call);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        iv.setPadding(pad, pad, pad, pad);
        dlg[0] = new AlertDialog.Builder(this).setTitle(I18n.tr("Calling " + contactName + "...")).setView(iv).setCancelable(false)
            .setNegativeButton(I18n.tr("Hang Up"), (d, w) -> {
                ui.removeCallbacks(noAnswer); stop.run(); playSound("hangup");
                addLine(I18n.tr("*You have ended the " + what + "."));
            }).show();
        ui.postDelayed(noAnswer, 9000);
    }

    void showActivities() {
        final String[] acts = {"Send a Nudge", "Send a Wink", "Play Tic-Tac-Toe", "Insert an Emoticon", "Send a File or Photo"};
        new AlertDialog.Builder(this).setTitle(I18n.tr("Activities")).setItems(I18n.tr(acts), (d, w) -> {
            switch (w) {
                case 0: sendNudge(); break;
                case 1: pickWink(); break;
                case 2: playTicTacToe(); break;
                case 3: pickEmoticon(); break;
                default: sendFilesMenu();
            }
        }).setNegativeButton(I18n.tr("Cancel"), null).show();
    }

    void playTicTacToe() {
        final char[] b = new char[9];
        Arrays.fill(b, ' ');
        final Button[] cells = new Button[9];
        tttBusy = false;
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        grid.setPadding(24, 24, 24, 24);
        final TextView info = new TextView(this);
        info.setText(I18n.tr("You are X. Your move."));
        info.setTextColor(0xFF1E3287);
        info.setPadding(8, 0, 8, 16);
        grid.addView(info);
        int sz = Math.round(72 * getResources().getDisplayMetrics().density);
        for (int r = 0; r < 3; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER);
            for (int c = 0; c < 3; c++) {
                final int k = r * 3 + c;
                Button bt = new Button(this);
                bt.setTextSize(28);
                cells[k] = bt;
                bt.setOnClickListener(v -> tttMove(b, cells, info, k));
                row.addView(bt, new LinearLayout.LayoutParams(sz, sz));
            }
            grid.addView(row);
        }
        addLine(I18n.tr("*You have invited " + contactName + " to play Tic-Tac-Toe."));
        new AlertDialog.Builder(this).setTitle(I18n.tr("Tic-Tac-Toe with " + contactName)).setView(grid).setNegativeButton(I18n.tr("Close"), null).show();
    }

    void tttMove(final char[] b, final Button[] cells, final TextView info, int k) {
        if (tttBusy || b[k] != ' ' || tttWinner(b) != 0) return;
        b[k] = 'X'; cells[k].setText(I18n.tr("X")); cells[k].setTextColor(0xFF1E64C8);
        if (tttDone(b, info)) return;
        tttBusy = true;
        info.setText(I18n.tr(contactName + " is thinking..."));
        ui.postDelayed(() -> {
            tttBusy = false;
            int m = tttPick(b);
            if (m < 0) return;
            b[m] = 'O'; cells[m].setText(I18n.tr("O")); cells[m].setTextColor(0xFF2E9E2E);
            if (!tttDone(b, info)) info.setText(I18n.tr("Your move."));
        }, 700);
    }

    static char tttWinner(char[] b) {
        int[][] lines = {{0, 1, 2}, {3, 4, 5}, {6, 7, 8}, {0, 3, 6}, {1, 4, 7}, {2, 5, 8}, {0, 4, 8}, {2, 4, 6}};
        for (int[] l : lines) if (b[l[0]] != ' ' && b[l[0]] == b[l[1]] && b[l[1]] == b[l[2]]) return b[l[0]];
        for (char c : b) if (c == ' ') return 0;
        return 'D';
    }

    boolean tttDone(char[] b, TextView info) {
        char w = tttWinner(b);
        if (w == 0) return false;
        String msg = w == 'X' ? "You won Tic-Tac-Toe against " + contactName + "!"
            : w == 'O' ? contactName + " won Tic-Tac-Toe." : "Tic-Tac-Toe with " + contactName + " ended in a draw.";
        info.setText(I18n.tr(msg));
        addLine(I18n.tr("*" + msg));
        playSound(w == 'X' ? "vimdone" : "newalert");
        return true;
    }

    static int tttPick(char[] b) {
        for (char p : new char[]{'O', 'X'})
            for (int i = 0; i < 9; i++) if (b[i] == ' ') { b[i] = p; boolean w = tttWinner(b) == p; b[i] = ' '; if (w) return i; }
        if (b[4] == ' ') return 4;
        ArrayList<Integer> free = new ArrayList<>();
        for (int i = 0; i < 9; i++) if (b[i] == ' ') free.add(i);
        return free.isEmpty() ? -1 : free.get(new Random().nextInt(free.size()));
    }

    void toggleBlock() {
        final android.content.SharedPreferences sp = getSharedPreferences("msn_blocked", MODE_PRIVATE);
        final boolean blocked = sp.getBoolean(roomId, false);
        new AlertDialog.Builder(this).setTitle(I18n.tr(blocked ? "Unblock" : "Block"))
            .setMessage(I18n.tr(blocked ? "Unblock " + contactName + "? They will be able to see your status and send you messages."
                : "Block " + contactName + "? They won't be able to see when you are online or send you messages."))
            .setPositiveButton(I18n.tr(blocked ? "Unblock" : "Block"), (d, w) -> {
                sp.edit().putBoolean(roomId, !blocked).apply();
                addLine(I18n.tr(blocked ? "*" + contactName + " has been unblocked." : "*" + contactName + " has been blocked."));
                AssetStore.set(((ImageView) findViewById(R.id.ivTitlePresence)), blocked
                    ? MainActivity.presenceIcon(getIntent().getStringExtra("status")) : R.drawable.tb_block);
                if (!isDemo) setIgnored(!blocked);
            }).setNegativeButton(I18n.tr("Cancel"), null).show();
    }

    void setIgnored(final boolean ignore) {
        matrix("GET", "/_matrix/client/v3/rooms/" + enc(roomId) + "/joined_members", null, null, (code, body) -> {
            try {
                JSONObject joined = new JSONObject(body).getJSONObject("joined");
                final ArrayList<String> others = new ArrayList<>();
                Iterator<String> it = joined.keys();
                while (it.hasNext()) { String u = it.next(); if (!u.equals(userId)) others.add(u); }
                final String path = "/_matrix/client/v3/user/" + enc(userId) + "/account_data/m.ignored_user_list";
                matrix("GET", path, null, null, (c2, b2) -> {
                    try {
                        JSONObject root = c2 == 200 ? new JSONObject(b2) : new JSONObject();
                        JSONObject list = root.optJSONObject("ignored_users");
                        if (list == null) list = new JSONObject();
                        for (String u : others) { if (ignore) list.put(u, new JSONObject()); else list.remove(u); }
                        root.put("ignored_users", list);
                        matrix("PUT", path, root.toString().getBytes("UTF-8"), "application/json", null);
                    } catch (Exception ex) {}
                });
            } catch (Exception ex) {}
        });
    }

    /** Room of the chat window currently on screen, so the contact list doesn't alert for it. */
    static volatile String OPEN_ROOM = null;
    boolean resumed = false, winkBusy = false;
    volatile boolean historyOk = false;
    final java.util.ArrayDeque<Integer> winkQueue = new java.util.ArrayDeque<>();

    @Override
    protected void onResume() { super.onResume(); resumed = true; viewing = false; NotifyService.clearRoom(this, roomId); OPEN_ROOM = roomId; nextWink(); }

    @Override
    protected void onPause() { resumed = false; if (!viewing && roomId != null && roomId.equals(OPEN_ROOM)) OPEN_ROOM = null; super.onPause(); }

    /** Winks play one at a time, and wait until the window is visible. */
    void queueWink(int i) { if (i < 0) return; winkQueue.add(i); nextWink(); }

    void nextWink() {
        if (!resumed || winkBusy || isFinishing() || winkQueue.isEmpty()) return;
        winkBusy = true;
        Winks.play(this, winkQueue.poll(), () -> { winkBusy = false; ui.postDelayed(this::nextWink, 300); });
    }

    @Override
    protected void onDestroy() {
        live = false;
        stopVoice(false);
        stopVoicePlayback();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    void addLine(String line) {
        if (line != null && line.startsWith("*")) line = I18n.tr(line);
        messageTexts.add(line);
        adapter.notifyDataSetChanged();
        listMessages.setSelection(listMessages.getCount() - 1);
    }

    class MsgAdapter extends BaseAdapter {
        public int getCount() { return messageTexts.size(); }
        public Object getItem(int p) { return messageTexts.get(p); }
        public long getItemId(int p) { return p; }
        public View getView(int p, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(ChatActivity.this).inflate(R.layout.row_msg, parent, false);
            String line = messageTexts.get(p);
            TextView says = v.findViewById(R.id.tvSays), body = v.findViewById(R.id.tvBody);
            ImageView img = v.findViewById(R.id.ivImage);
            img.setVisibility(View.GONE); img.setTag(null); img.setOnClickListener(null); body.setVisibility(View.VISIBLE); body.setOnClickListener(null); body.setClickable(false); body.setOnLongClickListener(null); body.setLongClickable(false);
            body.setTypeface(android.graphics.Typeface.DEFAULT); body.setTextSize(14);
            body.setPaintFlags(body.getPaintFlags() & ~(android.graphics.Paint.UNDERLINE_TEXT_FLAG | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG));
            body.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
            TextView cap = v.findViewById(R.id.tvCaption);
            cap.setVisibility(View.GONE); cap.setOnClickListener(null);
            img.setMaxHeight(mediaH()); img.setMaxWidth(mediaW());
            img.setBackgroundResource(R.drawable.bg_tile_frame); img.setPadding(wdp(2), wdp(2), wdp(2), wdp(2));
            img.setScaleType(ImageView.ScaleType.FIT_START);
            ViewGroup.LayoutParams lp0 = img.getLayoutParams();
            lp0.width = ViewGroup.LayoutParams.WRAP_CONTENT; lp0.height = ViewGroup.LayoutParams.WRAP_CONTENT; img.setLayoutParams(lp0);
            if (line.startsWith(WINK)) {
                int bar = line.indexOf('|');
                int wi = -1;
                try { wi = Integer.parseInt(line.substring(WINK.length(), bar)); } catch (Exception ignored) {}
                says.setVisibility(View.GONE);
                body.setText(line.substring(bar + 1));
                body.setTextColor(0xFF808080); body.setPadding(0, 6, 0, 6);
                if (wi >= 0) {
                    final int fw = wi;
                    img.setVisibility(View.VISIBLE);
                    img.setBackground(null); img.setPadding(0, 0, 0, 0);
                    img.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    lp0.width = wdp(160); lp0.height = wdp(120); img.setLayoutParams(lp0);
                    Winks.setThumb(img, wi);
                    cap.setText(getString(R.string.wink_play, Winks.name(wi)));
                    cap.setWidth(wdp(160));
                    cap.setVisibility(View.VISIBLE);
                    View.OnClickListener rp = vv -> { if (!winkBusy && winkQueue.isEmpty()) queueWink(fw); };
                    img.setOnClickListener(rp); cap.setOnClickListener(rp); body.setOnClickListener(rp);
                }
                return v;
            }
            int vp = line.indexOf(VOICE);
            if (vp >= 0 && !isSys(line)) {
                String whoV = vp >= 2 ? line.substring(0, vp - 2) : "";
                String[] vf = line.substring(vp + VOICE.length()).split("\\|", 2);
                final String vsrc = vf[0];
                long dms = 0; try { dms = Long.parseLong(vf.length > 1 ? vf[1] : "0"); } catch (Exception ignored) {}
                boolean sameV = false;
                if (p > 0) { String prev = messageTexts.get(p - 1); int pc = prev.indexOf(": "); sameV = !isSys(prev) && pc > 0 && prev.substring(0, pc).equals(whoV); }
                says.setVisibility(sameV || whoV.isEmpty() ? View.GONE : View.VISIBLE);
                says.setText(I18n.tr(whoV + " says:"));
                body.setText((vsrc.equals(playingSrc) ? "\u25A0 " : "\u25B6 ") + getString(R.string.voice_line, mmss(dms)));
                body.setTextColor(0xFF1E3287); body.setPadding(wdp(14), wdp(2), 0, wdp(2));
                body.setCompoundDrawablesWithIntrinsicBounds(AssetStore.drawable(ChatActivity.this, R.drawable.ic_voiceclip), null, null, null); body.setCompoundDrawablePadding(wdp(4));
                body.setOnClickListener(vv -> playVoice(vsrc));
                if (vsrc.startsWith("mxc://")) body.setOnLongClickListener(vv -> { saveFile(vsrc, "Voice Clip.m4a", "audio/mp4"); return true; });
                return v;
            }
            int fp = line.indexOf(FILE);
            if (fp >= 0 && !isSys(line)) {
                String whoF = fp >= 2 ? line.substring(0, fp - 2) : "";
                String[] ff = line.substring(fp + FILE.length()).split("\\|", 4);
                final String fmxc = ff[0];
                final String fmime = ff.length > 1 ? ff[1] : "";
                long fsz = 0; try { fsz = Long.parseLong(ff.length > 2 ? ff[2] : "0"); } catch (Exception ignored) {}
                final String fnm = ff.length > 3 && !ff[3].isEmpty() ? ff[3] : "file";
                boolean sameF = false;
                if (p > 0) { String prev = messageTexts.get(p - 1); int pc = prev.indexOf(": "); sameF = !isSys(prev) && pc > 0 && prev.substring(0, pc).equals(whoF); }
                says.setVisibility(sameF || whoF.isEmpty() ? View.GONE : View.VISIBLE);
                says.setText(I18n.tr(whoF + " says:"));
                body.setText(fnm + (fsz > 0 ? "  (" + fmtSize(fsz) + ")" : "") + "\n" + getString(R.string.file_tap_save));
                body.setTextColor(0xFF1E3287); body.setPadding(wdp(14), wdp(2), 0, wdp(2));
                android.graphics.drawable.Drawable fic = AssetStore.drawable(ChatActivity.this, R.drawable.tb_sendfiles);
                if (fic != null) fic.setBounds(0, 0, wdp(24), wdp(24));
                body.setCompoundDrawables(fic, null, null, null); body.setCompoundDrawablePadding(wdp(6));
                body.setOnClickListener(vv -> saveFile(fmxc, fnm, fmime));
                return v;
            }
            int ip = line.indexOf(IMG);
            if (ip >= 0 && !line.startsWith("*")) {
                String who0 = ip >= 2 ? line.substring(0, ip - 2) : "";
                String[] mf = line.substring(ip + IMG.length()).split("\\|", 5);
                final String mxc = mf[0];
                final String kind = mf.length > 1 ? mf[1] : "image";
                final String mime0 = mf.length > 2 ? mf[2] : "";
                final String thumb = mf.length > 3 ? mf[3] : "";
                final String fname = mf.length > 4 ? mf[4] : "";
                final boolean vid = "video".equals(kind);
                boolean same0 = false;
                if (p > 0) { String prev = messageTexts.get(p - 1); int pc = prev.indexOf(": "); same0 = !isSys(prev) && pc > 0 && prev.substring(0, pc).equals(who0); }
                says.setVisibility(same0 || who0.isEmpty() ? View.GONE : View.VISIBLE);
                says.setText(I18n.tr(who0 + " says:"));
                img.setVisibility(View.VISIBLE);
                if (vid) {
                    int px0 = (int) (14 * getResources().getDisplayMetrics().density);
                    body.setText(I18n.tr("\u25B6 " + (fname.isEmpty() ? "Video" : fname) + "  (tap to play)"));
                    body.setTextColor(0xFF1E3287); body.setPadding(px0, 0, 0, 0);
                } else body.setVisibility(View.GONE);
                if (isDemo || server == null) AssetStore.set(img, R.drawable.pic_flower);
                else if (vid && !thumb.startsWith("mxc://")) AssetStore.set(img, R.drawable.tb_webcam);
                else Avatars.showImage(ChatActivity.this, img, server, token, vid ? thumb : mxc, R.drawable.tb_sendfiles, mediaW(), mediaH());
                View.OnClickListener open = vv -> openMedia(mxc, kind, mime0, fname);
                img.setOnClickListener(open);
                if (vid) body.setOnClickListener(open);
                return v;
            }
            if (line.startsWith("*")) {
                says.setVisibility(View.GONE);
                body.setText(line.substring(1)); body.setTextColor(0xFF808080); body.setPadding(0, 6, 0, 6);
                return v;
            }
            int c = line.indexOf(": ");
            String who = c > 0 ? line.substring(0, c) : "";
            String msg = c > 0 ? line.substring(c + 2) : line;
            boolean sameAsPrev = false;
            if (p > 0) { String prev = messageTexts.get(p - 1); int pc = prev.indexOf(": "); sameAsPrev = !isSys(prev) && pc > 0 && prev.substring(0, pc).equals(who); }
            says.setVisibility(sameAsPrev || who.isEmpty() ? View.GONE : View.VISIBLE);
            says.setText(I18n.tr(who + " says:"));
            MsnFont mf = null;
            if (msg.startsWith(FONT)) { int fe = msg.indexOf('\u0003', FONT.length()); if (fe > 0) { mf = MsnFont.parse(msg.substring(FONT.length(), fe)); msg = msg.substring(fe + 1); } }
            body.setTextColor(0xFF000000);
            if (mf != null) mf.apply(body);
            body.setText(Emoticons.render(ChatActivity.this, msg, emoSize(body)));
            int px = (int) (14 * getResources().getDisplayMetrics().density); body.setPadding(px, 0, 0, 0);
            return v;
        }
    }

    void loadDemoHistory() {
        Map<String, String[]> demos = new LinkedHashMap<>();
        demos.put("demo-1", new String[]{
            "John: Hey! How's it going? :)",
            "Me: Great! Just finished work. You?",
            "John: Same, finally free! (^)",
            "John: Want to grab a coffee later? (C)",
            "Me: Sure! See you at 5?",
            "John: Perfect! (h5)",
        });
        demos.put("demo-2", new String[]{
            "Sarah: Are you coming to the party on Saturday? (G)",
            "Me: I think so! What time does it start?",
            "Sarah: 8pm at Mike's place!",
            "Me: Awesome, I'll be there :D",
        });
        demos.put("demo-3", new String[]{
            "Mike: Busy with work rn, call you later (mp)",
            "Me: No worries, good luck!",
        });
        demos.put("demo-4", new String[]{
            "Lisa: Check out this new song I found! (8)",
            "Me: Oh nice, send me the link!",
            "Lisa: https://open.spotify.com/track/...",
            "Lisa: What do you think?",
            "Me: This is amazing! Adding to my playlist (8)",
        });
        demos.put("demo-5", new String[]{
            "Alex: Hey sorry I missed your call",
            "Me: No problem! I just wanted to ask about the project",
            "Alex: I'll send you the files tomorrow (Y)",
        });

        String[] msgs = demos.get(roomId);
        if (msgs != null) {
            messageTexts.clear();
            Collections.addAll(messageTexts, msgs);
        }
        runOnUiThread(() -> {
            adapter.notifyDataSetChanged();
            listMessages.setSelection(listMessages.getCount() - 1);
        });
    }

    void loadHistory() {
        new Thread(() -> {
            try {
                String enc = URLEncoder.encode(roomId, "UTF-8");
                String url = server.replaceAll("/$", "") + "/_matrix/client/v3/rooms/" + enc + "/messages?dir=b&limit=100";
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestMethod("GET");

                if (conn.getResponseCode() == 200) {
                    String text = new Scanner(conn.getInputStream()).useDelimiter("\\A").next();
                    JSONObject j = new JSONObject(text);
                    JSONArray chunk = j.optJSONArray("chunk");
                    if (chunk != null) {
                        ArrayList<String> msgs = new ArrayList<>();
                        lastWinkName = null; lastWinkEv = null;
                        for (int i = chunk.length() - 1; i >= 0; i--) {
                            JSONObject e = chunk.getJSONObject(i);
                            seen.add(e.optString("event_id", ""));
                            if ("m.room.message".equals(e.optString("type"))) {
                                JSONObject ct = e.optJSONObject("content"); String body = ct == null ? null : ct.optString("body");
                                String sender = e.optString("sender");
                                if (body != null && !body.isEmpty()) {
                                    String who = sender.equals(userId) ? "Me" : MainActivity.displayFromId(sender);
                                    if (sender.equals(userId)) { lastWinkName = null; lastWinkEv = null; }
                                    String il = imageLine(ct);
                                    if (il != null)
                                        msgs.add(who + ": " + il);
                                    else if (body.startsWith("*wink:") && body.endsWith("*") && body.length() > 7) {
                                        String wn = body.substring(6, body.length() - 1);
                                        msgs.add(winkLine(who.equals("Me") ? "You" : who, wn));
                                        if (!who.equals("Me")) { lastWinkName = wn; lastWinkEv = e.optString("event_id", ""); }
                                    }
                                    else if (body.equals("*nudge*"))
                                        msgs.add(who.equals("Me") ? "*You have just sent a nudge." : "*" + who + " has just sent you a nudge.");
                                    else msgs.add(who + ": " + fontPrefix(ct) + body);
                                }
                            }
                        }
                        messageTexts.clear();
                        messageTexts.addAll(msgs);
                        historyOk = true;
                        final String mwN = lastWinkName, mwE = lastWinkEv;
                        runOnUiThread(() -> {
                            adapter.notifyDataSetChanged();
                            listMessages.setSelection(listMessages.getCount() - 1);
                            if (!isFinishing()) playMissedWink(mwN, mwE);
                        });
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_SHORT).show());
            }
            if (live && !isFinishing()) startLive();
        }).start();
    }

    void sendMessage() {
        String text = etMessage.getText().toString().trim();
        if (text.isEmpty()) return;
        etMessage.setText(I18n.tr(""));
        addLine(I18n.tr("Me: " + myFont().marker() + text));
        if (isDemo) { sendDemoReply(); return; }
        sendFormatted(text);
    }

    volatile boolean live = true;
    final java.util.Set<String> seen = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    final java.util.Set<String> sentTxns = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    int winkIndex(String name) {
        Winks.load(this);
        for (int k = 0; k < Winks.count(); k++) if (Winks.name(k).equals(name)) return k;
        return -1;
    }

    /** Long-polls /sync for this room so new messages, winks, nudges and typing appear in real time. */
    void startLive() {
        new Thread(() -> {
            String since = null;
            while (live && !isFinishing()) {
                try {
                    boolean firstSync = since == null;
                    String filter = "{\"room\":{\"rooms\":[" + JSONObject.quote(roomId) + "],\"timeline\":{\"limit\":" + (firstSync ? 30 : 50)
                        + "},\"state\":{\"lazy_load_members\":true}},\"presence\":{\"types\":[\"m.presence\"]},\"account_data\":{\"not_types\":[\"*\"]}}";
                    String url = server.replaceAll("/$", "") + "/_matrix/client/v3/sync?timeout=" + (firstSync ? 0 : 25000)
                        + "&set_presence=" + MainActivity.presParam(getSharedPreferences("msn", MODE_PRIVATE).getString("my_status", "Online"))
                        + "&filter=" + URLEncoder.encode(filter, "UTF-8")
                        + (since != null ? "&since=" + URLEncoder.encode(since, "UTF-8") : "");
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(40000);
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                    if (conn.getResponseCode() != 200) { conn.disconnect(); Thread.sleep(4000); continue; }
                    String t = new Scanner(conn.getInputStream(), "UTF-8").useDelimiter("\\A").next();
                    JSONObject j = new JSONObject(t);
                    since = j.optString("next_batch", since);
                    final String peerId = getIntent().getStringExtra("peer");
                    JSONObject prs = j.optJSONObject("presence");
                    JSONArray pe = prs == null ? null : prs.optJSONArray("events");
                    if (pe != null && peerId != null) for (int k = 0; k < pe.length(); k++) {
                        JSONObject e = pe.getJSONObject(k);
                        if (!peerId.equals(e.optString("sender"))) continue;
                        JSONObject c = e.optJSONObject("content");
                        if (c == null) continue;
                        final String st = MainActivity.statusFrom(c);
                        runOnUiThread(() -> {
                            if (isFinishing()) return;
                            tvChatTitle.setText(contactName + " (" + I18n.tr(st) + ")");
                            AssetStore.set(((ImageView) findViewById(R.id.ivTitlePresence)), MainActivity.presenceIcon(st));
                            MainActivity.frameFor((View) findViewById(R.id.ivTheirTile).getParent(), st);
                        });
                    }
                    JSONObject rooms = j.optJSONObject("rooms");
                    JSONObject join = rooms == null ? null : rooms.optJSONObject("join");
                    JSONObject r = join == null ? null : join.optJSONObject(roomId);
                    if (r == null) continue;
                    JSONObject eph = r.optJSONObject("ephemeral");
                    JSONArray ee = eph == null ? null : eph.optJSONArray("events");
                    if (ee != null) for (int k = 0; k < ee.length(); k++) {
                        JSONObject e = ee.getJSONObject(k);
                        if (!"m.typing".equals(e.optString("type"))) continue;
                        JSONObject c = e.optJSONObject("content");
                        JSONArray ids = c == null ? null : c.optJSONArray("user_ids");
                        String typer = null;
                        if (ids != null) for (int q = 0; q < ids.length(); q++) { String u = ids.optString(q); if (!u.equals(userId)) { typer = u; break; } }
                        final String tp = typer;
                        runOnUiThread(() -> { if (tp != null) tvTyping.setText(I18n.tr(MainActivity.displayFromId(tp) + " is typing a message...")); });
                    }
                    JSONObject tl = r.optJSONObject("timeline");
                    JSONArray ev = tl == null ? null : tl.optJSONArray("events");
                    if (ev != null) for (int k = 0; k < ev.length(); k++) {
                        JSONObject e = ev.getJSONObject(k);
                        if (firstSync && !historyOk) { seen.add(e.optString("event_id", "")); continue; }
                        handleLive(e);
                    }
                } catch (Exception e) {
                    if (!live) return;
                    try { Thread.sleep(4000); } catch (InterruptedException ie) { return; }
                }
            }
        }).start();
    }

    void handleLive(JSONObject e) {
        String id = e.optString("event_id", "");
        if (!id.isEmpty() && !seen.add(id)) return;
        JSONObject us = e.optJSONObject("unsigned");
        String txn = us == null ? "" : us.optString("transaction_id", "");
        if ("m.room.member".equals(e.optString("type"))) { memberChanged(e); return; }
        if (!txn.isEmpty() && sentTxns.contains(txn)) return;
        if (!"m.room.message".equals(e.optString("type"))) return;
        JSONObject c = e.optJSONObject("content");
        final String body = c == null ? "" : c.optString("body", "");
        if (body.isEmpty()) return;
        final boolean mine = e.optString("sender").equals(userId);
        final String who = mine ? "Me" : MainActivity.displayFromId(e.optString("sender"));
        final String imgLine = imageLine(c);
        runOnUiThread(() -> {
            if (isFinishing()) return;
            if (imgLine != null) {
                addLine(I18n.tr(who + ": " + imgLine));
                if (!mine) playSound("type");
            } else if (body.startsWith("*wink:") && body.endsWith("*") && body.length() > 7) {
                String wn = body.substring(6, body.length() - 1);
                addLine(winkLine(mine ? "You" : who, wn));
                if (!mine) { markWinkPlayed(id); queueWink(winkIndex(wn)); }
            } else if (body.equals("*nudge*")) {
                addLine(I18n.tr(mine ? "*You have just sent a nudge." : "*" + who + " has just sent you a nudge."));
                if (!mine) { playSound("nudge"); shake(); }
            } else {
                addLine(I18n.tr(who + ": " + fontPrefix(c) + body));
                if (!mine) {
                    playSound("type");
                    tvTyping.setText(I18n.tr("Last message received at " + android.text.format.DateFormat.getTimeFormat(this).format(new Date()) + "."));
                }
            }
        });
    }

    static final String IMG = "\u0001img:";
    /** Voice clip rows: VOICE + (mxc:// or file://) + "|" + duration ms. */
    static final String VOICE = "\u0004voice:";
    /** File rows: FILE + mxc|mime|size|name. */
    static final String FILE = "\u0005file:";
    /** Font marker inside a text line: FONT + face|b|i|u|s|pt|rrggbb + \u0003. */
    static final String FONT = "\u0003f:";
    static final String[] FONT_FACES = {"Segoe UI", "Arial", "Tahoma", "Verdana", "Times New Roman", "Georgia", "Courier New", "Comic Sans MS", "Lucida Handwriting", "Arial Narrow", "Calibri Light"};
    static final int[] FONT_PTS = {8, 9, 10, 11, 12, 14, 16, 18, 20};
    static final int[] FONT_COLORS = {0xFF000000, 0xFF800000, 0xFF008000, 0xFF808000, 0xFF000080, 0xFF800080, 0xFF008080, 0xFF808080,
        0xFFC0C0C0, 0xFFFF0000, 0xFF00A000, 0xFFFF8000, 0xFF0000FF, 0xFFFF00FF, 0xFF0099CC, 0xFF663300};

    static android.graphics.Typeface face(String n, boolean b, boolean i) {
        String fam;
        switch (n == null ? "" : n) {
            case "Times New Roman": case "Georgia": fam = "serif"; break;
            case "Courier New": fam = "monospace"; break;
            case "Comic Sans MS": fam = "casual"; break;
            case "Lucida Handwriting": fam = "cursive"; break;
            case "Arial Narrow": fam = "sans-serif-condensed"; break;
            case "Calibri Light": fam = "sans-serif-light"; break;
            default: fam = "sans-serif";
        }
        return android.graphics.Typeface.create(fam, (b ? android.graphics.Typeface.BOLD : 0) | (i ? android.graphics.Typeface.ITALIC : 0));
    }

    /** MSN 8 style message font: face, bold/italic, underline/strikeout, size in points and colour. */
    static class MsnFont {
        String face = "Segoe UI"; boolean b, i, u, s; int pt = 10; int color = 0xFF000000;
        static int clamp(int p) { return Math.max(8, Math.min(20, p)); }
        static String clean(String f) { return f == null ? "Segoe UI" : f.replace("|", "").replace("\u0003", ""); }
        static MsnFont load(android.content.SharedPreferences p) {
            MsnFont f = new MsnFont();
            f.face = clean(p.getString("font_face", f.face)); f.b = p.getBoolean("font_b", false); f.i = p.getBoolean("font_i", false);
            f.u = p.getBoolean("font_u", false); f.s = p.getBoolean("font_s", false); f.pt = clamp(p.getInt("font_pt", 10)); f.color = p.getInt("font_color", 0xFF000000);
            return f;
        }
        void save(android.content.SharedPreferences p) {
            p.edit().putString("font_face", face).putBoolean("font_b", b).putBoolean("font_i", i).putBoolean("font_u", u)
                .putBoolean("font_s", s).putInt("font_pt", pt).putInt("font_color", color).apply();
        }
        boolean isDefault() { return "Segoe UI".equals(face) && !b && !i && !u && !s && pt == 10 && (color & 0xFFFFFF) == 0; }
        String hex() { return String.format(Locale.US, "%06x", color & 0xFFFFFF); }
        String marker() { return FONT + clean(face) + "|" + (b ? 1 : 0) + "|" + (i ? 1 : 0) + "|" + (u ? 1 : 0) + "|" + (s ? 1 : 0) + "|" + pt + "|" + hex() + "\u0003"; }
        static MsnFont parse(String m) {
            MsnFont f = new MsnFont();
            try {
                String[] a = m.split("\\|");
                f.face = clean(a[0]); f.b = "1".equals(a[1]); f.i = "1".equals(a[2]); f.u = "1".equals(a[3]); f.s = "1".equals(a[4]);
                f.pt = clamp(Integer.parseInt(a[5])); f.color = 0xFF000000 | Integer.parseInt(a[6], 16);
            } catch (Exception ignored) {}
            return f;
        }
        JSONObject json() throws org.json.JSONException {
            JSONObject o = new JSONObject();
            o.put("face", face); o.put("bold", b); o.put("italic", i); o.put("underline", u); o.put("strike", s); o.put("pt", pt); o.put("color", "#" + hex());
            return o;
        }
        static MsnFont fromJson(JSONObject o) {
            MsnFont f = new MsnFont();
            f.face = clean(o.optString("face", f.face)); f.b = o.optBoolean("bold"); f.i = o.optBoolean("italic");
            f.u = o.optBoolean("underline"); f.s = o.optBoolean("strike"); f.pt = clamp(o.optInt("pt", 10));
            try { f.color = 0xFF000000 | Integer.parseInt(o.optString("color", "#000000").replace("#", ""), 16); } catch (Exception ignored) {}
            return f;
        }
        void apply(TextView t) {
            t.setTypeface(face(face, b, i));
            t.setTextSize(pt * 1.4f);
            t.setTextColor(color);
            int fl = t.getPaintFlags() & ~(android.graphics.Paint.UNDERLINE_TEXT_FLAG | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            if (u) fl |= android.graphics.Paint.UNDERLINE_TEXT_FLAG;
            if (s) fl |= android.graphics.Paint.STRIKE_THRU_TEXT_FLAG;
            t.setPaintFlags(fl);
        }
        /** HTML for other Matrix apps such as Element. */
        String html(String text) {
            String h = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("\n", "<br>");
            if (b) h = "<b>" + h + "</b>";
            if (i) h = "<i>" + h + "</i>";
            if (u) h = "<u>" + h + "</u>";
            if (s) h = "<del>" + h + "</del>";
            if ((color & 0xFFFFFF) != 0) h = "<font color=\"#" + hex() + "\" data-mx-color=\"#" + hex() + "\">" + h + "</font>";
            return h;
        }
    }


    @Override
    public void onUserInteraction() { super.onUserInteraction(); MainActivity.userActive(); }

    MsnFont myFont() { return MsnFont.load(getSharedPreferences("msn", MODE_PRIVATE)); }

    static String fontPrefix(JSONObject ct) {
        JSONObject fo = ct == null ? null : ct.optJSONObject("com.msnmessenger.font");
        return fo == null ? "" : MsnFont.fromJson(fo).marker();
    }

    TextView fLabel(int res) {
        TextView t = new TextView(this); t.setText(res); t.setTextColor(0xFF1E3287); t.setTextSize(13);
        t.setPadding(0, wdp(8), 0, wdp(2)); return t;
    }
    CheckBox fCheck(int res, boolean on) { CheckBox c = new CheckBox(this); c.setText(res); c.setChecked(on); c.setPadding(0, 0, wdp(12), 0); return c; }
    LinearLayout fRow() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    android.graphics.drawable.GradientDrawable swatch(int c, boolean sel) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(c); g.setCornerRadius(wdp(3)); g.setStroke(sel ? wdp(3) : wdp(1), sel ? 0xFF1E3287 : 0xFF8FB3D9); return g;
    }

    /** MSN 8 "Change Font" dialog. */
    void pickFont() {
        final MsnFont f = myFont();
        final int pad = wdp(14);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(pad, wdp(6), pad, 0);
        final TextView sample = new TextView(this);
        final Runnable upd = () -> { f.apply(sample); sample.setText("AaBbYyZz :)"); };
        col.addView(fLabel(R.string.font_face));
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> fa = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, FONT_FACES) {
            @Override public View getView(int p, View v, ViewGroup g) { TextView t = (TextView) super.getView(p, v, g); t.setTypeface(face(FONT_FACES[p], false, false)); return t; }
            @Override public View getDropDownView(int p, View v, ViewGroup g) { TextView t = (TextView) super.getDropDownView(p, v, g); t.setTypeface(face(FONT_FACES[p], false, false)); return t; }
        };
        fa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(fa);
        sp.setSelection(Math.max(0, Arrays.asList(FONT_FACES).indexOf(f.face)));
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> a, View v, int p, long id) { f.face = FONT_FACES[p]; upd.run(); }
            public void onNothingSelected(AdapterView<?> a) {}
        });
        col.addView(sp);
        col.addView(fLabel(R.string.font_style));
        LinearLayout r1 = fRow();
        CheckBox cB = fCheck(R.string.font_bold, f.b); cB.setOnCheckedChangeListener((x, on) -> { f.b = on; upd.run(); }); r1.addView(cB);
        CheckBox cI = fCheck(R.string.font_italic, f.i); cI.setOnCheckedChangeListener((x, on) -> { f.i = on; upd.run(); }); r1.addView(cI);
        col.addView(r1);
        col.addView(fLabel(R.string.font_effects));
        LinearLayout r2 = fRow();
        CheckBox cS = fCheck(R.string.font_strike, f.s); cS.setOnCheckedChangeListener((x, on) -> { f.s = on; upd.run(); }); r2.addView(cS);
        CheckBox cU = fCheck(R.string.font_underline, f.u); cU.setOnCheckedChangeListener((x, on) -> { f.u = on; upd.run(); }); r2.addView(cU);
        col.addView(r2);
        col.addView(fLabel(R.string.font_size));
        String[] pts = new String[FONT_PTS.length]; int ps = 2;
        for (int k = 0; k < FONT_PTS.length; k++) { pts[k] = String.valueOf(FONT_PTS[k]); if (FONT_PTS[k] == f.pt) ps = k; }
        Spinner ss = new Spinner(this);
        ArrayAdapter<String> sa = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, pts);
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        ss.setAdapter(sa); ss.setSelection(ps);
        ss.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> a, View v, int p, long id) { f.pt = FONT_PTS[p]; upd.run(); }
            public void onNothingSelected(AdapterView<?> a) {}
        });
        col.addView(ss);
        col.addView(fLabel(R.string.font_color));
        final ArrayList<View> sws = new ArrayList<>();
        final Runnable paintSw = () -> { for (int k = 0; k < sws.size(); k++) sws.get(k).setBackground(swatch(FONT_COLORS[k], FONT_COLORS[k] == f.color)); };
        LinearLayout cr = fRow();
        for (int k = 0; k < FONT_COLORS.length; k++) {
            if (k % 8 == 0) { cr = fRow(); col.addView(cr); }
            View sw = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(wdp(26), wdp(26)); lp.setMargins(wdp(2), wdp(2), wdp(2), wdp(2));
            final int cc = FONT_COLORS[k];
            sw.setOnClickListener(x -> { f.color = cc; paintSw.run(); upd.run(); });
            cr.addView(sw, lp); sws.add(sw);
        }
        paintSw.run();
        col.addView(fLabel(R.string.font_sample));
        sample.setBackgroundResource(R.drawable.bg_history); sample.setPadding(pad, wdp(8), pad, wdp(8));
        sample.setGravity(android.view.Gravity.CENTER); sample.setMinHeight(wdp(56));
        col.addView(sample, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        upd.run();
        final CheckBox cEmo = new CheckBox(this); cEmo.setText(R.string.qw_show_emo);
        cEmo.setChecked(getSharedPreferences("msn", MODE_PRIVATE).getBoolean("show_emoticons", true));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); elp.topMargin = wdp(10);
        col.addView(cEmo, elp);
        ScrollView sv = new ScrollView(this); sv.addView(col);
        new AlertDialog.Builder(this).setTitle(R.string.font_title).setView(sv)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                f.save(getSharedPreferences("msn", MODE_PRIVATE)); f.apply(etMessage);
                getSharedPreferences("msn", MODE_PRIVATE).edit().putBoolean("show_emoticons", cEmo.isChecked()).apply();
                if (adapter != null) adapter.notifyDataSetChanged();
                String curT = etMessage.getText().toString(); etMessage.setText(curT); etMessage.setSelection(curT.length());
            })
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    void sendContent(final JSONObject body) {
        new Thread(() -> {
            try {
                String enc = URLEncoder.encode(roomId, "UTF-8");
                String txn = "txn_" + System.currentTimeMillis() + "_" + System.nanoTime();
                sentTxns.add(txn);
                HttpURLConnection conn = (HttpURLConnection) new URL(server.replaceAll("/$", "") + "/_matrix/client/v3/rooms/" + enc + "/send/m.room.message/" + txn).openConnection();
                conn.setRequestMethod("PUT");
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.getOutputStream().write(body.toString().getBytes("UTF-8"));
                final int code = conn.getResponseCode();
                if (code != 200) runOnUiThread(() -> Toast.makeText(this, I18n.tr("Send failed: " + code), Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /** Sends text with the chosen font, plus HTML so Element shows colour and style. */
    void sendFormatted(String text) {
        try {
            MsnFont f = myFont();
            JSONObject b = new JSONObject();
            b.put("msgtype", "m.text"); b.put("body", text);
            b.put("com.msnmessenger.font", f.json());
            if (!f.isDefault()) { b.put("format", "org.matrix.custom.html"); b.put("formatted_body", f.html(text)); }
            sendContent(b);
        } catch (Exception e) { sendText(text); }
    }

    static String mmss(long ms) { long s = Math.round(ms / 1000.0); return (s / 60) + ":" + String.format(Locale.US, "%02d", s % 60); }

    // ---- Voice clips (MSN 8: hold the Voice Clip button to talk, up to 15 seconds)
    android.media.MediaRecorder rec; File recFile; long recStart; CharSequence recPrev;
    MediaPlayer voicePlayer; String playingSrc;
    final Runnable recTick = new Runnable() {
        public void run() {
            if (rec == null) return;
            tvTyping.setText(getString(R.string.voice_rec, mmss(System.currentTimeMillis() - recStart)));
            ui.postDelayed(this, 250);
        }
    };

    void startVoice() {
        if (rec != null) return;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 77); return;
        }
        stopVoicePlayback();
        try {
            recFile = new File(getCacheDir(), "voice_" + System.currentTimeMillis() + ".m4a");
            android.media.MediaRecorder r = new android.media.MediaRecorder();
            r.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
            r.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
            r.setAudioSamplingRate(44100); r.setAudioEncodingBitRate(64000); r.setAudioChannels(1);
            r.setMaxDuration(15000);
            r.setOutputFile(recFile.getPath());
            r.setOnInfoListener((mr, what, extra) -> { if (what == android.media.MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) ui.post(() -> stopVoice(true)); });
            r.prepare(); r.start();
            rec = r; recStart = System.currentTimeMillis();
            recPrev = tvTyping.getText(); tvTyping.setVisibility(View.VISIBLE);
            ui.post(recTick);
        } catch (Exception e) {
            rec = null;
            Toast.makeText(this, R.string.voice_fail, Toast.LENGTH_SHORT).show();
        }
    }

    void stopVoice(boolean send) {
        if (rec == null) return;
        android.media.MediaRecorder r = rec; rec = null;
        ui.removeCallbacks(recTick);
        tvTyping.setText(recPrev == null ? "" : recPrev);
        long dur = Math.min(15000, System.currentTimeMillis() - recStart);
        boolean ok = true;
        try { r.stop(); } catch (RuntimeException e) { ok = false; }
        r.release();
        if (!send || !ok || dur < 700) {
            recFile.delete();
            if (send) Toast.makeText(this, R.string.voice_short, Toast.LENGTH_SHORT).show();
            return;
        }
        sendVoice(recFile, dur);
    }

    void sendVoice(final File f, final long dur) {
        addLine(I18n.tr("Me: " + VOICE + "file://" + f.getPath() + "|" + dur));
        if (isDemo || server == null) return;
        new Thread(() -> {
            try {
                byte[] data = Avatars.readAll(new FileInputStream(f));
                String mxc = uploadSync(data, "audio/mp4", "Voice Clip.m4a");
                if (mxc == null) throw new IOException("upload failed");
                JSONObject info = new JSONObject(); info.put("mimetype", "audio/mp4"); info.put("size", data.length); info.put("duration", dur);
                JSONObject b = new JSONObject();
                b.put("msgtype", "m.audio"); b.put("body", "Voice Clip.m4a"); b.put("url", mxc); b.put("info", info);
                JSONObject au = new JSONObject(); au.put("duration", dur);
                b.put("org.matrix.msc1767.audio", au); b.put("org.matrix.msc3245.voice", new JSONObject());
                sendContent(b);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, R.string.voice_fail, Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    void stopVoicePlayback() {
        if (voicePlayer != null) { try { voicePlayer.stop(); } catch (Exception ignored) {} voicePlayer.release(); voicePlayer = null; }
        if (playingSrc != null) { playingSrc = null; if (adapter != null) adapter.notifyDataSetChanged(); }
    }

    void voiceFailed() {
        playingSrc = null; if (adapter != null) adapter.notifyDataSetChanged();
        Toast.makeText(this, R.string.voice_play_fail, Toast.LENGTH_SHORT).show();
    }

    /** Tap a voice clip to play it; tap again to stop. */
    void playVoice(final String src) {
        if (src.equals(playingSrc)) { stopVoicePlayback(); return; }
        stopVoicePlayback();
        playingSrc = src; adapter.notifyDataSetChanged();
        new Thread(() -> {
            File vf = null;
            try {
                if (src.startsWith("file://")) vf = new File(src.substring(7));
                else if (src.startsWith("mxc://") && server != null) {
                    File dir = new File(getCacheDir(), "voice"); dir.mkdirs();
                    vf = new File(dir, Integer.toHexString(src.hashCode()) + ".audio");
                    if (vf.length() == 0) {
                        String path = src.substring(6), base = server.replaceAll("/+$", "");
                        boolean got = false;
                        for (String u : new String[]{base + "/_matrix/client/v1/media/download/" + path, base + "/_matrix/media/v3/download/" + path}) {
                            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                            c.setConnectTimeout(10000); c.setReadTimeout(30000);
                            c.setRequestProperty("Authorization", "Bearer " + token);
                            if (c.getResponseCode() != 200) { c.disconnect(); continue; }
                            byte[] d = Avatars.readAll(c.getInputStream());
                            try (FileOutputStream o = new FileOutputStream(vf)) { o.write(d); }
                            got = true; break;
                        }
                        if (!got) vf = null;
                    }
                }
            } catch (Exception e) { vf = null; }
            final File ff = vf;
            runOnUiThread(() -> {
                if (isFinishing() || !src.equals(playingSrc)) return;
                if (ff == null || !ff.exists()) { voiceFailed(); return; }
                try {
                    MediaPlayer mp = new MediaPlayer();
                    mp.setDataSource(ff.getPath());
                    mp.setOnCompletionListener(m -> stopVoicePlayback());
                    mp.prepare(); mp.start();
                    voicePlayer = mp;
                } catch (Exception e) { voiceFailed(); }
            });
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == 78) { Runnable ps = pendingSave; pendingSave = null; if (ps != null && res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) ps.run(); else if (ps != null) Toast.makeText(this, I18n.tr("Storage permission is needed to save files."), Toast.LENGTH_LONG).show(); return; }
        if (req == 77) Toast.makeText(this, res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED ? R.string.voice_short : R.string.voice_perm, Toast.LENGTH_SHORT).show();
    }
    /** Wink rows: WINK + index + "|" + display text. Shown with a tappable thumbnail. */
    static final String WINK = "\u0002wink:";
    static boolean isSys(String s) { return s.startsWith("*") || s.startsWith(WINK); }
    int wdp(int d) { return (int) (d * getResources().getDisplayMetrics().density); }
    /** Largest inline picture/video preview: about 70% of the screen width, up to 300dp wide and 340dp tall. */
    int mediaW() { return Math.min(wdp(300), Math.round(getResources().getDisplayMetrics().widthPixels * 0.70f)); }
    int mediaH() { return wdp(340); }
    String lastWinkName, lastWinkEv;

    String winkLine(String who, String name) {
        String t = I18n.tr("*" + who + " sent the wink \"" + name + "\".");
        if (t.startsWith("*")) t = t.substring(1);
        return WINK + winkIndex(name) + "|" + t;
    }

    void markWinkPlayed(String ev) {
        if (ev != null && !ev.isEmpty()) getSharedPreferences("msn_winks", MODE_PRIVATE).edit().putString("played_" + roomId, ev).apply();
    }

    /** Plays the latest wink the contact sent before this chat was opened, once. */
    void playMissedWink(String name, String ev) {
        if (name == null || ev == null || ev.isEmpty()) return;
        if (ev.equals(getSharedPreferences("msn_winks", MODE_PRIVATE).getString("played_" + roomId, ""))) return;
        markWinkPlayed(ev);
        queueWink(winkIndex(name));
    }

    /** Returns an image marker line for m.image messages, or null for anything else. */
    static String imageLine(JSONObject c) {
        if (c == null) return null;
        String mt = c.optString("msgtype");
        if ("m.audio".equals(mt)) {
            String au = c.optString("url", "");
            if (!au.startsWith("mxc://")) return null;
            JSONObject ai = c.optJSONObject("info");
            return VOICE + au + "|" + (ai == null ? 0 : ai.optLong("duration", 0));
        }
        if ("m.file".equals(mt)) {
            String fu = c.optString("url", "");
            if (!fu.startsWith("mxc://")) return null;
            JSONObject fi = c.optJSONObject("info");
            String fm = fi == null ? "" : fi.optString("mimetype", "").replace("|", "");
            long fs = fi == null ? 0 : fi.optLong("size", 0);
            return FILE + fu + "|" + fm + "|" + fs + "|" + c.optString("body", "file").replace('\n', ' ');
        }
        boolean vid = "m.video".equals(mt);
        if (!vid && !"m.image".equals(mt)) return null;
        JSONObject inf = c.optJSONObject("info");
        String url = c.optString("url", "");
        String thumb = inf == null ? "" : inf.optString("thumbnail_url", "");
        if (!url.startsWith("mxc://")) { if (vid) return null; url = thumb; }
        if (!url.startsWith("mxc://")) return null;
        String mime = inf == null ? "" : inf.optString("mimetype", "").replace("|", "");
        String name = c.optString("body", vid ? "video" : "image").replace('\n', ' ');
        return IMG + url + "|" + (vid ? "video" : "image") + "|" + mime + "|" + (thumb.startsWith("mxc://") ? thumb : "") + "|" + name;
    }

    Runnable pendingSave;

    /** Saves any shared file to Downloads/MSN Messenger. */
    void saveFile(final String mxc, final String name, final String mime) {
        if (isDemo || server == null) { Toast.makeText(this, I18n.tr("Demo mode: sign in to view and save shared files."), Toast.LENGTH_SHORT).show(); return; }
        if (android.os.Build.VERSION.SDK_INT < 29 && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingSave = () -> saveFile(mxc, name, mime);
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 78);
            return;
        }
        MediaViewerActivity.download(this, server, token, mxc, name, mime);
    }

    static String fmtSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return Math.round(b / 1024.0) + " KB";
        return String.format(java.util.Locale.getDefault(), "%.1f MB", b / (1024.0 * 1024.0));
    }

    boolean viewing = false;

    /** Opens a picture or video full screen, with a Save button. */
    void openMedia(String mxc, String kind, String mime, String name) {
        if (isDemo || server == null) { Toast.makeText(this, I18n.tr("Demo mode: sign in to view and save shared files."), Toast.LENGTH_SHORT).show(); return; }
        android.content.Intent i = new android.content.Intent(this, MediaViewerActivity.class);
        i.putExtra("server", server); i.putExtra("token", token); i.putExtra("mxc", mxc);
        i.putExtra("kind", kind); i.putExtra("mime", mime); i.putExtra("name", name);
        viewing = true;
        startActivity(i);
    }

    /** Uploads bytes to the media repository and returns the mxc URL, or null. Runs on a background thread. */
    String uploadSync(byte[] data, String mime, String name) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(server.replaceAll("/+$", "") + "/_matrix/media/v3/upload?filename=" + URLEncoder.encode(name, "UTF-8")).openConnection();
            conn.setRequestMethod("POST"); conn.setDoOutput(true);
            conn.setConnectTimeout(15000); conn.setReadTimeout(60000);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", mime);
            conn.getOutputStream().write(data);
            if (conn.getResponseCode() != 200) return null;
            String t = new Scanner(conn.getInputStream(), "UTF-8").useDelimiter("\\A").next();
            String m = new JSONObject(t).optString("content_uri", "");
            return m.startsWith("mxc://") ? m : null;
        } catch (Exception e) { return null; }
    }

    /** Video size, length and an uploaded preview frame, so the video shows properly here and in Element. */
    JSONObject videoInfo(android.net.Uri uri) {
        JSONObject info = new JSONObject();
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(this, uri);
            String d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) info.put("duration", Long.parseLong(d));
            android.graphics.Bitmap f = r.getFrameAtTime(0);
            if (f != null) {
                info.put("w", f.getWidth()); info.put("h", f.getHeight());
                android.graphics.Bitmap s = f;
                int big = Math.max(f.getWidth(), f.getHeight());
                if (big > 640) { float k = 640f / big; s = android.graphics.Bitmap.createScaledBitmap(f, Math.max(1, Math.round(f.getWidth() * k)), Math.max(1, Math.round(f.getHeight() * k)), true); }
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                s.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bo);
                String t = uploadSync(bo.toByteArray(), "image/jpeg", "thumbnail.jpg");
                if (t != null) {
                    info.put("thumbnail_url", t);
                    JSONObject ti = new JSONObject();
                    ti.put("mimetype", "image/jpeg"); ti.put("w", s.getWidth()); ti.put("h", s.getHeight()); ti.put("size", bo.size());
                    info.put("thumbnail_info", ti);
                }
            }
        } catch (Exception ignored) {
        } finally { try { r.release(); } catch (Exception ignored) {} }
        return info;
    }

    /** A member's name or picture changed: refresh it live. */
    void memberChanged(JSONObject e) {
        final String uid = e.optString("state_key", "");
        JSONObject c = e.optJSONObject("content");
        if (uid.isEmpty() || c == null || !"join".equals(c.optString("membership"))) return;
        String av = c.optString("avatar_url", "");
        if (av.startsWith("mxc://")) Avatars.MXC.put(uid, av); else Avatars.MXC.remove(uid);
        String dn = c.optString("displayname", "");
        if (!dn.isEmpty()) MainActivity.NAMES.put(uid, dn);
        final String peerId = getIntent().getStringExtra("peer");
        runOnUiThread(() -> {
            if (isFinishing()) return;
            if (uid.equals(peerId)) {
                if (!dn.isEmpty() && !dn.equals(contactName)) {
                    String t = tvChatTitle.getText().toString();
                    int p = t.lastIndexOf(" (");
                    contactName = dn;
                    tvChatTitle.setText(dn + (p > 0 ? t.substring(p) : ""));
                }
                setTheirTile();
            } else if (uid.equals(userId)) {
                setTheirTile();
            }
        });
    }

    void sendText(String text) {
        new Thread(() -> {
            try {
                String enc = URLEncoder.encode(roomId, "UTF-8");
                String txn = "txn_" + System.currentTimeMillis() + "_" + System.nanoTime();
                sentTxns.add(txn);
                String url = server.replaceAll("/$", "") + "/_matrix/client/v3/rooms/" + enc + "/send/m.room.message/" + txn;
                JSONObject body = new JSONObject();
                body.put("msgtype", "m.text");
                body.put("body", text);
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("PUT");
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.getOutputStream().write(body.toString().getBytes("UTF-8"));
                final int code = conn.getResponseCode();
                if (code != 200) runOnUiThread(() -> Toast.makeText(this, I18n.tr("Send failed: " + code), Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    void sendDemoReply() {
        String[][] replies = {
            {"Haha, good one! :D", "So what else is new?", "I should go, ttyl! ;)"},
            {"That's interesting!", "Tell me more :O", "I agree! (H)"},
            {"Nice!", "Cool :)", "LOL :P"},
            {"Oh really? :D", "I know, right?!", "See you later! (L)"},
        };
        int setIndex = Math.abs(roomId.hashCode()) % replies.length;
        final String reply = replies[setIndex][demoIndex % replies[setIndex].length];
        demoIndex++;
        final String contact = contactName;
        tvTyping.setText(I18n.tr(contact + " is typing a message..."));
        ui.postDelayed(() -> {
            tvTyping.setText(I18n.tr("Last message received at " + android.text.format.DateFormat.getTimeFormat(this).format(new Date()) + "."));
            addLine(I18n.tr(contact + ": " + reply));
            playSound("type");
        }, 1600);
    }

    void showCrash(Exception e) {
        new AlertDialog.Builder(this).setTitle(I18n.tr("Error"))
            .setMessage(android.util.Log.getStackTraceString(e))
            .setPositiveButton(I18n.tr("OK"), (d, w) -> finish()).show();
    }
}
