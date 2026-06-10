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

    private static String accessToken = null;
    private static long tokenExpiry = 0;

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
                    System.out.println("[KIS] Token refreshed successfully.");
                } else {
                    System.err.println("[KIS] refreshToken failed (code=" + responseCode + "): " + response);
                }
            }
        } catch (Exception e) {
            System.err.println("[KIS] refreshToken exception: " + e.getMessage());
        }
    }


    private static String getToken() {
        if (accessToken == null || System.currentTimeMillis() > tokenExpiry) {
            refreshToken();
        }
        return accessToken;
    }

    public static JsonObject getStockPrice(String stockCode) {
        String token = getToken();
        if (token == null) {
            return null;
        }

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

            if (is == null) return null;

            try (InputStreamReader isr = new InputStreamReader(is, "UTF-8")) {
                Gson gson = new Gson();
                JsonObject response = gson.fromJson(isr, JsonObject.class);
                if (responseCode >= 200 && responseCode < 300 && response.has("output")) {
                    return response.getAsJsonObject("output");
                } else {
                    System.err.println("[KIS] getStockPrice error (" + responseCode + "): " + response);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
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
