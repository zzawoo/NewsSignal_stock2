package com.newssignal.common;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MacroDataClient {

    public static JsonObject getMacroPrices() {
        JsonObject result = new JsonObject();
        
        // 1. KOSPI
        result.add("KOSPI", getNaverIndex("KOSPI"));
        
        // 2. KOSDAQ
        result.add("KOSDAQ", getNaverIndex("KOSDAQ"));
        
        // 3. USD/KRW Exchange Rate
        result.add("환율", getNaverExchange());
        
        // 4. Oil, Gold, Silver (from finance.naver.com HTML parsing)
        fetchCommoditiesFromHtml(result);

        return result;
    }

    private static JsonObject getNaverIndex(String code) {
        JsonObject obj = new JsonObject();
        try {
            URL url = new URL("https://m.stock.naver.com/api/index/" + code + "/basic");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            
            try (InputStreamReader isr = new InputStreamReader(conn.getInputStream(), "UTF-8")) {
                JsonObject resp = new Gson().fromJson(isr, JsonObject.class);
                obj.addProperty("price", resp.has("closePrice") ? resp.get("closePrice").getAsString() : "-");
                
                String sign = "3";
                if (resp.has("compareToPreviousPrice") && !resp.get("compareToPreviousPrice").isJsonNull()) {
                    JsonObject comp = resp.getAsJsonObject("compareToPreviousPrice");
                    if (comp.has("code")) sign = comp.get("code").getAsString();
                }
                obj.addProperty("sign", sign);
                
                String diff = resp.has("compareToPreviousClosePrice") ? resp.get("compareToPreviousClosePrice").getAsString() : "0";
                obj.addProperty("diff", diff.replace("-", ""));
                
                String ratio = resp.has("fluctuationsRatio") ? resp.get("fluctuationsRatio").getAsString() : "0.00";
                obj.addProperty("ratio", ratio);
            }
        } catch (Exception e) {
            e.printStackTrace();
            obj.addProperty("price", "-");
            obj.addProperty("diff", "0");
            obj.addProperty("ratio", "0.00");
            obj.addProperty("sign", "3");
        }
        return obj;
    }

    private static JsonObject getNaverExchange() {
        JsonObject obj = new JsonObject();
        try {
            URL url = new URL("https://m.stock.naver.com/front-api/marketIndex/prices?category=exchange&reutersCode=FX_USDKRW");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            
            try (InputStreamReader isr = new InputStreamReader(conn.getInputStream(), "UTF-8")) {
                JsonObject resp = new Gson().fromJson(isr, JsonObject.class);
                if (resp.has("result") && resp.get("result").isJsonArray()) {
                    JsonArray arr = resp.getAsJsonArray("result");
                    if (arr.size() > 0) {
                        JsonObject today = arr.get(0).getAsJsonObject();
                        obj.addProperty("price", today.has("closePrice") ? today.get("closePrice").getAsString() : "-");
                        String diff = today.has("fluctuations") ? today.get("fluctuations").getAsString() : "0";
                        String ratio = today.has("fluctuationsRatio") ? today.get("fluctuationsRatio").getAsString() : "0.00";
                        
                        String sign = "3";
                        if (today.has("fluctuationsType")) {
                            JsonObject typeObj = today.getAsJsonObject("fluctuationsType");
                            if (typeObj.has("code")) {
                                sign = typeObj.get("code").getAsString(); // "2"=상승, "5"=하락
                            }
                        }
                        obj.addProperty("sign", sign);
                        obj.addProperty("diff", diff.replace("-", ""));
                        obj.addProperty("ratio", ratio.replace("-", ""));
                        return obj;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        obj.addProperty("price", "-");
        obj.addProperty("diff", "0");
        obj.addProperty("ratio", "0.00");
        obj.addProperty("sign", "3");
        return obj;
    }

    private static void fetchCommoditiesFromHtml(JsonObject result) {
        String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

        result.add("유가", fetchDailyQuote("OIL_CL", userAgent));
        result.add("금", fetchDailyQuote("CMDT_GC", userAgent));
        result.add("은", fetchDailyQuote("CMDT_SI", userAgent));
    }

    private static JsonObject fetchDailyQuote(String code, String userAgent) {
        JsonObject obj = new JsonObject();
        try {
            Document doc = Jsoup.connect("https://finance.naver.com/marketindex/worldDailyQuote.naver?marketindexCd=" + code + "&fdtc=2")
                .userAgent(userAgent)
                .timeout(3000)
                .get();
            
            Element tbody = doc.selectFirst("tbody");
            if (tbody != null) {
                Element tr = tbody.selectFirst("tr");
                if (tr != null) {
                    obj.addProperty("price", tr.child(1).text().trim());
                    obj.addProperty("diff", tr.child(2).text().trim());
                    obj.addProperty("ratio", tr.child(3).text().trim().replace("+", "").replace("%", ""));
                    
                    String sign = "3";
                    if (tr.hasClass("up")) sign = "2";
                    else if (tr.hasClass("down")) sign = "5";
                    obj.addProperty("sign", sign);
                    return obj;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        obj.addProperty("price", "-");
        obj.addProperty("diff", "0");
        obj.addProperty("ratio", "0.00");
        obj.addProperty("sign", "3");
        return obj;
    }

    private static void parseHtmlElement(Element infoEl, JsonObject obj) {
        if (infoEl == null) return;
        Element valEl = infoEl.selectFirst("span.value");
        if (valEl != null) obj.addProperty("price", valEl.text());
        
        Element changeEl = infoEl.selectFirst("span.change");
        if (changeEl != null) obj.addProperty("diff", changeEl.text());
        
        String sign = "3";
        if (infoEl.hasClass("point_up")) sign = "2";
        else if (infoEl.hasClass("point_dn")) sign = "5";
        obj.addProperty("sign", sign);
        
        obj.addProperty("ratio", "0.00"); // Ratio is hard to parse easily from this block, leave 0.00 or compute later
    }
    
    private static void setEmpty(JsonObject obj) {
        obj.addProperty("price", "-");
        obj.addProperty("diff", "0");
        obj.addProperty("ratio", "0.00");
        obj.addProperty("sign", "3");
    }

    /* ── 카드용 미니 그래프(스파크라인) 시계열 ── 최근 ~12일 종가. 일별이라 5분 캐시. */
    private static volatile JsonObject sparkCache = null;
    private static volatile long sparkCacheTs = 0;
    private static final long SPARK_TTL_MS = 5 * 60 * 1000;
    private static final int SPARK_POINTS = 12;

    public static JsonObject getSparklines() {
        long now = System.currentTimeMillis();
        JsonObject c = sparkCache;
        if (c != null && now - sparkCacheTs < SPARK_TTL_MS) return c;
        JsonObject r = new JsonObject();
        r.add("KOSPI", indexSeries("KOSPI"));
        r.add("KOSDAQ", indexSeries("KOSDAQ"));
        r.add("환율", fxSeries());
        r.add("유가", commoditySeries("OIL_CL"));
        r.add("금", commoditySeries("CMDT_GC"));
        r.add("은", commoditySeries("CMDT_SI"));
        sparkCache = r;
        sparkCacheTs = now;
        return r;
    }

    private static JsonArray tail(java.util.List<Double> vals) {
        JsonArray out = new JsonArray();
        int from = Math.max(0, vals.size() - SPARK_POINTS);
        for (int i = from; i < vals.size(); i++) out.add(vals.get(i));
        return out;
    }

    private static String httpGet(String urlStr, String charset) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(4000);
        try (java.io.InputStream is = conn.getInputStream();
             java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[4096]; int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), charset);
        }
    }

    /** 지수(코스피/코스닥) 일별 종가 — siseJson(헤더 단일따옴표라 정규식 파싱). */
    private static JsonArray indexSeries(String code) {
        java.util.List<Double> vals = new java.util.ArrayList<Double>();
        try {
            java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");
            String end = java.time.LocalDate.now().format(f);
            String start = java.time.LocalDate.now().minusDays(25).format(f);
            String body = httpGet("https://api.finance.naver.com/siseJson.naver?symbol=" + code
                    + "&requestType=1&startTime=" + start + "&endTime=" + end + "&timeframe=day", "UTF-8");
            // 행: ["YYYYMMDD", 시가, 고가, 저가, 종가, 거래량, ...] → 종가(5번째) 추출
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "\\[\\s*\"\\d{8}\"\\s*,\\s*[\\d.\\-]+\\s*,\\s*[\\d.\\-]+\\s*,\\s*[\\d.\\-]+\\s*,\\s*([\\d.\\-]+)").matcher(body);
            while (m.find()) { try { vals.add(Double.parseDouble(m.group(1))); } catch (Exception ig) {} }
        } catch (Exception e) { e.printStackTrace(); }
        return tail(vals); // siseJson은 과거→최신 순
    }

    /** 환율(USD/KRW) 일별 종가 — marketIndex/prices(최신순 → 역순). */
    private static JsonArray fxSeries() {
        java.util.List<Double> vals = new java.util.ArrayList<Double>();
        try {
            String body = httpGet("https://m.stock.naver.com/front-api/marketIndex/prices?category=exchange&reutersCode=FX_USDKRW&page=1&pageSize=20", "UTF-8");
            JsonObject resp = new Gson().fromJson(body, JsonObject.class);
            if (resp != null && resp.has("result") && resp.get("result").isJsonArray()) {
                JsonArray arr = resp.getAsJsonArray("result");
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject o = arr.get(i).getAsJsonObject();
                    if (o.has("closePrice")) {
                        try { vals.add(Double.parseDouble(o.get("closePrice").getAsString().replace(",", ""))); } catch (Exception ig) {}
                    }
                }
                java.util.Collections.reverse(vals); // 최신→과거 이므로 뒤집어 시간순
            }
        } catch (Exception e) { e.printStackTrace(); }
        return tail(vals);
    }

    /** 상품(유가/금/은) 일별 종가 — worldDailyQuote 표(최신순 → 역순). */
    private static JsonArray commoditySeries(String code) {
        java.util.List<Double> vals = new java.util.ArrayList<Double>();
        try {
            Document doc = Jsoup.connect("https://finance.naver.com/marketindex/worldDailyQuote.naver?marketindexCd=" + code + "&fdtc=2")
                    .userAgent("Mozilla/5.0").timeout(4000).get();
            Element tbody = doc.selectFirst("tbody");
            if (tbody != null) {
                for (Element tr : tbody.select("tr")) {
                    if (tr.children().size() > 1) {
                        String c = tr.child(1).text().trim().replace(",", "");
                        try { vals.add(Double.parseDouble(c)); } catch (Exception ig) {}
                    }
                }
            }
            java.util.Collections.reverse(vals);
        } catch (Exception e) { e.printStackTrace(); }
        return tail(vals);
    }
}
