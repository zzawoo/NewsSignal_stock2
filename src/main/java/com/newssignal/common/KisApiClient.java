package com.newssignal.common;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class KisApiClient {

    private static final String APP_KEY = System.getenv("KIS_APP_KEY");
    private static final String APP_SECRET = System.getenv("KIS_APP_SECRET");
    private static final String BASE_URL = "https://openapi.koreainvestment.com:9443";

    private static volatile String accessToken = null;
    private static volatile long tokenExpiry = 0;
    private static volatile long tokenRetryAfter = 0; // 토큰 발급 실패 시 재시도 가능 시각

    /* 토큰을 디스크에 보관해 재기동 시 재발급(EGW00133 1분당 1회 제한) 회피.
       KIS access_token은 ~24h 유효하므로 그대로 재사용한다. */
    private static final java.io.File TOKEN_FILE = resolveTokenFile();

    private static java.io.File resolveTokenFile() {
        String dir = System.getProperty("java.io.tmpdir");
        if (dir == null || dir.trim().isEmpty()) dir = System.getProperty("user.home");
        // 일부 환경에서 따옴표가 섞여 들어오는 경우 제거
        dir = dir.trim().replace("\"", "");
        java.io.File d = new java.io.File(dir);
        if (!d.exists()) d.mkdirs();
        return new java.io.File(d, "kis_token.json");
    }

    /* ── 종목별 현재가 캐시 (호출량 절감 + KIS 한도초과 시 마지막 정상값 재사용) ── */
    private static final long PRICE_CACHE_TTL_MS = 12000; // 12초
    private static final java.util.Map<String, CachedPrice> priceCache =
            new java.util.concurrent.ConcurrentHashMap<String, CachedPrice>();

    private static final class CachedPrice {
        final JsonObject data;
        final long ts;
        CachedPrice(JsonObject data, long ts) { this.data = data; this.ts = ts; }
    }

    /* ── KIS 호출 글로벌 throttle (EGW00201 "초당 거래건수 초과" 방지) ── */
    private static final Object throttleLock = new Object();
    private static long lastCallTime = 0;
    private static final long MIN_CALL_INTERVAL_MS = 130; // 약 초당 7~8건 상한

    private static void throttle() {
        synchronized (throttleLock) {
            long wait = MIN_CALL_INTERVAL_MS - (System.currentTimeMillis() - lastCallTime);
            if (wait > 0) {
                try { Thread.sleep(wait); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
            lastCallTime = System.currentTimeMillis();
        }
    }

    private static synchronized void refreshToken() {
        if (APP_KEY == null || APP_SECRET == null || APP_KEY.trim().isEmpty()) {
            System.err.println("[KIS] APP_KEY or APP_SECRET is not set.");
            return;
        }

        try {
            URL url = new URL(BASE_URL + "/oauth2/tokenP");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            String body = String.format("{\"grant_type\":\"client_credentials\", \"appkey\":\"%s\", \"appsecret\":\"%s\"}", APP_KEY, APP_SECRET);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }

            int responseCode = conn.getResponseCode();
            java.io.InputStream is = (responseCode >= 200 && responseCode < 300)
                    ? conn.getInputStream() : conn.getErrorStream();

            if (is == null) {
                System.err.println("[KIS] refreshToken: empty response (code=" + responseCode + ")");
                return;
            }

            try (InputStreamReader isr = new InputStreamReader(is, "UTF-8")) {
                Gson gson = new Gson();
                JsonObject response = gson.fromJson(isr, JsonObject.class);
                if (responseCode >= 200 && responseCode < 300 && response.has("access_token")) {
                    accessToken = response.get("access_token").getAsString();
                    int expiresIn = response.get("expires_in").getAsInt();
                    tokenExpiry = System.currentTimeMillis() + (expiresIn * 1000L) - 60000;
                    saveTokenToDisk();
                    System.out.println("[KIS] Token refreshed successfully.");
                } else {
                    System.err.println("[KIS] refreshToken failed (code=" + responseCode + "): " + response);
                    // KIS는 토큰 발급을 1분당 1회로 제한(EGW00133) → 실패 시 60초 쿨다운
                    tokenRetryAfter = System.currentTimeMillis() + 60000;
                }
            }
        } catch (Exception e) {
            System.err.println("[KIS] refreshToken exception: " + e.getMessage());
            tokenRetryAfter = System.currentTimeMillis() + 60000;
        }
    }


    private static String getToken() {
        // 빠른 경로: 유효한 토큰이 있으면 락 없이 반환
        if (accessToken != null && System.currentTimeMillis() < tokenExpiry) {
            return accessToken;
        }
        // 느린 경로: 한 스레드만 발급 (이중 검사 락으로 동시 다발 발급=EGW00133 방지)
        synchronized (KisApiClient.class) {
            long now = System.currentTimeMillis();
            if (accessToken != null && now < tokenExpiry) {
                return accessToken; // 다른 스레드가 이미 갱신함
            }
            // 디스크에 유효 토큰이 있으면 재발급 없이 재사용 (재기동 직후 storm 방지)
            loadTokenFromDisk();
            if (accessToken != null && now < tokenExpiry) {
                return accessToken;
            }
            if (now < tokenRetryAfter) {
                return accessToken; // 발급 실패 쿨다운 중 (null일 수 있음 → 호출부가 폴백)
            }
            refreshToken();
            return accessToken;
        }
    }

    /* 락 보유 중에만 호출 (getToken/refreshToken 내부). */
    private static void loadTokenFromDisk() {
        if (accessToken != null) return;
        if (!TOKEN_FILE.exists()) return;
        try (InputStreamReader r = new InputStreamReader(new java.io.FileInputStream(TOKEN_FILE), "UTF-8")) {
            JsonObject o = new Gson().fromJson(r, JsonObject.class);
            if (o != null && o.has("access_token") && o.has("expiry")) {
                long exp = o.get("expiry").getAsLong();
                if (System.currentTimeMillis() < exp) {
                    accessToken = o.get("access_token").getAsString();
                    tokenExpiry = exp;
                    System.out.println("[KIS] Token loaded from disk (valid until " + exp + ").");
                }
            }
        } catch (Exception e) {
            System.err.println("[KIS] loadTokenFromDisk failed: " + e.getMessage());
        }
    }

    private static void saveTokenToDisk() {
        if (accessToken == null) return;
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(TOKEN_FILE), "UTF-8")) {
            JsonObject o = new JsonObject();
            o.addProperty("access_token", accessToken);
            o.addProperty("expiry", tokenExpiry);
            new Gson().toJson(o, w);
            w.flush();
            System.out.println("[KIS] Token saved to disk: " + TOKEN_FILE.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[KIS] saveTokenToDisk failed (" + TOKEN_FILE.getAbsolutePath() + "): " + e.getMessage());
        }
    }

    public static JsonObject getStockPrice(String stockCode) {
        // 1. 캐시가 신선하면 그대로 사용 (KIS 미호출)
        CachedPrice cached = priceCache.get(stockCode);
        if (cached != null && (System.currentTimeMillis() - cached.ts) < PRICE_CACHE_TTL_MS) {
            return cached.data;
        }

        String token = getToken();
        if (token == null) {
            return cached != null ? cached.data : null; // 토큰 실패 시 마지막 정상값
        }

        throttle();
        try {
            String urlStr = BASE_URL + "/uapi/domestic-stock/v1/quotations/inquire-price?fid_cond_mrkt_div_code=J&fid_input_iscd=" + stockCode;
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("authorization", "Bearer " + token);
            conn.setRequestProperty("appkey", APP_KEY);
            conn.setRequestProperty("appsecret", APP_SECRET);
            conn.setRequestProperty("tr_id", "FHKST01010100");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();
            java.io.InputStream is = (responseCode >= 200 && responseCode < 300)
                    ? conn.getInputStream() : conn.getErrorStream();

            if (is == null) return cached != null ? cached.data : null;

            try (InputStreamReader isr = new InputStreamReader(is, "UTF-8")) {
                Gson gson = new Gson();
                JsonObject response = gson.fromJson(isr, JsonObject.class);
                if (responseCode >= 200 && responseCode < 300 && response.has("output")) {
                    JsonObject output = response.getAsJsonObject("output");
                    priceCache.put(stockCode, new CachedPrice(output, System.currentTimeMillis()));
                    return output;
                } else {
                    System.err.println("[KIS] getStockPrice error (" + responseCode + "): " + response);
                    // 한도초과 등 실패 시 마지막 정상값 반환 (0 깜빡임 방지)
                    return cached != null ? cached.data : null;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return cached != null ? cached.data : null;
        }
    }

    public static JsonObject getDailyChartPrice(String stockCode) {
        String token = getToken();
        if (token == null) {
            return null;
        }

        try {
            java.time.LocalDate now = java.time.LocalDate.now();
            java.time.LocalDate past = now.minusDays(30);
            java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");
            String today = now.format(dtf);
            String thirtyDaysAgo = past.format(dtf);

            throttle();
            String urlStr = BASE_URL + "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice"
                    + "?fid_cond_mrkt_div_code=J"
                    + "&fid_input_iscd=" + stockCode
                    + "&fid_input_date_1=" + thirtyDaysAgo
                    + "&fid_input_date_2=" + today
                    + "&fid_period_div_code=D"
                    + "&fid_org_adj_prc=0";

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("authorization", "Bearer " + token);
            conn.setRequestProperty("appkey", APP_KEY);
            conn.setRequestProperty("appsecret", APP_SECRET);
            conn.setRequestProperty("tr_id", "FHKST03010100");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();
            java.io.InputStream is = (responseCode >= 200 && responseCode < 300)
                    ? conn.getInputStream() : conn.getErrorStream();

            if (is == null) return null;

            try (InputStreamReader isr = new InputStreamReader(is, "UTF-8")) {
                Gson gson = new Gson();
                JsonObject response = gson.fromJson(isr, JsonObject.class);
                if (responseCode < 200 || responseCode >= 300) {
                    System.err.println("[KIS] getDailyChartPrice error (" + responseCode + "): " + response);
                    return null;
                }
                return response;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
