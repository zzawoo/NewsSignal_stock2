package com.newssignal.user;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.newssignal.common.Db;

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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 섹터(테마/업종)별 실시간 추세: 구성종목 과반이 상승이면 UP, 하락이면 DOWN, 반반/판단불가면 FLAT.
 * 종목 시세는 네이버 일괄(realtime) 시세로 묶어 조회하고 90초 캐시한다. (KIS 한도 보호)
 */
@WebServlet("/api/sector/trend")
public class SectorTrendServlet extends HttpServlet {

    private static volatile JsonObject cache;
    private static volatile long cacheTs;
    private static final long TTL_MS = 90_000L;
    private static final Object LOCK = new Object();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        write(resp, getCached());
    }

    private JsonObject getCached() {
        long now = System.currentTimeMillis();
        JsonObject c = cache;
        if (c != null && now - cacheTs < TTL_MS) return c;
        synchronized (LOCK) {
            if (cache != null && System.currentTimeMillis() - cacheTs < TTL_MS) return cache;
            JsonObject computed = compute();
            cache = computed; cacheTs = System.currentTimeMillis();
            return computed;
        }
    }

    private JsonObject compute() {
        JsonObject out = new JsonObject();
        JsonObject trends = new JsonObject();
        Map<String, List<String>> sectorStocks = new LinkedHashMap<String, List<String>>();
        Set<String> allCodes = new LinkedHashSet<String>();
        try (Connection c = Db.conn();
             PreparedStatement ps = c.prepareStatement(
                "SELECT sm.sector_name, m.stock_code FROM sector_stock_map m "
              + "JOIN sector_master sm ON sm.id = m.sector_id WHERE sm.sector_type IN ('테마','업종')");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String name = rs.getString(1), code = rs.getString(2);
                if (name == null || code == null) continue;
                List<String> l = sectorStocks.get(name);
                if (l == null) { l = new ArrayList<String>(); sectorStocks.put(name, l); }
                l.add(code);
                allCodes.add(code);
            }
        } catch (Exception e) {
            System.err.println("[SectorTrend] db error: " + e.getMessage());
            out.add("trends", trends);
            return out;
        }

        // 시가총액 가중: 섹터 등락률 = (Σ당일시총 / Σ전일시총 − 1) × 100.
        //   전일시총 = 당일시총 / (1 + 등락률/100)  (상장주식수 불변 → 시총비 = 주가비)
        Map<String, double[]> caps = fetchCaps(allCodes); // code -> {당일시총, 등락률%}
        for (Map.Entry<String, List<String>> e : sectorStocks.entrySet()) {
            double sumToday = 0, sumYest = 0;
            for (String code : e.getValue()) {
                double[] v = caps.get(code);
                if (v == null) continue;
                double todayCap = v[0], ratio = v[1], denom = 1 + ratio / 100.0;
                if (todayCap <= 0 || denom <= 0) continue;
                sumToday += todayCap;
                sumYest += todayCap / denom;
            }
            JsonObject o = new JsonObject();
            if (sumYest <= 0) { o.addProperty("d", "FLAT"); o.addProperty("p", 0); }
            else {
                double ret = (sumToday / sumYest - 1) * 100.0;
                o.addProperty("d", ret > 0.05 ? "UP" : ret < -0.05 ? "DOWN" : "FLAT");
                o.addProperty("p", Math.round(ret * 100.0) / 100.0);
            }
            trends.add(e.getKey(), o);
        }
        out.add("trends", trends);
        return out;
    }

    /** 네이버 일괄 시세로 종목별 {당일 시가총액, 등락률%} 조회 (등락률은 부호 포함). */
    private Map<String, double[]> fetchCaps(Set<String> codes) {
        Map<String, double[]> map = new HashMap<String, double[]>();
        List<String> list = new ArrayList<String>(codes);
        final int BATCH = 100;
        for (int i = 0; i < list.size(); i += BATCH) {
            List<String> batch = list.subList(i, Math.min(i + BATCH, list.size()));
            StringBuilder sb = new StringBuilder();
            for (String c : batch) { if (sb.length() > 0) sb.append(','); sb.append(c); }
            HttpURLConnection conn = null;
            try {
                URL url = new URL("https://polling.finance.naver.com/api/realtime/domestic/stock/" + sb.toString());
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(5000);
                if (conn.getResponseCode() != 200) continue;
                String body;
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder r = new StringBuilder(); String line;
                    while ((line = br.readLine()) != null) r.append(line);
                    body = r.toString();
                }
                JsonObject resp = JsonParser.parseString(body).getAsJsonObject();
                if (!resp.has("datas") || !resp.get("datas").isJsonArray()) continue;
                JsonArray datas = resp.getAsJsonArray("datas");
                for (int j = 0; j < datas.size(); j++) {
                    JsonObject d = datas.get(j).getAsJsonObject();
                    if (!d.has("itemCode")) continue;
                    String code = d.get("itemCode").getAsString();
                    double cap = num(d, "marketValueFullRaw");
                    double ratio = num(d, "fluctuationsRatio");
                    if (cap > 0) map.put(code, new double[]{ cap, ratio });
                }
            } catch (Exception ignore) {
                /* 배치 1건 실패는 무시 */
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return map;
    }

    private static double num(JsonObject o, String key) {
        try {
            if (!o.has(key) || o.get(key).isJsonNull()) return 0;
            return Double.parseDouble(o.get(key).getAsString().replace(",", "").trim());
        } catch (Exception e) { return 0; }
    }

    private static void write(HttpServletResponse resp, JsonObject o) throws IOException {
        try (PrintWriter w = resp.getWriter()) { w.print(o.toString()); }
    }
}
