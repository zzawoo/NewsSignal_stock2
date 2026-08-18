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

    /* ── 종목별 현재가 캐시: 실시간성 위해 5초만 유지 (호출량 절감 + 한도초과 시 마지막값 재사용) ── */
    private static final long PRICE_CACHE_TTL_MS = 5000; // 5초
    private static final java.util.Map<String, CachedPrice> priceCache =
            new java.util.concurrent.ConcurrentHashMap<String, CachedPrice>();

    /* ── 차트(분/일/주/월) 캐시: 트래픽 크므로 60초 유지 ── */
    private static final long CHART_CACHE_TTL_MS = 60000; // 60초
    private static final java.util.Map<String, CachedPrice> chartCache =
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

    /** 응답이 "만료된 토큰"(EGW00123) 오류인지 판정. */
    private static boolean isExpiredTokenError(JsonObject r) {
        if (r == null) return false;
        String mc = (r.has("msg_cd") && !r.get("msg_cd").isJsonNull()) ? r.get("msg_cd").getAsString() : "";
        if ("EGW00123".equals(mc)) return true;
        String m1 = (r.has("msg1") && !r.get("msg1").isJsonNull()) ? r.get("msg1").getAsString() : "";
        return m1.contains("만료된 token") || m1.toLowerCase().contains("expired");
    }

    /**
     * 만료/무효 토큰 폐기 → 즉시 재발급 허용. 디스크 토큰도 삭제(만료본 재로딩 방지).
     * 경합 방어: 실패한 토큰이 현재 토큰과 다르면(이미 새 토큰으로 교체됨) 무시.
     */
    private static synchronized void invalidateToken(String failedToken) {
        if (failedToken != null && !failedToken.equals(accessToken)) return;
        accessToken = null;
        tokenExpiry = 0;
        tokenRetryAfter = 0; // 만료 토큰은 쿨다운 없이 즉시 재발급
        try { if (TOKEN_FILE.exists()) TOKEN_FILE.delete(); } catch (Exception ignore) {}
        System.err.println("[KIS] expired token invalidated → will re-issue");
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

        // 만료 토큰(EGW00123) 등으로 실패하면 토큰을 폐기·재발급해 1회 재시도
        for (int attempt = 0; attempt < 2; attempt++) {
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
                    }
                    // 만료 토큰이면 폐기 후 재발급해 재시도
                    if (attempt == 0 && isExpiredTokenError(response)) {
                        invalidateToken(token);
                        continue;
                    }
                    System.err.println("[KIS] getStockPrice error (" + responseCode + "): " + response);
                    // 한도초과 등 실패 시 마지막 정상값 반환 (0 깜빡임 방지)
                    return cached != null ? cached.data : null;
                }
            } catch (Exception e) {
                e.printStackTrace();
                return cached != null ? cached.data : null;
            }
        }
        return cached != null ? cached.data : null;
    }

    /** 인증 GET 후 JSON 반환 (throttle 적용). 만료 토큰이면 재발급해 1회 재시도. 실패 시 null. */
    private static JsonObject getJson(String urlStr, String trId) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = getToken();
            if (token == null) return null;
            throttle();
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("authorization", "Bearer " + token);
                conn.setRequestProperty("appkey", APP_KEY);
                conn.setRequestProperty("appsecret", APP_SECRET);
                conn.setRequestProperty("tr_id", trId);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(7000);
                int rc = conn.getResponseCode();
                java.io.InputStream is = (rc >= 200 && rc < 300) ? conn.getInputStream() : conn.getErrorStream();
                if (is == null) return null;
                try (InputStreamReader isr = new InputStreamReader(is, "UTF-8")) {
                    JsonObject resp = new Gson().fromJson(isr, JsonObject.class);
                    if (rc >= 200 && rc < 300) return resp;
                    if (attempt == 0 && isExpiredTokenError(resp)) { invalidateToken(token); continue; }
                    System.err.println("[KIS] getJson error (" + rc + ", " + trId + "): " + resp);
                    return null;
                }
            } catch (Exception e) { e.printStackTrace(); return null; }
        }
        return null;
    }

    /**
     * 차트 데이터. tf: D/W/M(일/주/월봉) 또는 1/5/10(분봉).
     * 정규화 결과 {"candles":[{t,ts,o,h,l,c,v}, ...]} (시간순). 60초 캐시.
     */
    public static JsonObject getChart(String stockCode, String tf) {
        if (tf == null || tf.trim().isEmpty()) tf = "D";
        final String t = tf.trim();
        String key = stockCode + "|" + t;
        CachedPrice c = chartCache.get(key);
        long now = System.currentTimeMillis();
        if (c != null && now - c.ts < CHART_CACHE_TTL_MS) return c.data;
        JsonObject result;
        if (t.equals("D") || t.equals("W") || t.equals("M")) {
            result = fetchDailyChart(stockCode, t);
        } else {
            int minutes = t.equals("5") ? 5 : t.equals("10") ? 10 : 1;
            result = fetchMinuteChart(stockCode, minutes);
        }
        if (result != null) { chartCache.put(key, new CachedPrice(result, now)); return result; }
        return c != null ? c.data : null;
    }

    private static JsonObject fetchDailyChart(String code, String period) {
        // KIS는 호출당 최대 100봉 → 일봉은 날짜창을 뒤로 밀며 여러 번 호출해 과거 데이터를 더 확보(약 300봉, MA120 등 장기지표 지원).
        java.time.LocalDate end = java.time.LocalDate.now();
        java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");
        int pages = period.equals("D") ? 3 : 1;                 // 주/월은 1콜로 장기간 커버됨
        int windowDays = period.equals("M") ? 4000 : period.equals("W") ? 1500 : 150;
        java.util.TreeMap<String, JsonObject> byDate = new java.util.TreeMap<String, JsonObject>(); // ts 오름차순
        for (int p = 0; p < pages; p++) {
            String url = BASE_URL + "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice"
                    + "?fid_cond_mrkt_div_code=J&fid_input_iscd=" + code
                    + "&fid_input_date_1=" + end.minusDays(windowDays).format(dtf)
                    + "&fid_input_date_2=" + end.format(dtf)
                    + "&fid_period_div_code=" + period + "&fid_org_adj_prc=0";
            JsonObject resp = getJson(url, "FHKST03010100");
            if (resp == null || !resp.has("output2") || !resp.get("output2").isJsonArray()) break;
            com.google.gson.JsonArray out = resp.getAsJsonArray("output2");
            if (out.size() == 0) break;
            String oldest = null;
            for (int i = 0; i < out.size(); i++) {
                JsonObject d = out.get(i).getAsJsonObject();
                String date = optStr(d, "stck_bsop_date");
                double clpr = optNum(d, "stck_clpr");
                if (date == null || date.length() < 8 || clpr <= 0) continue;
                if (!byDate.containsKey(date)) {
                    JsonObject k = new JsonObject();
                    k.addProperty("t", date.substring(4, 6) + "/" + date.substring(6, 8));
                    k.addProperty("ts", date);
                    k.addProperty("o", optNum(d, "stck_oprc"));
                    k.addProperty("h", optNum(d, "stck_hgpr"));
                    k.addProperty("l", optNum(d, "stck_lwpr"));
                    k.addProperty("c", clpr);
                    k.addProperty("v", optNum(d, "acml_vol"));
                    byDate.put(date, k);
                }
                if (oldest == null || date.compareTo(oldest) < 0) oldest = date;
            }
            if (oldest == null) break;
            end = java.time.LocalDate.parse(oldest, dtf).minusDays(1); // 다음 페이지는 가장 오래된 날짜 직전부터
        }
        if (byDate.isEmpty()) return null;
        com.google.gson.JsonArray candles = new com.google.gson.JsonArray();
        for (JsonObject k : byDate.values()) candles.add(k);
        JsonObject r = new JsonObject(); r.add("candles", candles); return r;
    }

    private static JsonObject fetchMinuteChart(String code, int minutes) {
        java.util.List<JsonObject> oneMin = new java.util.ArrayList<JsonObject>();
        String hour = "";
        for (int page = 0; page < 4; page++) {
            String url = BASE_URL + "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice"
                    + "?fid_etc_cls_code=&fid_cond_mrkt_div_code=J&fid_input_iscd=" + code
                    + "&fid_input_hour_1=" + hour + "&fid_pw_data_incu_yn=Y";
            JsonObject resp = getJson(url, "FHKST03010200");
            if (resp == null || !resp.has("output2") || !resp.get("output2").isJsonArray()) break;
            com.google.gson.JsonArray out = resp.getAsJsonArray("output2");
            if (out.size() == 0) break;
            for (int i = 0; i < out.size(); i++) {
                JsonObject d = out.get(i).getAsJsonObject();
                if (optNum(d, "stck_prpr") > 0) oneMin.add(d);
            }
            String lastHour = optStr(out.get(out.size() - 1).getAsJsonObject(), "stck_cntg_hour");
            if (lastHour == null) break;
            hour = minusOneMinute(lastHour);
            if (hour == null) break;
        }
        if (oneMin.isEmpty()) return null;
        oneMin.sort(new java.util.Comparator<JsonObject>() {
            public int compare(JsonObject a, JsonObject b) {
                return (str(a, "stck_bsop_date") + str(a, "stck_cntg_hour")).compareTo(str(b, "stck_bsop_date") + str(b, "stck_cntg_hour"));
            }
        });
        com.google.gson.JsonArray candles = new com.google.gson.JsonArray();
        JsonObject cur = null; int count = 0;
        for (JsonObject d : oneMin) {
            if (cur == null || count >= minutes) {
                if (cur != null) candles.add(cur);
                cur = new JsonObject();
                String hh = str(d, "stck_cntg_hour");
                cur.addProperty("t", (hh.length() >= 4 ? hh.substring(0, 2) + ":" + hh.substring(2, 4) : hh));
                cur.addProperty("ts", str(d, "stck_bsop_date") + hh);
                cur.addProperty("o", optNum(d, "stck_oprc"));
                cur.addProperty("h", optNum(d, "stck_hgpr"));
                cur.addProperty("l", optNum(d, "stck_lwpr"));
                cur.addProperty("c", optNum(d, "stck_prpr"));
                cur.addProperty("v", optNum(d, "cntg_vol"));
                count = 1;
            } else {
                cur.addProperty("h", Math.max(cur.get("h").getAsDouble(), optNum(d, "stck_hgpr")));
                cur.addProperty("l", Math.min(cur.get("l").getAsDouble(), optNum(d, "stck_lwpr")));
                cur.addProperty("c", optNum(d, "stck_prpr"));
                cur.addProperty("v", cur.get("v").getAsDouble() + optNum(d, "cntg_vol"));
                count++;
            }
        }
        if (cur != null) candles.add(cur);
        JsonObject r = new JsonObject(); r.add("candles", candles); return r;
    }

    private static String minusOneMinute(String hhmmss) {
        try {
            int total = Integer.parseInt(hhmmss.substring(0, 2)) * 60 + Integer.parseInt(hhmmss.substring(2, 4)) - 1;
            if (total < 0) return null;
            return String.format("%02d%02d00", total / 60, total % 60);
        } catch (Exception e) { return null; }
    }

    private static String str(JsonObject o, String k) { String s = optStr(o, k); return s == null ? "" : s; }
    private static String optStr(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }
    private static double optNum(JsonObject o, String k) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? Double.parseDouble(o.get(k).getAsString().trim()) : 0; }
        catch (Exception e) { return 0; }
    }
}
