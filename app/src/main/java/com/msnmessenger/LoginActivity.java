package com.msnmessenger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

public class LoginActivity extends Activity {
    EditText etServer, etUser, etPass;
    Button btnLogin;
    TextView btnDemo;
    ProgressBar progress;
    TextView tvLoginStatus;
    ImageView ivLoginStatus;
    CheckBox cbMe, cbPass, cbAuto;
    ImageView ivTile;
    TextView tvSigning;
    android.animation.ObjectAnimator spin;
    static final String[] LOGIN_ST = {"Online", "Busy", "Be Right Back", "Away", "In a Call", "Out to Lunch", "Appear Offline"};

    /** Shows the saved sign-in status (used by the contact list after sign-in). */
    void showLoginStatus() {
        String st = getSharedPreferences("msn", MODE_PRIVATE).getString("my_status", "Online");
        tvLoginStatus.setText(I18n.tr(st) + "  \u25BE");
        AssetStore.set(ivLoginStatus, "Appear Offline".equals(st) ? R.drawable.presence_offline : MainActivity.presenceIcon(st));
    }

    void pickLoginStatus() {
        new AlertDialog.Builder(this).setTitle(I18n.tr("My Status:")).setItems(I18n.tr(LOGIN_ST), (d, w) -> {
            getSharedPreferences("msn", MODE_PRIVATE).edit().putString("my_status", LOGIN_ST[w]).apply();
            showLoginStatus();
        }).show();
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        I18n.init(this); Session.init(this);
        // Public build: no bundled Microsoft assets until the user imports their own installer
        if (!AssetStore.ready(this)) {
            startActivity(new Intent(this, ImportActivity.class));
            finish();
            return;
        }
        try {
            setContentView(R.layout.activity_login);
            AssetStore.applyTags(findViewById(android.R.id.content));
            etServer = findViewById(R.id.etServer);
            etUser = findViewById(R.id.etUser);
            etPass = findViewById(R.id.etPass);
            btnLogin = findViewById(R.id.btnLogin);
            btnDemo = findViewById(R.id.btnDemo);
            progress = findViewById(R.id.progress);
            tvLoginStatus = findViewById(R.id.tvLoginStatus);
            ivLoginStatus = findViewById(R.id.ivLoginStatus);
            showLoginStatus();
            ((View) tvLoginStatus.getParent()).setOnClickListener(v -> pickLoginStatus());

            btnLogin.setOnClickListener(v -> doLogin());
            btnDemo.setOnClickListener(v -> startDemo());
            setupRemember();
            setupSso();
            if (handleRedirect(getIntent())) return;
            if (autoSignIn()) return;
        } catch (Exception e) {
            showCrash(e);
        }
    }

    /** Remember me / Remember my password / Sign me in automatically, as in Messenger 8.5. */
    void setupRemember() {
        cbMe = findViewById(R.id.cbRememberMe); cbPass = findViewById(R.id.cbRememberPass); cbAuto = findViewById(R.id.cbAutoSignIn);
        ivTile = findViewById(R.id.ivLoginTile); tvSigning = findViewById(R.id.tvSigning);
        final SharedPreferences p = getSharedPreferences("msn", MODE_PRIVATE);
        boolean me = p.getBoolean("remember_me", true), pw = p.getBoolean("remember_pass", false), au = p.getBoolean("auto_signin", false);
        cbMe.setChecked(me); cbPass.setChecked(me && pw); cbAuto.setChecked(me && pw && au);
        if (me) etUser.setText(p.getString("login_user", ""));
        if (me && pw) etPass.setText(p.getString("login_pass", ""));
        String srv = p.getString("login_server", null);
        if (me && srv != null && !srv.isEmpty()) etServer.setText(srv);
        if (me) Avatars.showLogin(this, ivTile);
        cbMe.setOnCheckedChangeListener((b, on) -> {
            if (on) return;
            cbPass.setChecked(false); cbAuto.setChecked(false);
            p.edit().remove("login_user").remove("login_pass").remove("login_server").putBoolean("remember_me", false)
                .putBoolean("remember_pass", false).putBoolean("auto_signin", false).apply();
        });
        cbPass.setOnCheckedChangeListener((b, on) -> {
            if (on) { cbMe.setChecked(true); return; }
            cbAuto.setChecked(false);
            p.edit().remove("login_pass").putBoolean("remember_pass", false).putBoolean("auto_signin", false).apply();
        });
        cbAuto.setOnCheckedChangeListener((b, on) -> {
            if (on) { cbMe.setChecked(true); cbPass.setChecked(true); }
            else p.edit().putBoolean("auto_signin", false).apply();
        });
    }

