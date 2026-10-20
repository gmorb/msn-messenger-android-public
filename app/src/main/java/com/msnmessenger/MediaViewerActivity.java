package com.msnmessenger;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.HashMap;
import java.util.Map;

/** Full-screen viewer for images and videos sent in a conversation, with a Save button. */
public class MediaViewerActivity extends Activity {
    static final int REQ_STORAGE = 77;
    String server, token, mxc, name, mime, kind;
    ImageView image;
    VideoView video;
    ProgressBar spinner;
    float zoom = 1f;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        I18n.init(this); Session.init(this);
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            server = getIntent().getStringExtra("server");
            token = getIntent().getStringExtra("token");
            mxc = getIntent().getStringExtra("mxc");
            name = getIntent().getStringExtra("name");
            mime = getIntent().getStringExtra("mime");
            kind = getIntent().getStringExtra("kind");
            if (name == null || name.isEmpty()) name = "video".equals(kind) ? "video.mp4" : "picture.jpg";

            FrameLayout root = new FrameLayout(this);
            root.setBackgroundColor(0xFF000000);
            setContentView(root);

            spinner = new ProgressBar(this);
            root.addView(spinner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

            if ("video".equals(kind)) showVideo(root); else showImage(root);

            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setBackgroundColor(0xCC1E3287);
            int pad = dp(10);
            bar.setPadding(pad, pad, pad, pad);
            TextView title = new TextView(this);
            title.setText(I18n.tr(name)); title.setTextColor(0xFFFFFFFF); title.setTextSize(14); title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView save = button("Save");
            save.setOnClickListener(v -> save());
            bar.addView(save);
            TextView close = button("Close");
            close.setOnClickListener(v -> finish());
            bar.addView(close);
            root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        } catch (Exception e) {
            Toast.makeText(this, I18n.tr("Couldn't open this file: " + e.getMessage()), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    TextView button(String t) {
        TextView tv = new TextView(this);
        tv.setText(I18n.tr(t)); tv.setTextColor(0xFFFFFFFF); tv.setTextSize(14);
        tv.setBackgroundResource(R.drawable.bg_viewer_btn);
        tv.setMinHeight(dp(36)); tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(14), dp(6), dp(14), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(8));
        tv.setLayoutParams(lp);
        return tv;
    }

    String downloadUrl() {
        String path = mxc != null && mxc.startsWith("mxc://") ? mxc.substring(6) : "";
        return server.replaceAll("/+$", "") + "/_matrix/client/v1/media/download/" + path;
    }

    void showImage(FrameLayout root) {
        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(image, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final ScaleGestureDetector sg = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                zoom = Math.max(1f, Math.min(5f, zoom * d.getScaleFactor()));
                image.setScaleX(zoom); image.setScaleY(zoom);
                if (zoom == 1f) { image.setTranslationX(0); image.setTranslationY(0); }
                return true;
            }
        });
        final float[] last = new float[2];
        image.setOnTouchListener((v, e) -> {
            sg.onTouchEvent(e);
            if (e.getPointerCount() == 1 && zoom > 1f) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) { last[0] = e.getRawX(); last[1] = e.getRawY(); }
                else if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    image.setTranslationX(image.getTranslationX() + e.getRawX() - last[0]);
                    image.setTranslationY(image.getTranslationY() + e.getRawY() - last[1]);
                    last[0] = e.getRawX(); last[1] = e.getRawY();
                }
            }
            return true;
        });
        final Activity a = this;
        new Thread(() -> {
            final Bitmap bm = loadFull();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                spinner.setVisibility(View.GONE);
                if (bm != null) image.setImageBitmap(bm);
                else Toast.makeText(a, I18n.tr("The picture couldn't be loaded."), Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    /** Downloads the original picture (not the server's preview) and fits it to the screen. */
    Bitmap loadFull() {
        String path = mxc != null && mxc.startsWith("mxc://") ? mxc.substring(6) : "";
        String base = server.replaceAll("/+$", "");
        for (String u : new String[]{ base + "/_matrix/client/v1/media/download/" + path, base + "/_matrix/media/v3/download/" + path }) {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                c.setConnectTimeout(15000); c.setReadTimeout(60000);
                c.setRequestProperty("Authorization", Session.bearer(token));
                if (c.getResponseCode() != 200) { c.disconnect(); continue; }
                byte[] d = Avatars.readAll(c.getInputStream());
                Bitmap b = Avatars.decodeSampled(d, 2048);
                if (b != null) return b;
            } catch (Exception ignored) {}
        }
        return null;
    }

    void showVideo(FrameLayout root) {
        video = new VideoView(this);
        root.addView(video, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        MediaController mc = new MediaController(this);
        mc.setAnchorView(video);
        video.setMediaController(mc);
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", Session.bearer(token));
        video.setVideoURI(Uri.parse(downloadUrl()), h);
        video.setOnPreparedListener(mp -> { spinner.setVisibility(View.GONE); video.start(); mc.show(3000); });
        video.setOnErrorListener((mp, what, extra) -> {
            spinner.setVisibility(View.GONE);
            Toast.makeText(this, I18n.tr("This video can't be played on this phone. Tap Save to download it."), Toast.LENGTH_LONG).show();
            return true;
        });
    }

    void save() {
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        download(this, server, token, mxc, name, mime);
    }

    /** Saves any shared file (mxc://) to Downloads/MSN Messenger using the system download manager. */
    static void download(Activity a, String server, String token, String mxc, String name, String mime) {
        try {
            if (name == null || name.isEmpty()) name = "file";
            String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            String path = mxc != null && mxc.startsWith("mxc://") ? mxc.substring(6) : "";
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(server.replaceAll("/+$", "") + "/_matrix/client/v1/media/download/" + path));
            r.addRequestHeader("Authorization", Session.bearer(token));
            r.setTitle(safe);
            r.setDescription("MSN Messenger");
            if (mime != null && !mime.isEmpty()) r.setMimeType(mime);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "MSN Messenger/" + safe);
            if (Build.VERSION.SDK_INT < 29) r.allowScanningByMediaScanner();
            ((DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
            Toast.makeText(a, I18n.tr("Saving \"" + safe + "\" to Downloads/MSN Messenger..."), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(a, I18n.tr("Couldn't save: " + e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] res) {
        super.onRequestPermissionsResult(req, p, res);
        if (req == REQ_STORAGE && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) save();
        else if (req == REQ_STORAGE) Toast.makeText(this, I18n.tr("Storage permission is needed to save files."), Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        if (video != null) try { video.stopPlayback(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
