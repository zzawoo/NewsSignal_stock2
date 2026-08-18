package com.newssignal.common;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;

/**
 * 카카오톡 "나에게 보내기"(메모 API) 연동.
 *  - OAuth2 인가코드 → 토큰 교환 / 리프레시
 *  - 사용자별 토큰은 user_kakao 테이블에 저장
 *  - 알림을 본인 카카오톡(나와의 채팅)으로 전송
 *
 * 환경변수: KAKAO_REST_API_KEY(필수), KAKAO_REDIRECT_URI(선택), APP_BASE_URL(선택)
 */
public final class KakaoClient {

    private static final String AUTH_BASE = "https://kauth.kakao.com";
    private static final String API_BASE  = "https://kapi.kakao.com";

    private KakaoClient() {}

    public static String restKey() { return System.getenv("KAKAO_REST_API_KEY"); }
    public static boolean isConfigured() { String k = restKey(); return k != null && !k.trim().isEmpty(); }

    /** 클라이언트 시크릿이 켜져 있으면 토큰 요청에 함께 전송(환경변수 KAKAO_CLIENT_SECRET). */
    private static String clientSecretParam() {
        String s = System.getenv("KAKAO_CLIENT_SECRET");
        return (s != null && !s.trim().isEmpty()) ? "&client_secret=" + enc(s.trim()) : "";
    }

    public static String redirectUri() {
        String r = System.getenv("KAKAO_REDIRECT_URI");
        if (r != null && !r.trim().isEmpty()) return r.trim();
        return appBaseUrl() + "/kakao/callback";
    }
    public static String appBaseUrl() {
        String b = System.getenv("APP_BASE_URL");
        if (b != null && !b.trim().isEmpty()) return b.trim().replaceAll("/+$", "");
        return "http://localhost:8080/newssignal2";
    }

    /** 카카오 로그인(동의) 인가 URL. scope=talk_message(나에게 보내기). */
    public static String authorizeUrl(String redirectUri) {
        return AUTH_BASE + "/oauth/authorize"
             + "?response_type=code"
             + "&client_id=" + enc(restKey())
             + "&redirect_uri=" + enc(redirectUri)
             + "&scope=talk_message";
    }

    /* ── 토큰 교환/갱신 ─────────────────────────────────────────── */

    /** 인가코드로 토큰 교환 후 사용자에 저장. 성공 시 true. */
    public static boolean exchangeAndSave(long userId, String code, String redirectUri) {
        String body = "grant_type=authorization_code"
                    + "&client_id=" + enc(restKey())
                    + "&redirect_uri=" + enc(redirectUri)
                    + "&code=" + enc(code)
                    + clientSecretParam();
        JsonObject res = postForm(AUTH_BASE + "/oauth/token", null, body);
        if (res == null || !res.has("access_token")) {
            System.err.println("[Kakao] token exchange failed: " + res);
            return false;
        }
        saveTokens(userId, res.get("access_token").getAsString(),
                res.has("refresh_token") ? res.get("refresh_token").getAsString() : null,
                res.has("expires_in") ? res.get("expires_in").getAsInt() : 21600);
        return true;
    }

    /** 유효한 access token 확보(만료 임박 시 refresh). 미연동/실패 시 null. */
    private static String ensureAccessToken(long userId) {
        String access = null, refresh = null; Timestamp exp = null;
        try (Connection c = Db.conn();
             PreparedStatement ps = c.prepareStatement(
                "SELECT access_token, refresh_token, access_expires_at FROM user_kakao WHERE user_id=?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                access = rs.getString(1); refresh = rs.getString(2); exp = rs.getTimestamp(3);
            }
        } catch (Exception e) { System.err.println("[Kakao] loadTokens: " + e.getMessage()); return null; }

        boolean expiring = exp == null || exp.getTime() <= System.currentTimeMillis() + 60_000L;
        if (!expiring) return access;

        // refresh
        String body = "grant_type=refresh_token&client_id=" + enc(restKey()) + "&refresh_token=" + enc(refresh) + clientSecretParam();
        JsonObject res = postForm(AUTH_BASE + "/oauth/token", null, body);
        if (res == null || !res.has("access_token")) { System.err.println("[Kakao] refresh failed: " + res); return null; }
        String newAccess = res.get("access_token").getAsString();
        String newRefresh = res.has("refresh_token") ? res.get("refresh_token").getAsString() : refresh;
        int expiresIn = res.has("expires_in") ? res.get("expires_in").getAsInt() : 21600;
        saveTokens(userId, newAccess, newRefresh, expiresIn);
        return newAccess;
    }

