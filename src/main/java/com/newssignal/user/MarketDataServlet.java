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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 시장 스냅샷 대시보드 데이터.
 *   /api/market?view=movers&metric=value|volume|gainers|losers  → 오늘의 종목 랭킹 TOP
 *   /api/market?view=heatmap&market=kospi|kosdaq                → 시총 비례 히트맵(트리맵)용 상위 종목
 * 네이버 일괄 시세를 60초 캐시로 1회 수집해 두 뷰가 공유한다(중복 호출/한도 보호).
 */
@WebServlet("/api/market")
public class MarketDataServlet extends HttpServlet {

    static final class Row {
        String code, name, market;
        long price, change, tradingValue, volume, cap;
        double chgPct;
        String dir; // UP | DOWN | FLAT
    }

    private static volatile List<Row> snapshot;
    private static volatile long snapTs;
    private static final long TTL_MS = 60_000L;
    private static final Object LOCK = new Object();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        String view = req.getParameter("view");
        List<Row> rows = getSnapshot();
        JsonObject out = new JsonObject();
        if ("heatmap".equals(view)) {
            out = buildHeatmap(rows, req.getParameter("market"));
        } else { // movers (기본)
            out = buildMovers(rows, req.getParameter("metric"));
        }
        try (PrintWriter w = resp.getWriter()) { w.print(out.toString()); }
    }

    /* ── 랭킹 ── */
    private JsonObject buildMovers(List<Row> rows, String metric) {
        if (metric == null) metric = "value";
        List<Row> list = new ArrayList<Row>(rows);
        final int LIMIT = 30;
        Comparator<Row> cmp;
        if ("volume".equals(metric)) {
            cmp = new Comparator<Row>() { public int compare(Row a, Row b) { return Long.compare(b.volume, a.volume); } };
        } else if ("gainers".equals(metric)) {
            List<Row> up = new ArrayList<Row>();
            for (Row r : list) if (r.chgPct > 0) up.add(r);
            list = up;
            cmp = new Comparator<Row>() { public int compare(Row a, Row b) { return Double.compare(b.chgPct, a.chgPct); } };
        } else if ("losers".equals(metric)) {
            List<Row> dn = new ArrayList<Row>();
            for (Row r : list) if (r.chgPct < 0) dn.add(r);
            list = dn;
            cmp = new Comparator<Row>() { public int compare(Row a, Row b) { return Double.compare(a.chgPct, b.chgPct); } };
        } else { // value (거래대금)
            metric = "value";
            cmp = new Comparator<Row>() { public int compare(Row a, Row b) { return Long.compare(b.tradingValue, a.tradingValue); } };
        }
        Collections.sort(list, cmp);
        JsonArray arr = new JsonArray();
        for (int i = 0; i < list.size() && i < LIMIT; i++) {
            Row r = list.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("code", r.code);
            o.addProperty("name", r.name);
            o.addProperty("price", r.price);
            o.addProperty("chgPct", r.chgPct);
            o.addProperty("dir", r.dir);
            o.addProperty("tradingValue", r.tradingValue);
            o.addProperty("volume", r.volume);
            arr.add(o);
        }
        JsonObject out = new JsonObject();
        out.addProperty("metric", metric);
        out.add("results", arr);
        return out;
    }

    /* ── 히트맵 ── */
    private JsonObject buildHeatmap(List<Row> rows, String market) {
        String mk = (market == null) ? "kospi" : market.toLowerCase();
        String want = "kosdaq".equals(mk) ? "KOSDAQ" : "KOSPI";
        int TOP = 50;
        List<Row> list = new ArrayList<Row>();
        for (Row r : rows) if (want.equals(r.market) && r.cap > 0) list.add(r);
        Collections.sort(list, new Comparator<Row>() {
            public int compare(Row a, Row b) { return Long.compare(b.cap, a.cap); }
        });
        JsonArray arr = new JsonArray();
        for (int i = 0; i < list.size() && i < TOP; i++) {
            Row r = list.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("code", r.code);
            o.addProperty("name", r.name);
            o.addProperty("cap", r.cap);
            o.addProperty("chgPct", r.chgPct);
            o.addProperty("dir", r.dir);
            arr.add(o);
        }
        JsonObject out = new JsonObject();
        out.addProperty("market", "kosdaq".equals(mk) ? "kosdaq" : "kospi");
        out.add("items", arr);
        return out;
    }

    /* ── 스냅샷(60초 캐시) ── */
    private List<Row> getSnapshot() {
        long now = System.currentTimeMillis();
        List<Row> s = snapshot;
        if (s != null && now - snapTs < TTL_MS) return s;
        synchronized (LOCK) {
            if (snapshot != null && System.currentTimeMillis() - snapTs < TTL_MS) return snapshot;
            List<Row> computed = fetchAll();
            if (!computed.isEmpty()) { snapshot = computed; snapTs = System.currentTimeMillis(); }
            return snapshot != null ? snapshot : new ArrayList<Row>();
        }
    }

    /** KOSPI+KOSDAQ 종목의 네이버 일괄 시세를 수집한다. */
    private List<Row> fetchAll() {
        List<String[]> universe = new ArrayList<String[]>(); // {code, market}
        try (Connection c = Db.conn();
             PreparedStatement ps = c.prepareStatement(
                "SELECT stock_code, market FROM stock_master WHERE market IN ('KOSPI','KOSDAQ')");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) universe.add(new String[]{ rs.getString(1), rs.getString(2) });
        } catch (Exception e) {
            System.err.println("[MarketData] db error: " + e.getMessage());
            return new ArrayList<Row>();
        }
        java.util.Map<String, String> marketOf = new java.util.HashMap<String, String>();
        List<String> codes = new ArrayList<String>();
        for (String[] u : universe) { marketOf.put(u[0], u[1]); codes.add(u[0]); }

        List<Row> rows = new ArrayList<Row>();
        final int BATCH = 100;
        for (int i = 0; i < codes.size(); i += BATCH) {
            List<String> batch = codes.subList(i, Math.min(i + BATCH, codes.size()));
            StringBuilder sb = new StringBuilder();
            for (String c : batch) { if (sb.length() > 0) sb.append(','); sb.append(c); }
            HttpURLConnection conn = null;
            try {
                URL url = new URL("https://polling.finance.naver.com/api/realtime/domestic/stock/" + sb.toString());
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(6000);
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
                    Row r = new Row();
                    r.code = str(d, "itemCode");
                    r.name = str(d, "stockName");
                    r.market = marketOf.get(r.code);
                    r.price = lng(d, "closePriceRaw");
                    r.change = lng(d, "compareToPreviousClosePriceRaw");
                    r.chgPct = num(d, "fluctuationsRatioRaw");
                    r.tradingValue = lng(d, "accumulatedTradingValueRaw");
                    r.volume = lng(d, "accumulatedTradingVolumeRaw");
                    r.cap = lng(d, "marketValueFullRaw");
                    String dc = "";
                    if (d.has("compareToPreviousPrice") && d.get("compareToPreviousPrice").isJsonObject()) {
                        JsonObject cp = d.getAsJsonObject("compareToPreviousPrice");
                        dc = cp.has("code") ? cp.get("code").getAsString() : "";
                    }
                    r.dir = ("1".equals(dc) || "2".equals(dc)) ? "UP" : ("4".equals(dc) || "5".equals(dc)) ? "DOWN" : "FLAT";
                    if (r.code != null && r.name != null) rows.add(r);
                }
            } catch (Exception ignore) {
                /* 배치 1건 실패 무시 */
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return rows;
    }

    private static String str(JsonObject o, String k) {
        try { return (o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null; }
        catch (Exception e) { return null; }
    }
    private static long lng(JsonObject o, String k) {
        try {
            if (!o.has(k) || o.get(k).isJsonNull()) return 0;
            String s = o.get(k).getAsString().replace(",", "").trim();
            if (s.isEmpty()) return 0;
            return (long) Double.parseDouble(s);
        } catch (Exception e) { return 0; }
    }
    private static double num(JsonObject o, String k) {
        try {
            if (!o.has(k) || o.get(k).isJsonNull()) return 0;
            return Double.parseDouble(o.get(k).getAsString().replace(",", "").trim());
        } catch (Exception e) { return 0; }
    }
}
