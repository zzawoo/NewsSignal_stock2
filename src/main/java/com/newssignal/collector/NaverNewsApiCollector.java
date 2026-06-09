package com.newssignal.collector;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 네이버 뉴스 검색 API 수집기 (기본 수집기, 계획서 4.1).
 * - CORS 회피를 위해 반드시 서버사이드에서 호출 (브라우저 직접 호출 금지).
 * - Client ID/Secret은 서버 환경변수로 보관, 로그/프론트 노출 금지 (계획서 9장).
 * - 제공 파라미터: query, display(≤100), start(≤1000), sort(sim/date).
 */
public class NaverNewsApiCollector implements NewsCollector {

    private static final String ENDPOINT = "https://openapi.naver.com/v1/search/news.json";
    private final String clientId;
    private final String clientSecret;
    private final int timeoutMs;

    public NaverNewsApiCollector(String clientId, String clientSecret, int timeoutMs) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.timeoutMs = timeoutMs;
    }

    @Override public String name() { return "NaverNewsApiCollector"; }

    @Override public boolean isEnabled() {
        return clientId != null && !clientId.isEmpty()
            && clientSecret != null && !clientSecret.isEmpty();
    }

    @Override
    public List<NewsArticleDTO> collect(String keyword, int display) {
        List<NewsArticleDTO> out = new ArrayList<>();
        if (!isEnabled()) {
            System.err.println("[Naver] API key not configured");
            return out;
        }
        HttpURLConnection conn = null;
        try {
            String q = URLEncoder.encode(keyword, StandardCharsets.UTF_8.name());
            int d = Math.min(Math.max(display, 1), 100);   // 1~100
            String url = ENDPOINT + "?query=" + q + "&display=" + d + "&sort=date"; // 최신순

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-Naver-Client-Id", clientId);
            conn.setRequestProperty("X-Naver-Client-Secret", clientSecret);
            conn.setRequestProperty("User-Agent", "NewsSignalAI/1.0");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);

            int code = conn.getResponseCode();
            if (code == 429) { System.err.println("[Naver] 429 rate limited"); return out; }
            if (code != 200) { System.err.println("[Naver] HTTP " + code); return out; }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }

            JsonObject root = JsonParser.parseString(sb.toString()).getAsJsonObject();
            JsonArray items = root.getAsJsonArray("items");
            for (int i = 0; i < items.size(); i++) {
                JsonObject it = items.get(i).getAsJsonObject();
                NewsArticleDTO n = new NewsArticleDTO();
                n.title       = stripTags(getStr(it, "title"));
                n.description = stripTags(getStr(it, "description"));
                n.originalLink= getStr(it, "originallink");
                n.naverLink   = getStr(it, "link");
                n.pubDate     = LocalDateTime.now(); // pubDate 파싱은 운영 시 RFC1123 처리
                n.sourceType  = "NAVER_API";
                out.add(n);
            }
        } catch (Exception e) {
            // API Key 등 민감정보가 메시지에 섞이지 않도록 일반 메시지만 로깅
            System.err.println("[Naver] collect failed: " + e.getClass().getSimpleName());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return out;
    }

    private static String getStr(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }
    private static String stripTags(String s) {
        return s == null ? "" : s.replaceAll("<[^>]+>", "")
                                 .replace("&quot;", "\"").replace("&amp;", "&")
                                 .replace("&lt;", "<").replace("&gt;", ">").trim();
    }
}