    /** Returns true if the sign-in screen was skipped. */
    boolean autoSignIn() {
        SharedPreferences p = getSharedPreferences("msn", MODE_PRIVATE);
        if (getIntent().getBooleanExtra("signedOut", false) || !p.getBoolean("auto_signin", false)) return false;
        if (p.getString("token", null) != null && !p.getBoolean("isDemo", false)) {
            startActivity(new Intent(this, MainActivity.class).putExtra("signedIn", true));
            finish();
            return true;
        }
        if (!etUser.getText().toString().trim().isEmpty() && !etPass.getText().toString().isEmpty()) doLogin();
        return false;
    }

    void saveRemember(String user, String pass, String srv) {
        boolean me = cbMe != null && cbMe.isChecked(), pw = me && cbPass.isChecked(), au = pw && cbAuto.isChecked();
        SharedPreferences.Editor e = getSharedPreferences("msn", MODE_PRIVATE).edit()
            .putBoolean("remember_me", me).putBoolean("remember_pass", pw).putBoolean("auto_signin", au);
        if (me) e.putString("login_user", user).putString("login_server", srv); else e.remove("login_user").remove("login_server");
        if (pw) e.putString("login_pass", pass); else e.remove("login_pass");
        e.apply();
    }

    /** Sign-in animation: the original Messenger 8.5 spinning buddies (msgsres.dll resource 208, 32 frames), transparent. */
    android.graphics.drawable.Drawable tileFrameBg;

    void showSigning(boolean on) {
        progress.setVisibility(View.GONE);
        if (ivTile == null) return;
        View frame = (View) ivTile.getParent();
        if (on) {
            if (tileFrameBg == null) tileFrameBg = frame.getBackground();
            frame.setBackground(null);
            ivTile.setBackground(null);
            ivTile.setRotationY(0f);
            ivTile.setPadding(0, 0, 0, 0);
            ivTile.setScaleType(ImageView.ScaleType.CENTER);
            android.graphics.drawable.Drawable anim = AssetStore.signinAnim(this);
            if (anim != null) ivTile.setImageDrawable(anim);
            android.graphics.drawable.Drawable dr = ivTile.getDrawable();
            if (dr instanceof android.graphics.drawable.AnimationDrawable) ((android.graphics.drawable.AnimationDrawable) dr).start();
            tvSigning.setVisibility(View.VISIBLE);
        } else {
            android.graphics.drawable.Drawable dr = ivTile.getDrawable();
            if (dr instanceof android.graphics.drawable.AnimationDrawable) ((android.graphics.drawable.AnimationDrawable) dr).stop();
            if (tileFrameBg != null) frame.setBackground(tileFrameBg);
            ivTile.setScaleType(ImageView.ScaleType.FIT_CENTER);
            AssetStore.set(ivTile, R.drawable.tile_default);
            if (cbMe != null && cbMe.isChecked()) Avatars.showLogin(this, ivTile);
            tvSigning.setVisibility(View.GONE);
        }
    }

    void startDemo() {
        SharedPreferences prefs = getSharedPreferences("msn", MODE_PRIVATE);
        prefs.edit().putString("token", "demo-token")
            .putString("user", "demo@msn.com")
            .putString("server", "demo")
            .putBoolean("isDemo", true)
            .apply();
        startActivity(new Intent(this, MainActivity.class).putExtra("signedIn", true));
        finish();
    }