    private static void saveTokens(long userId, String access, String refresh, int expiresInSec) {
        Timestamp exp = new Timestamp(System.currentTimeMillis() + expiresInSec * 1000L);
        String sql = "INSERT INTO user_kakao (user_id, access_token, refresh_token, access_expires_at) VALUES (?,?,?,?) "
                   + "ON DUPLICATE KEY UPDATE access_token=VALUES(access_token), "
                   + "refresh_token=COALESCE(VALUES(refresh_token), refresh_token), access_expires_at=VALUES(access_expires_at)";
        try (Connection c = Db.conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId); ps.setString(2, access);
            ps.setString(3, refresh != null ? refresh : access /*placeholder; NOT NULL*/);
            ps.setTimestamp(4, exp);
            ps.executeUpdate();
        } catch (Exception e) { System.err.println("[Kakao] saveTokens: " + e.getMessage()); }
    }

    public static boolean isLinked(long userId) {
        try (Connection c = Db.conn();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM user_kakao WHERE user_id=?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (Exception e) { return false; }
    }

    public static void unlink(long userId) {
        try (Connection c = Db.conn(); PreparedStatement ps = c.prepareStatement("DELETE FROM user_kakao WHERE user_id=?")) {
            ps.setLong(1, userId); ps.executeUpdate();
        } catch (Exception e) { System.err.println("[Kakao] unlink: " + e.getMessage()); }
    }

    /* ── 메시지 전송 ────────────────────────────────────────────── */

    /** 본인 카카오톡으로 텍스트+링크 전송. 성공 시 true. */
    public static boolean sendToMe(long userId, String text, String webUrl) {
        if (!isConfigured()) return false;
        String access = ensureAccessToken(userId);
        if (access == null) return false;
        return sendMemo(access, text, webUrl);
    }

    private static boolean sendMemo(String accessToken, String text, String webUrl) {
        String t = text == null ? "" : (text.length() > 190 ? text.substring(0, 190) + "…" : text);
        if (webUrl == null || webUrl.isEmpty()) webUrl = appBaseUrl() + "/user/dashboard.jsp";
        JsonObject link = new JsonObject();
        link.addProperty("web_url", webUrl);
        link.addProperty("mobile_web_url", webUrl);
        JsonObject tpl = new JsonObject();
        tpl.addProperty("object_type", "text");
        tpl.addProperty("text", t);
        tpl.add("link", link);
        tpl.addProperty("button_title", "대시보드 열기");
        String body = "template_object=" + enc(tpl.toString());
        JsonObject res = postForm(API_BASE + "/v2/api/talk/memo/default/send", "Bearer " + accessToken, body);
        boolean ok = res != null && res.has("result_code") && res.get("result_code").getAsInt() == 0;
        if (!ok) System.err.println("[Kakao] send failed: " + res);
        return ok;
    }

    /* ── HTTP ───────────────────────────────────────────────────── */

    private static JsonObject postForm(String urlStr, String authHeader, String body) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(urlStr).openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(5000);
            con.setReadTimeout(7000);
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=utf-8");
            if (authHeader != null) con.setRequestProperty("Authorization", authHeader);
            try (OutputStream os = con.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
            int sc = con.getResponseCode();
            InputStream is = (sc >= 200 && sc < 300) ? con.getInputStream() : con.getErrorStream();
            String resp = read(is);
            if (resp == null || resp.isEmpty()) return null;
            return JsonParser.parseString(resp).getAsJsonObject();
        } catch (Exception e) {
            System.err.println("[Kakao] HTTP error " + urlStr + ": " + e.getMessage());
            return null;
        } finally { if (con != null) con.disconnect(); }
    }

    private static String read(InputStream is) throws Exception {
        if (is == null) return null;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line; while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; }
    }
}
