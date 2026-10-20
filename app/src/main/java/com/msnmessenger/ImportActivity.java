package com.msnmessenger;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.msnmessenger.imp.Importer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * First-run screen of the public build: the user picks their own MSN Messenger 8 installer
 * (.exe, MsnMsgs.msi or a .zip), and the app extracts icons, emoticons, pictures, animation and sounds from it.
 * Uses no Microsoft artwork itself.
 */
public class ImportActivity extends Activity {
    static final int PICK = 41;
    TextView tvStatus;
    ProgressBar bar;
    Button btnPick;
    volatile boolean busy;
    /** Set after a pinned-shortcut request; sign-in opens once the launcher's confirmation is answered. */
    boolean waitShortcut, pausedForShortcut;

    @Override
    protected void onPause() {
        super.onPause();
        if (waitShortcut) pausedForShortcut = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitShortcut && pausedForShortcut) openLogin();
    }
    final Handler ui = new Handler(Looper.getMainLooper());

    int dp(int v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        I18n.init(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(48), dp(24), dp(24));
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText(I18n.tr("Set up MSN Messenger"));
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF1F4E9A);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText(I18n.tr("This app doesn't include Microsoft's original pictures and sounds. Choose your own MSN Messenger 8 installer (MSN Messenger 8.exe, MsnMsgs.msi or a .zip containing one of them) and the app will set them up. The installer is only read, nothing is installed."));
        info.setTextSize(15);
        info.setTextColor(0xFF333333);
        info.setPadding(0, dp(16), 0, dp(24));
        info.setGravity(Gravity.CENTER);
        root.addView(info);

        btnPick = new Button(this);
        btnPick.setText(I18n.tr("Choose installer..."));
        btnPick.setOnClickListener(v -> pick());
        root.addView(btnPick, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        root.addView(bar, lp);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(14);
        tvStatus.setTextColor(0xFF555555);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(0, dp(8), 0, 0);
        root.addView(tvStatus);

        setContentView(root);
    }

    void pick() {
        if (busy) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try { startActivityForResult(i, PICK); }
        catch (Exception e) { tvStatus.setText(I18n.tr("No file picker is available on this device.")); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        start(data.getData());
    }

    void start(final Uri uri) {
        busy = true;
        btnPick.setEnabled(false);
        bar.setVisibility(View.VISIBLE);
        bar.setProgress(0);
        tvStatus.setText(I18n.tr("Reading the installer..."));
        new Thread(() -> {
            File input = new File(getCacheDir(), "import_input.bin");
            File work = new File(getCacheDir(), "import_work");
            try {
                try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(input)) {
                    if (in == null) throw new java.io.IOException(I18n.tr("The file could not be read."));
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                new Importer(new AndroidPlatform(), (done, total, what) -> ui.post(() -> {
                    bar.setProgress(total > 0 ? done * 100 / total : 0);
                    tvStatus.setText(I18n.tr("Setting up...") + " " + done + "/" + total);
                })).run(input, work, AssetStore.dir(this));
                AssetStore.reset();
                Winks.reload();
                ui.post(this::finished);
            } catch (Throwable t) {
                final String msg = t.getMessage() == null ? t.toString() : t.getMessage();
                ui.post(() -> failed(msg));
            } finally {
                input.delete();
            }
        }).start();
    }

    void finished() {
        busy = false;
        bar.setProgress(100);
        tvStatus.setText(I18n.tr("Done!"));
        if (HomeShortcut.available(this)) {
            new android.app.AlertDialog.Builder(this)
                .setTitle(I18n.tr("MSN Messenger"))
                .setMessage(I18n.tr("Add MSN Messenger to your Home screen with the original icon?"))
                .setPositiveButton(I18n.tr("Add"), (d, w) -> {
                    if (!HomeShortcut.request(this)) {
                        android.widget.Toast.makeText(this, I18n.tr("The shortcut could not be added."), android.widget.Toast.LENGTH_LONG).show();
                        openLogin();
                        return;
                    }
                    // Let the launcher's confirmation show; continue once it is answered (or if none appears)
                    waitShortcut = true;
                    ui.postDelayed(() -> { if (waitShortcut && !pausedForShortcut) openLogin(); }, 4000);
                })
                .setNegativeButton(I18n.tr("Not now"), (d, w) -> openLogin())
                .setCancelable(false)
                .show();
        } else {
            openLogin();
        }
    }

    boolean loginOpened;

    void openLogin() {
        if (loginOpened) return;
        loginOpened = true;
        waitShortcut = false;
        startActivity(new Intent(this, LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    void failed(String msg) {
        busy = false;
        btnPick.setEnabled(true);
        bar.setVisibility(View.INVISIBLE);
        tvStatus.setText(I18n.tr("Import failed:") + " " + msg);
    }

    @Override
    public void onBackPressed() {
        if (!busy) super.onBackPressed();
    }
}
