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
}
