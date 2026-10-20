package com.msnmessenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Sign-in sessions and browser sign-in.
 * Password and classic SSO (m.login.sso) sign-ins give a long-lived Matrix token.
 * OAuth 2.0 sign-ins (Matrix Authentication Service, MSC3861 / Matrix 1.15) give a short-lived
 * token plus a refresh token; bearer() renews it shortly before it expires, so every request,
 * including the background notification connection, keeps working.
 */
public final class Session {
    private Session() {}

    /** Private-use redirect scheme: reverse DNS of a subdomain of CLIENT_URI's host (RFC 8252). */
    public static final String SCHEME = "io.github.gmorb.msnmessenger";
    static final String OAUTH_REDIRECT = SCHEME + ":/callback";
    static final String SSO_REDIRECT = SCHEME + ":/sso";
    static final String CLIENT_URI = "https://gmorb.github.io/msn-messenger-android-public";
    static final SecureRandom RNG = new SecureRandom();
    static Context app;

    static void init(Context c) { if (app == null && c != null) app = c.getApplicationContext(); }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("msn", Context.MODE_PRIVATE); }

    static SharedPreferences pending(Context c) { return c.getSharedPreferences("msn_auth_pending", Context.MODE_PRIVATE); }

    /** "Bearer <token>", renewed first if it is an OAuth token about to expire. */
    public static String bearer(String token) {
        if (app == null) return "Bearer " + token;
        SharedPreferences p = prefs(app);
        if (!"oauth".equals(p.getString("auth_kind", ""))) return "Bearer " + token;
        long exp = p.getLong("token_expires", 0);
        if (exp > 0 && System.currentTimeMillis() > exp - 60000 && Looper.myLooper() != Looper.getMainLooper()) refresh(false);
        String t = p.getString("token", null);
        return "Bearer " + (t != null ? t : token);
    }

    /** Renews the OAuth token. force: renew even if it isn't due yet (after a 401). */
    public static synchronized boolean refresh(boolean force) {
        if (app == null) return false;
        SharedPreferences p = prefs(app);
        if (!"oauth".equals(p.getString("auth_kind", ""))) return false;
        long exp = p.getLong("token_expires", 0);
        if (!force && exp > 0 && System.currentTimeMillis() < exp - 60000) return true; // renewed by another thread
        String rt = p.getString("refresh_token", null), te = p.getString("token_endpoint", null), cid = p.getString("client_id", null);
        if (rt == null || te == null || cid == null) return false;
        try {
            JSONObject r = postForm(te, "grant_type=refresh_token&refresh_token=" + enc(rt) + "&client_id=" + enc(cid));
            if (!r.has("access_token")) return false;
            saveTokens(p.edit(), r).commit();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static SharedPreferences.Editor saveTokens(SharedPreferences.Editor e, JSONObject r) throws Exception {
        e.putString("token", r.getString("access_token"));
        if (r.has("refresh_token")) e.putString("refresh_token", r.getString("refresh_token"));
        long in = r.optLong("expires_in", 0);
        e.putLong("token_expires", in > 0 ? System.currentTimeMillis() + in * 1000 : 0);
        return e;
    }

    /**
     * Ends the session on the server when signing out (best effort, in the background).
     * OAuth (MAS): revokes the refresh token. Password and classic SSO: Matrix /logout.
     * Values are read now because the caller clears the saved settings straight afterwards.
     */
    static void signOut(Context c) {
        SharedPreferences p = prefs(c);
        if ("oauth".equals(p.getString("auth_kind", ""))) {
            final String rev = p.getString("revocation_endpoint", ""), rt = p.getString("refresh_token", null), cid = p.getString("client_id", "");
            if (rev.isEmpty() || rt == null) return;
            new Thread(() -> {
                try { postForm(rev, "token=" + enc(rt) + "&token_type_hint=refresh_token&client_id=" + enc(cid)); } catch (Exception ignored) {}
            }).start();
            return;
        }
        final String srv = p.getString("server", ""), tok = p.getString("token", null);
        if (srv.isEmpty() || tok == null) return;
        final String url = (srv.endsWith("/") ? srv.substring(0, srv.length() - 1) : srv) + "/_matrix/client/v3/logout";
        new Thread(() -> {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
                h.setConnectTimeout(10000);
                h.setReadTimeout(15000);
                h.setRequestMethod("POST");
                h.setRequestProperty("Content-Type", "application/json");
                h.setRequestProperty("Authorization", "Bearer " + tok);
                h.setDoOutput(true);
                h.getOutputStream().write("{}".getBytes("UTF-8"));
                h.getResponseCode();
                h.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    // ---- What a server offers

    static final class Methods {
        String hs;
        boolean password, sso;
        JSONObject oauth;                       // OAuth 2.0 server metadata, if the server uses MAS
        final List<String[]> idps = new ArrayList<>(); // {id, name}
    }

    static Methods discover(String base) {
        Methods m = new Methods();
        m.hs = LoginActivity.discover(base);
        m.oauth = authMetadata(m.hs);
        try {
            JSONArray flows = getJson(m.hs + "/_matrix/client/v3/login", null).optJSONArray("flows");
            for (int i = 0; flows != null && i < flows.length(); i++) {
                JSONObject f = flows.getJSONObject(i);
                String t = f.optString("type");
                if ("m.login.password".equals(t)) m.password = true;
                if ("m.login.sso".equals(t)) {
                    m.sso = true;
                    JSONArray ip = f.optJSONArray("identity_providers");
                    for (int k = 0; ip != null && k < ip.length(); k++) {
                        JSONObject o = ip.getJSONObject(k);
                        String id = o.optString("id", "");
                        if (!id.isEmpty()) m.idps.add(new String[]{id, o.optString("name", id)});
                    }
                }
            }
        } catch (Exception ignored) {}
        return m;
    }

    static JSONObject authMetadata(String hs) {
        for (String path : new String[]{"/_matrix/client/v1/auth_metadata", "/_matrix/client/unstable/org.matrix.msc2965/auth_metadata"}) {
            try {
                JSONObject j = getJson(hs + path, null);
                if (j.has("authorization_endpoint") && j.has("token_endpoint")) return j;
            } catch (Exception ignored) {}
        }
        try { // older servers: issuer + OpenID Connect discovery
            String iss = getJson(hs + "/_matrix/client/unstable/org.matrix.msc2965/auth_issuer", null).optString("issuer", "");
            if (!iss.isEmpty()) {
                JSONObject j = getJson(iss.replaceAll("/+$", "") + "/.well-known/openid-configuration", null);
                if (j.has("authorization_endpoint") && j.has("token_endpoint")) return j;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ---- Starting a browser sign-in

    /** Classic SSO: returns the page to open in the browser. */
    static String ssoUrl(Context c, String base, String hs, String idp) {
        pending(c).edit().clear().putString("kind", "sso").putString("hs", hs).putString("base", base).commit();
        return hs + "/_matrix/client/v3/login/sso/redirect" + (idp == null ? "" : "/" + enc(idp))
            + "?redirectUrl=" + enc(SSO_REDIRECT);
    }

    /** OAuth 2.0 with PKCE: registers the app once per server, then returns the page to open. Network: call off the UI thread. */
    static String oauthUrl(Context c, String base, String hs, JSONObject meta) throws Exception {
        String auth = meta.getString("authorization_endpoint");
        String issuer = meta.optString("issuer", auth);
        SharedPreferences cl = c.getSharedPreferences("msn_oauth_clients", Context.MODE_PRIVATE);
        String cid = cl.getString(issuer, null);
        if (cid == null) {
            String reg = meta.optString("registration_endpoint", "");
            if (reg.isEmpty()) throw new Exception("this server doesn't let new apps register");
            JSONObject b = new JSONObject();
            b.put("client_name", "MSN Messenger");
            b.put("client_uri", CLIENT_URI);
            b.put("application_type", "native");
            b.put("token_endpoint_auth_method", "none");
            b.put("redirect_uris", new JSONArray().put(OAUTH_REDIRECT));
            b.put("grant_types", new JSONArray().put("authorization_code").put("refresh_token"));
            b.put("response_types", new JSONArray().put("code"));
            JSONObject r = postJson(reg, b);
            cid = r.optString("client_id", "");
            if (cid.isEmpty()) throw new Exception(err(r));
            cl.edit().putString(issuer, cid).commit();
        }
        String verifier = random(48), state = random(16);
        String challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("US-ASCII")));
        pending(c).edit().clear().putString("kind", "oauth").putString("hs", hs).putString("base", base)
            .putString("state", state).putString("verifier", verifier).putString("client_id", cid)
            .putString("token_endpoint", meta.getString("token_endpoint"))
            .putString("revocation_endpoint", meta.optString("revocation_endpoint", "")).commit();
        String scope = "urn:matrix:client:api:* urn:matrix:client:device:" + deviceId();
        return auth + (auth.contains("?") ? "&" : "?") + "response_type=code&response_mode=query"
            + "&client_id=" + enc(cid) + "&redirect_uri=" + enc(OAUTH_REDIRECT) + "&scope=" + enc(scope)
            + "&state=" + enc(state) + "&code_challenge=" + enc(challenge) + "&code_challenge_method=S256";
    }

    /** Completes a browser sign-in and saves the session. Returns {userId, base}. Network: call off the UI thread. */
    static String[] finish(Context c, android.net.Uri u) throws Exception {
        SharedPreferences pd = pending(c);
        String kind = pd.getString("kind", ""), hs = pd.getString("hs", ""), base = pd.getString("base", hs);
        if (kind.isEmpty() || hs.isEmpty()) throw new Exception("no sign-in was in progress");
        try {
            String e1 = u.getQueryParameter("error");
            if (e1 != null) {
                String d = u.getQueryParameter("error_description");
                throw new Exception(d != null ? d : e1);
            }
            SharedPreferences.Editor e = prefs(c).edit();
            String userId, device;
            if ("sso".equals(kind)) {
                String lt = u.getQueryParameter("loginToken");
                if (lt == null) throw new Exception("the server didn't send a sign-in token");
                JSONObject b = new JSONObject();
                b.put("type", "m.login.token");
                b.put("token", lt);
                b.put("initial_device_display_name", "MSN Messenger (Android)");
                JSONObject r = postJson(hs + "/_matrix/client/v3/login", b);
                if (!r.has("access_token")) throw new Exception(err(r));
                e.putString("auth_kind", "sso").remove("refresh_token").remove("token_expires").putString("token", r.getString("access_token"));
                userId = r.optString("user_id");
                device = r.optString("device_id");
            } else {
                String state = u.getQueryParameter("state"), code = u.getQueryParameter("code");
                if (state == null || !state.equals(pd.getString("state", null))) throw new Exception("the reply didn't match this sign-in");
                if (code == null) throw new Exception("the server didn't send a sign-in code");
                String te = pd.getString("token_endpoint", ""), cid = pd.getString("client_id", "");
                JSONObject r = postForm(te, "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(OAUTH_REDIRECT)
                    + "&client_id=" + enc(cid) + "&code_verifier=" + enc(pd.getString("verifier", "")));
                if (!r.has("access_token")) throw new Exception(err(r));
                JSONObject who = getJson(hs + "/_matrix/client/v3/account/whoami", r.getString("access_token"));
                saveTokens(e, r).putString("auth_kind", "oauth").putString("token_endpoint", te).putString("client_id", cid)
                    .putString("revocation_endpoint", pd.getString("revocation_endpoint", ""));
                userId = who.optString("user_id");
                device = who.optString("device_id");
            }
            e.putString("user", userId).putString("server", hs).putString("device", device).putBoolean("isDemo", false).commit();
            return new String[]{userId, base};
        } finally {
            pd.edit().clear().commit();
        }
    }

    // ---- HTTP helpers

    static JSONObject getJson(String url, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/json");
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        int code = c.getResponseCode();
        String t = LoginActivity.readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code != 200) throw new Exception("HTTP " + code);
        return new JSONObject(t);
    }

    static JSONObject postForm(String url, String body) throws Exception { return send(url, "application/x-www-form-urlencoded", body); }

    static JSONObject postJson(String url, JSONObject body) throws Exception { return send(url, "application/json", body.toString()); }

    static JSONObject send(String url, String type, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", type);
        c.setRequestProperty("Accept", "application/json");
        c.setDoOutput(true);
        c.getOutputStream().write(body.getBytes("UTF-8"));
        int code = c.getResponseCode();
        String t = LoginActivity.readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        JSONObject r;
        try { r = new JSONObject(t); } catch (Exception x) { r = new JSONObject(); }
        if (code >= 400 && !r.has("error")) r.put("error", "HTTP " + code);
        return r;
    }

    static String err(JSONObject r) {
        String d = r.optString("error_description", "");
        return d.isEmpty() ? r.optString("error", "unknown error") : d;
    }

    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    static String b64(byte[] b) {
        return android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
    }

    static String random(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return b64(b);
    }

    static String deviceId() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 10; i++) s.append((char) ('A' + RNG.nextInt(26)));
        return s.toString();
    }
}
