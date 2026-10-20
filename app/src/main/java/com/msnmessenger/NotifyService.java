package com.msnmessenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps a live Matrix connection while signed in and posts notifications for conversations that aren't on screen. */
public class NotifyService extends Service {
    static final String CH_MSG = "msn_messages", CH_NUDGE = "msn_nudges", CH_SVC = "msn_signed_in";
    static final int FG_ID = 1, MSG_ID = 2;
    static final ConcurrentHashMap<String, ArrayList<String>> LINES = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, String> ROOM_NAMES = new ConcurrentHashMap<>();
    volatile boolean run = true;
    Thread worker;
    Bitmap large;

    public static void start(Context c) {
        try { ContextCompat.startForegroundService(c, new Intent(c, NotifyService.class)); } catch (Exception ignored) {}
    }

    public static void stop(Context c) {
        try { c.stopService(new Intent(c, NotifyService.class)); } catch (Exception ignored) {}
        LINES.clear();
        NotificationManagerCompat.from(c).cancelAll();
    }

    /** Called when a chat window opens: removes its notification. */
    public static void clearRoom(Context c, String roomId) {
        if (roomId == null) return;
        LINES.remove(roomId);
        NotificationManagerCompat.from(c).cancel(roomId, MSG_ID);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    static Uri raw(Context c, String n) {
        java.io.File f = AssetStore.soundFile(c, n);
        if (f != null) {
            try {
                Uri u = androidx.core.content.FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", f);
                for (String pkg : new String[]{"com.android.systemui", "android"}) {
                    try { c.grantUriPermission(pkg, u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                }
                return u;
            } catch (Exception ignored) {}
        }
        int id = c.getResources().getIdentifier(n, "raw", c.getPackageName());
        return id == 0 ? null : Uri.parse("android.resource://" + c.getPackageName() + "/" + id);
    }

    static void channels(Context c) {
        I18n.init(c);
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        AudioAttributes aa = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        NotificationChannel m = new NotificationChannel(CH_MSG, I18n.tr("Messages"), NotificationManager.IMPORTANCE_HIGH);
        m.setSound(raw(c, "type"), aa); m.enableVibration(true);
        NotificationChannel n = new NotificationChannel(CH_NUDGE, I18n.tr("Nudges and winks"), NotificationManager.IMPORTANCE_HIGH);
        n.setSound(raw(c, "nudge"), aa); n.enableVibration(true); n.setVibrationPattern(new long[]{0, 120, 80, 120, 80, 120});
        NotificationChannel s = new NotificationChannel(CH_SVC, I18n.tr("Signed in"), NotificationManager.IMPORTANCE_MIN);
        s.setShowBadge(false); s.setSound(null, null);
        nm.createNotificationChannel(m); nm.createNotificationChannel(n); nm.createNotificationChannel(s);
    }

    static PendingIntent pi(Context c, int code, Intent[] intents) {
        return PendingIntent.getActivities(c, code, intents, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static Intent mainIntent(Context c) {
        return new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        I18n.init(this);
        channels(this);
        SharedPreferences p = getSharedPreferences("msn", MODE_PRIVATE);
        Notification fg = new NotificationCompat.Builder(this, CH_SVC)
            .setSmallIcon(R.drawable.notif_small)
            .setContentTitle(I18n.tr("MSN Messenger"))
            .setContentText(I18n.tr("Signed in as " + MainActivity.displayFromId(p.getString("user", "")) + " (" + p.getString("my_status", "Online") + ")"))
            .setPriority(NotificationCompat.PRIORITY_MIN).setOngoing(true).setShowWhen(false)
            .setContentIntent(pi(this, 0, new Intent[]{mainIntent(this)})).build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(FG_ID, fg, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(FG_ID, fg);
        } catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
        if (p.getString("token", null) == null || p.getBoolean("isDemo", false)) { stopSelf(); return START_NOT_STICKY; }
        if (worker == null || !worker.isAlive()) { run = true; worker = new Thread(this::loop, "msn-notify"); worker.start(); }
        return START_STICKY;
    }

    @Override
    public void onDestroy() { run = false; if (worker != null) worker.interrupt(); super.onDestroy(); }

    boolean nap(long ms) { try { Thread.sleep(ms); return run; } catch (InterruptedException e) { return false; } }

    void loop() {
        SharedPreferences p = getSharedPreferences("msn", MODE_PRIVATE);
        String since = p.getString("notify_since", null);
        int fails = 0;
        while (run) {
            String token = p.getString("token", null), server = p.getString("server", ""), me = p.getString("user", "");
            if (token == null || p.getBoolean("isDemo", false)) { stopSelf(); return; }
            try {
                boolean first = since == null;
                String filter = "{\"room\":{\"timeline\":{\"limit\":" + (first ? 1 : 20) + "},\"state\":{\"lazy_load_members\":true},"
                    + "\"ephemeral\":{\"not_types\":[\"*\"]},\"account_data\":{\"not_types\":[\"*\"]}},"
                    + "\"presence\":{\"not_types\":[\"*\"]},\"account_data\":{\"not_types\":[\"*\"]}}";
                String url = server.replaceAll("/+$", "") + "/_matrix/client/v3/sync?timeout=" + (first ? 0 : 30000)
                    + "&set_presence=" + MainActivity.presParam(p.getString("my_status", "Online"))
                    + "&filter=" + URLEncoder.encode(filter, "UTF-8")
                    + (first ? "" : "&since=" + URLEncoder.encode(since, "UTF-8"));
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000); c.setReadTimeout(45000);
                c.setRequestProperty("Authorization", "Bearer " + token);
                int code = c.getResponseCode();
                if (code == 401) { stopSelf(); return; }
                if (code != 200) { c.disconnect(); fails++; if (!nap(Math.min(60000, 3000L * fails))) return; continue; }
                JSONObject j = new JSONObject(new Scanner(c.getInputStream(), "UTF-8").useDelimiter("\\A").next());
                String nb = j.optString("next_batch", since);
                if (!first) handle(j, me, server, token);
                since = nb;
                p.edit().putString("notify_since", since).apply();
                fails = 0;
            } catch (Exception e) {
                fails++;
                if (!nap(Math.min(60000, 3000L * fails))) return;
            }
        }
    }

    void handle(JSONObject j, String me, String server, String token) throws Exception {
        JSONObject rooms = j.optJSONObject("rooms");
        JSONObject join = rooms == null ? null : rooms.optJSONObject("join");
        if (join == null) return;
        for (Iterator<String> it = join.keys(); it.hasNext(); ) {
            String rid = it.next();
            JSONObject r = join.getJSONObject(rid);
            for (String sec : new String[]{"state", "timeline"}) {
                JSONObject so = r.optJSONObject(sec);
                JSONArray se = so == null ? null : so.optJSONArray("events");
                if (se == null) continue;
                for (int s = 0; s < se.length(); s++) {
                    JSONObject e = se.getJSONObject(s);
                    JSONObject c = e.optJSONObject("content");
                    if (c == null) continue;
                    String ty = e.optString("type");
                    if ("m.room.name".equals(ty) && !c.optString("name", "").isEmpty()) ROOM_NAMES.put(rid, c.optString("name"));
                    else if ("m.room.member".equals(ty)) {
                        String dn = c.optString("displayname", ""), who = e.optString("state_key", "");
                        if (!who.isEmpty() && !dn.isEmpty() && !dn.startsWith("@")) MainActivity.NAMES.put(who, dn);
                    }
                }
            }
            JSONObject tl = r.optJSONObject("timeline");
            JSONArray ev = tl == null ? null : tl.optJSONArray("events");
            if (ev == null) continue;
            for (int i = 0; i < ev.length(); i++) {
                JSONObject e = ev.getJSONObject(i);
                String sender = e.optString("sender");
                if (!"m.room.message".equals(e.optString("type")) || sender.equals(me)) continue;
                if (rid.equals(ChatActivity.OPEN_ROOM) || MainActivity.FOREGROUND) continue;
                JSONObject c = e.optJSONObject("content");
                if (c == null) continue;
                String body = c.optString("body", ""), mt = c.optString("msgtype", "");
                String who = MainActivity.displayFromId(sender);
                boolean nudge = body.equals("*nudge*");
                boolean wink = body.startsWith("*wink:") && body.endsWith("*") && body.length() > 7;
                String line;
                if (nudge) line = who + " has just sent you a nudge.";
                else if (wink) line = who + " sent you the wink \"" + body.substring(6, body.length() - 1) + "\".";
                else if ("m.image".equals(mt)) line = who + " sent you a picture.";
                else if ("m.video".equals(mt)) line = who + " sent you a video.";
                else if ("m.file".equals(mt)) line = who + " sent you a file: " + body;
                else if (body.isEmpty()) continue;
                else line = who + " says: " + body;
                post(rid, sender, line, nudge || wink, server, token, me);
            }
        }
    }

    void post(String rid, String sender, String line, boolean loud, String server, String token, String me) {
        line = I18n.tr(line);
        ArrayList<String> l = LINES.get(rid);
        if (l == null) { l = new ArrayList<>(); LINES.put(rid, l); }
        NotificationCompat.InboxStyle st = new NotificationCompat.InboxStyle();
        int count;
        synchronized (l) {
            l.add(line);
            while (l.size() > 6) l.remove(0);
            for (String s : l) st.addLine(s);
            count = l.size();
        }
        String room = ROOM_NAMES.get(rid);
        String title = room != null ? room : MainActivity.displayFromId(sender);
        Intent chat = new Intent(this, ChatActivity.class);
        chat.putExtra("roomId", rid);
        chat.putExtra("roomName", title);
        if (room == null) chat.putExtra("peer", sender);
        chat.putExtra("status", "Online");
        chat.putExtra("server", server);
        chat.putExtra("token", token);
        chat.putExtra("userId", me);
        chat.putExtra("isDemo", false);
        if (large == null) try { large = BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher); } catch (Exception ignored) {}
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, loud ? CH_NUDGE : CH_MSG)
            .setSmallIcon(R.drawable.notif_small)
            .setContentTitle(I18n.tr(title))
            .setContentText(I18n.tr(line))
            .setStyle(st)
            .setNumber(count)
            .setColor(0xFF1E3287)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi(this, rid.hashCode(), new Intent[]{mainIntent(this), chat}));
        if (large != null) b.setLargeIcon(large);
        if (Build.VERSION.SDK_INT < 26) {
            Uri snd = raw(this, loud ? "nudge" : "type");
            if (snd != null) b.setSound(snd);
            b.setVibrate(new long[]{0, 120, 80, 120});
        }
        try { NotificationManagerCompat.from(this).notify(rid, MSG_ID, b.build()); } catch (SecurityException ignored) {}
    }
}