    void doLogin() {
        String server = etServer.getText().toString().trim();
        final String user = etUser.getText().toString().trim();
        final String pass = etPass.getText().toString();
        if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            signInError("Please type your e-mail address (Matrix username) and password.");
            return;
        }
        if (!server.startsWith("http://") && !server.startsWith("https://")) server = "https://" + server;
        final String base = server.replaceAll("/+$", "");
        btnLogin.setEnabled(false);
        showSigning(true);
        new Thread(() -> {
            String error;
            try {
                final String hs = discover(base);
                String[] res = null;
                for (JSONObject id : identifiers(user)) {
                    res = tryLogin(hs, id, pass);
                    if (res[0] != null) break;
                    if (!"M_FORBIDDEN".equals(res[2]) && !"M_INVALID_USERNAME".equals(res[2]) && !"M_UNKNOWN".equals(res[2])) break;
                }
                if (res != null && res[0] != null) {
                    getSharedPreferences("msn", MODE_PRIVATE).edit()
                        .putString("token", res[0]).putString("user", res[1])
                        .putString("server", hs).putString("device", res[5])
                        .putString("auth_kind", "password").remove("refresh_token").remove("token_expires")
                        .putBoolean("isDemo", false).apply();
                    saveRemember(user, pass, base);
                    runOnUiThread(() -> {
                        startActivity(new Intent(this, MainActivity.class).putExtra("signedIn", true));
                        finish();
                    });
                    return;
                }
                error = friendly(res);
            } catch (java.net.UnknownHostException e) {
                error = "We can't find the server " + base + ". Check the address and that your phone is online.";
            } catch (java.net.SocketTimeoutException e) {
                error = "The server took too long to respond. Please try again.";
            } catch (javax.net.ssl.SSLException e) {
                error = "A secure connection to the server couldn't be made (" + e.getMessage() + ").";
            } catch (Exception e) {
                error = "Couldn't sign in: " + e;
            }
            final String msg = error;
            runOnUiThread(() -> {
                btnLogin.setEnabled(true);
                showSigning(false);
                signInError(msg);
            });
        }).start();
    }

    /** Accepts yourname, @yourname:server, yourname:server, yourname@server or a linked e-mail address. */
    static java.util.List<JSONObject> identifiers(String u) throws org.json.JSONException {
        java.util.List<JSONObject> l = new java.util.ArrayList<>();
        if (u.startsWith("@")) { l.add(mxid(u)); return l; }
        if (u.contains(":")) { l.add(mxid("@" + u)); return l; }
        int at = u.indexOf('@');
        if (at > 0) {
            JSONObject e = new JSONObject();
            e.put("type", "m.id.thirdparty");
            e.put("medium", "email");
            e.put("address", u.toLowerCase());
            l.add(e);
            l.add(mxid(u.substring(0, at)));
            return l;
        }
        l.add(mxid(u));
        return l;
    }

    static JSONObject mxid(String u) throws org.json.JSONException {
        JSONObject o = new JSONObject();
        o.put("type", "m.id.user");
        o.put("user", u);
        return o;
    }

    /** Returns {token, userId, errcode, error, httpCode, deviceId}. */
    static String[] tryLogin(String hs, JSONObject id, String pass) throws Exception {
        JSONObject body = new JSONObject();
        body.put("type", "m.login.password");
        body.put("identifier", id);
        body.put("password", pass);
        body.put("initial_device_display_name", "MSN Messenger (Android)");
        HttpURLConnection c = (HttpURLConnection) new URL(hs + "/_matrix/client/v3/login").openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        c.getOutputStream().write(body.toString().getBytes("UTF-8"));
        int code = c.getResponseCode();
        String txt = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        JSONObject r;
        try { r = new JSONObject(txt); } catch (Exception e) { r = new JSONObject(); }
        if (code == 200 && r.has("access_token"))
            return new String[]{r.getString("access_token"), r.optString("user_id"), null, null, "200", r.optString("device_id")};
        String err = r.optString("error", txt.length() > 200 ? txt.substring(0, 200) : txt);
        if (r.has("retry_after_ms")) err += " (try again in " + (r.optLong("retry_after_ms") / 1000 + 1) + " s)";
        return new String[]{null, null, r.optString("errcode", "HTTP_" + code), err, String.valueOf(code), null};
    }

    static String readAll(InputStream in) throws java.io.IOException {
        if (in == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    /** Follows /.well-known/matrix/client so a bare domain also works. */
    static String discover(String base) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(base + "/.well-known/matrix/client").openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() == 200) {
                String u = new JSONObject(readAll(c.getInputStream())).getJSONObject("m.homeserver").optString("base_url", "");
                if (u.startsWith("http")) return u.replaceAll("/+$", "");
            }
        } catch (Exception ignored) {}
        return base;
    }

    static String friendly(String[] r) {
        if (r == null) return "No response from the server.";
        String code = r[2] == null ? "" : r[2];
        switch (code) {
            case "M_FORBIDDEN":
                return "The e-mail address or password you entered is incorrect.\n\nUse your Matrix username (for example yourname or @yourname:server) and its password.";
            case "M_LIMIT_EXCEEDED":
                return "Too many sign-in attempts. " + r[3];
            case "M_USER_DEACTIVATED":
                return "This account has been deactivated on the server.";
            case "M_USER_LOCKED":
                return "This account is locked on the server.";
            default:
                return "Sign-in failed (" + code + ", HTTP " + r[4] + "): " + r[3];
        }
    }

    // ---- Single sign-on (classic Matrix SSO) and OAuth 2.0 (Matrix Authentication Service).
    // Buttons appear only when the server offers these; the classic sign-in box always stays.
    LinearLayout llSso;
    final android.os.Handler probeUi = new android.os.Handler(android.os.Looper.getMainLooper());
    String probedBase;
    final Runnable probeRun = this::probeServer;

    void setupSso() {
        llSso = findViewById(R.id.llSso);
        if (llSso == null) return;
        etServer.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            public void onTextChanged(CharSequence s, int a, int c, int d) {}
            public void afterTextChanged(android.text.Editable s) { probeUi.removeCallbacks(probeRun); probeUi.postDelayed(probeRun, 900); }
        });
        probeServer();
    }

    static String baseOf(String server) {
        server = server.trim();
        if (server.isEmpty()) return "";
        if (!server.startsWith("http://") && !server.startsWith("https://")) server = "https://" + server;
        return server.replaceAll("/+$", "");
    }

    /** Asks the server which sign-in methods it offers and shows matching buttons. */
    void probeServer() {
        if (llSso == null) return;
        final String base = baseOf(etServer.getText().toString());
        if (base.equals(probedBase)) return;
        probedBase = base;
        llSso.removeAllViews();
        llSso.setVisibility(View.GONE);
        if (base.isEmpty()) return;
        new Thread(() -> {
            final Session.Methods m = Session.discover(base);
            runOnUiThread(() -> { if (!isFinishing() && base.equals(probedBase)) showMethods(base, m); });
        }).start();
    }

    void showMethods(final String base, final Session.Methods m) {
        llSso.removeAllViews();
        if (m.oauth != null) {
            addSsoButton(I18n.tr("Sign in with your browser"), v -> startOAuth(base, m));
        } else if (m.sso) {
            if (m.idps.isEmpty()) addSsoButton(I18n.tr("Sign in with SSO"), v -> openBrowser(Session.ssoUrl(this, base, m.hs, null)));
            for (final String[] idp : m.idps)
                addSsoButton(I18n.tr("Sign in with " + idp[1]), v -> openBrowser(Session.ssoUrl(this, base, m.hs, idp[0])));
        }
        llSso.setVisibility(llSso.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    void addSsoButton(String text, View.OnClickListener l) {
        Button b = new Button(this, null, 0, R.style.WlmPushButton);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        llSso.addView(b, lp);
    }

    void openBrowser(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (Exception e) {
            signInError("Couldn't open your browser.");
        }
    }

    void startOAuth(final String base, final Session.Methods m) {
        new Thread(() -> {
            try {
                final String url = Session.oauthUrl(this, base, m.hs, m.oauth);
                runOnUiThread(() -> openBrowser(url));
            } catch (Exception e) {
                final String msg = "Couldn't start signing in: " + e.getMessage();
                runOnUiThread(() -> signInError(msg));
            }
        }).start();
    }

    /** Browser sign-in result, forwarded by AuthCallbackActivity. Returns true if one was handled. */
    boolean handleRedirect(Intent in) {
        final android.net.Uri u = in == null ? null : in.getData();
        if (u == null || !Session.SCHEME.equals(u.getScheme())) return false;
        in.setData(null);
        btnLogin.setEnabled(false);
        showSigning(true);
        new Thread(() -> {
            try {
                final String[] r = Session.finish(this, u);
                saveRemember(r[0], "", r[1]);
                runOnUiThread(() -> {
                    startActivity(new Intent(this, MainActivity.class).putExtra("signedIn", true));
                    finish();
                });
            } catch (Exception e) {
                final String msg = "Couldn't finish signing in: " + e.getMessage();
                runOnUiThread(() -> {
                    btnLogin.setEnabled(true);
                    showSigning(false);
                    signInError(msg);
                });
            }
        }).start();
        return true;
    }

    void signInError(String msg) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this).setTitle(I18n.tr("Sign-in error")).setMessage(I18n.tr(msg)).setPositiveButton(I18n.tr("OK"), null).show();
    }

    void showCrash(Exception e) {
        new AlertDialog.Builder(this).setTitle(I18n.tr("Error"))
            .setMessage(android.util.Log.getStackTraceString(e))
            .setPositiveButton(I18n.tr("OK"), (d, w) -> finish()).show();
    }
}
