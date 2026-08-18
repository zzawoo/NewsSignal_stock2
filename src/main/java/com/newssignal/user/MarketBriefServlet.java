package com.newssignal.user;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 장중 시황정리: 코스피/코스닥 지수 + 투자자별 순매수(개인/외국인/기관) + 자동 요약 라인.
 * 네이버 지수 API(basic/trend)만 사용, 90초 캐시. LLM 미사용(항상 즉시 응답).
 *   /api/market/brief → { generatedAt, indices:[...], investors:{...}, lines:[...] }
 */
@WebServlet("/api/market/brief")
public class MarketBriefServlet extends HttpServlet {

    private static volatile JsonObject cache;
    private static volatile long cacheTs;
    private static final long TTL_MS = 90_000L;
    private static final Object LOCK = new Object();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        long now = System.currentTimeMillis();
        JsonObject c = cache;
        if (c == null || now - cacheTs >= TTL_MS) {
            synchronized (LOCK) {
                if (cache == null || System.currentTimeMillis() - cacheTs >= TTL_MS) {
                    cache = compute(); cacheTs = System.currentTimeMillis();
                }
            }
        }
        try (PrintWriter w = resp.getWriter()) { w.print(cache.toString()); }
    }

    private JsonObject compute() {
        JsonObject out = new JsonObject();
        JsonArray indices = new JsonArray();
        JsonObject investors = new JsonObject();

        double kospiPct = 0, kosdaqPct = 0;
        String kospiDir = "FLAT";
        double[] kospiInv = null;

        String[][] want = { {"KOSPI", "코스피"}, {"KOSDAQ", "코스닥"} };
        for (String[] mk : want) {
            JsonObject basic = fetchJson("https://m.stock.naver.com/api/index/" + mk[0] + "/basic");
            JsonObject idx = new JsonObject();
            idx.addProperty("name", mk[1]);
            double pct = 0; String dir = "FLAT"; String price = "-";
            if (basic != null) {
                price = str(basic, "closePrice", "-");
                pct = num(basic, "fluctuationsRatio");
                if (basic.has("compareToPreviousPrice") && basic.get("compareToPreviousPrice").isJsonObject()) {
                    String code = str(basic.getAsJsonObject("compareToPreviousPrice"), "code", "3");
                    dir = ("1".equals(code) || "2".equals(code)) ? "UP" : ("4".equals(code) || "5".equals(code)) ? "DOWN" : "FLAT";
                }
                idx.addProperty("change", str(basic, "compareToPreviousClosePrice", ""));
            }
            idx.addProperty("price", price);
            idx.addProperty("chgPct", pct);
            idx.addProperty("dir", dir);
            indices.add(idx);

            // 투자자별 순매수 (억원)
            JsonObject trend = fetchJson("https://m.stock.naver.com/api/index/" + mk[0] + "/trend");
            if (trend != null) {
                JsonObject inv = new JsonObject();
                inv.addProperty("personal", str(trend, "personalValue", ""));
                inv.addProperty("foreign", str(trend, "foreignValue", ""));
                inv.addProperty("institution", str(trend, "institutionalValue", ""));
                investors.add("KOSPI".equals(mk[0]) ? "kospi" : "kosdaq", inv);
                if ("KOSPI".equals(mk[0])) {
                    kospiInv = new double[]{ toNum(str(trend, "foreignValue", "0")),
                                             toNum(str(trend, "institutionalValue", "0")),
                                             toNum(str(trend, "personalValue", "0")) };
                }
            }
            if ("KOSPI".equals(mk[0])) { kospiPct = pct; kospiDir = dir; }
            else kosdaqPct = pct;
        }

        // 자동 요약 라인 (템플릿, LLM 미사용)
        JsonArray lines = new JsonArray();
        lines.add(String.format("코스피 %s%.2f%%, 코스닥 %s%.2f%% %s.",
                kospiPct >= 0 ? "+" : "", kospiPct, kosdaqPct >= 0 ? "+" : "", kosdaqPct,
                (kospiPct >= 0 && kosdaqPct >= 0) ? "동반 강세" : (kospiPct < 0 && kosdaqPct < 0) ? "동반 약세" : "혼조"));
        if (kospiInv != null) {
            lines.add(String.format("코스피 투자자별 순매수 — 외국인 %s억, 기관 %s억, 개인 %s억.",
                    signStr(kospiInv[0]), signStr(kospiInv[1]), signStr(kospiInv[2])));
            String driver;
            if (kospiInv[0] < 0 && kospiDir.equals("DOWN")) driver = "외국인 매도세가 지수 하락을 주도";
            else if (kospiInv[0] > 0 && kospiDir.equals("UP")) driver = "외국인 순매수가 지수 상승을 견인";
            else if (kospiInv[2] > 0 && kospiInv[0] < 0) driver = "개인이 외국인 매도 물량을 받아내는 구도";
            else driver = "기관·외국인 수급이 지수 방향을 좌우";
            lines.add(driver + ".");
        }
        lines.add("관전 포인트: 외국인 순매수 전환 여부와 대형주(반도체) 흐름.");

        out.addProperty("generatedAt", new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date()));
        out.add("indices", indices);
        out.add("investors", investors);
        out.add("lines", lines);
        return out;
    }

    private JsonObject fetchJson(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(4000);
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = br.readLine()) != null) sb.append(line);
            }
            return JsonParser.parseString(sb.toString()).getAsJsonObject();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String str(JsonObject o, String k, String def) {
        try { return (o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : def; }
        catch (Exception e) { return def; }
    }
    private static double num(JsonObject o, String k) {
        try {
            if (!o.has(k) || o.get(k).isJsonNull()) return 0;
            return Double.parseDouble(o.get(k).getAsString().replace(",", "").replace("+", "").trim());
        } catch (Exception e) { return 0; }
    }
    private static double toNum(String s) {
        try { return Double.parseDouble(s.replace(",", "").replace("+", "").trim()); }
        catch (Exception e) { return 0; }
    }
    /** 부호 유지 + 천단위 콤마 (예: -23,097 / +36,004). */
    private static String signStr(double v) {
        long r = Math.round(v);
        return (r > 0 ? "+" : "") + String.format("%,d", r);
    }
}
