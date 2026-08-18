package com.newssignal.user;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.newssignal.common.DartApiClient;
import com.newssignal.common.Db;
import com.newssignal.common.KisApiClient;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

@WebServlet("/api/stock/info")
public class StockInfoServlet extends HttpServlet {

    // 목표가·투자의견·배당(네이버 통합정보)은 일 단위로 바뀌므로 30분 캐시(네이버 호출 절감).
    private static final java.util.Map<String, Object[]> CONSENSUS_CACHE = new java.util.concurrent.ConcurrentHashMap<String, Object[]>();
    private static final long CONSENSUS_TTL_MS = 30 * 60 * 1000L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");

        String code = req.getParameter("code");
        String type = req.getParameter("type"); // list | details | search
        if (type == null) type = "list";

        if ("search".equals(type)) { handleSearch(req, resp); return; }

        JsonObject result = new JsonObject();

        // 종목코드 없으면 빈 JSON 반환 (HTML error 대신)
        if (code == null || code.trim().isEmpty()) {
            result.addProperty("error", "Missing stock code");
            try (PrintWriter out = resp.getWriter()) {
                out.print(new Gson().toJson(result));
            }
            return;
        }

        if ("list".equals(type)) {
            // 1. KIS 현재가
            try {
                JsonObject kisData = KisApiClient.getStockPrice(code);
                if (kisData != null) {
                    result.add("price", kisData);
                } else {
                    JsonObject naverData = getNaverStockBasic(code);
                    if (naverData != null) result.add("price", naverData);
                }
            } catch (Exception e) {
                System.err.println("[StockInfoServlet] KIS getStockPrice error for " + code + ": " + e.getMessage());
                try {
                    JsonObject naverData = getNaverStockBasic(code);
                    if (naverData != null) {
                        result.add("price", naverData);
                    } else {
                        result.addProperty("price_error", e.getMessage());
                    }
                } catch (Exception ex) {
                    result.addProperty("price_error", e.getMessage());
                }
            }

            // 2. DART 재무 정보 (종목코드→corp_code 변환 후 조회)
            try {
                JsonObject dartFinance = DartApiClient.getFinanceInfo(code);
                if (dartFinance != null) result.add("finance", dartFinance);
            } catch (Exception e) {
                System.err.println("[StockInfoServlet] DART getFinanceInfo error for " + code + ": " + e.getMessage());
                result.addProperty("finance_error", e.getMessage());
            }

        } else if ("details".equals(type)) {
            // 차트 시간대: D/W/M(일/주/월) 또는 1/5/10(분). 기본 D.
            String tf = req.getParameter("tf");
            if (tf == null || tf.trim().isEmpty()) tf = "D";

            // 1. KIS 차트 (정규화 candles) — 60초 캐시
            try {
                JsonObject kisChart = KisApiClient.getChart(code, tf);
                if (kisChart != null) result.add("chart", kisChart);
            } catch (Exception e) {
                System.err.println("[StockInfoServlet] KIS getChart error for " + code + ": " + e.getMessage());
                result.addProperty("chart_error", e.getMessage());
            }

            // 2. KIS 현재가/지표 (시총·PER·PBR·EPS·52주최고·외국인 등) — 5초 캐시
            try {
                JsonObject kisPrice = KisApiClient.getStockPrice(code);
                if (kisPrice != null) result.add("price", kisPrice);
            } catch (Exception e) {
                System.err.println("[StockInfoServlet] KIS getStockPrice(details) error for " + code + ": " + e.getMessage());
            }

            // 3. DART 기업 개황 — 24시간 캐시
            try {
                JsonObject dartCompany = DartApiClient.getCompanyInfo(code);
                if (dartCompany != null) result.add("company", dartCompany);
            } catch (Exception e) {
                System.err.println("[StockInfoServlet] DART getCompanyInfo error for " + code + ": " + e.getMessage());
                result.addProperty("company_error", e.getMessage());
            }

            // 4. DART 재무 (매출·영업이익 등) — 24시간 캐시
            try {
                JsonObject dartFinance = DartApiClient.getFinanceInfo(code);
                if (dartFinance != null) result.add("finance", dartFinance);
            } catch (Exception e) {
                System.err.println("[StockInfoServlet] DART getFinanceInfo(details) error for " + code + ": " + e.getMessage());
            }

            // 5. 소속 섹터(테마/업종) + 동일 업종 비교 종목
            try {
                addSectorsAndPeers(code, result);
            } catch (Exception e) {
                System.err.println("[StockInfoServlet] addSectorsAndPeers error for " + code + ": " + e.getMessage());
            }

            // 6. 관련 뉴스 (그룹 대표기사 + 호재/악재·영향도)
            try {
                addStockNews(code, result);
            } catch (Exception e) {
                System.err.println("[StockInfoServlet] addStockNews error for " + code + ": " + e.getMessage());
            }

            // 7. 호재/악재 타임라인 (날짜별 영향도)
            try {
                addStockTimeline(code, result);
            } catch (Exception e) {
                System.err.println("[StockInfoServlet] addStockTimeline error for " + code + ": " + e.getMessage());
            }

            // 8. 목표주가(컨센서스)·투자의견·배당 (네이버, 30분 캐시)
            try {
                addConsensus(code, result);
            } catch (Exception e) {
                System.err.println("[StockInfoServlet] addConsensus error for " + code + ": " + e.getMessage());
            }
        }

        try (PrintWriter out = resp.getWriter()) {
            out.print(new Gson().toJson(result));
        }
    }

    /** 네이버 통합정보에서 목표주가(컨센서스)·투자의견·배당수익률·주당배당금·추정PER/EPS를 가져온다(30분 캐시). */
    private void addConsensus(String code, JsonObject result) {
        long now = System.currentTimeMillis();
        Object[] cached = CONSENSUS_CACHE.get(code);
        if (cached != null && now - (Long) cached[1] < CONSENSUS_TTL_MS) {
            result.add("consensus", (JsonObject) cached[0]);
            return;
        }
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL("https://m.stock.naver.com/api/stock/" + code + "/integration");
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            if (conn.getResponseCode() != 200) return;
            JsonObject root;
            try (java.io.InputStreamReader isr = new java.io.InputStreamReader(conn.getInputStream(), "UTF-8")) {
                root = new Gson().fromJson(isr, JsonObject.class);
            }
            JsonObject c = new JsonObject();
            if (root.has("consensusInfo") && root.get("consensusInfo").isJsonObject()) {
                JsonObject ci = root.getAsJsonObject("consensusInfo");
                if (ci.has("priceTargetMean") && !ci.get("priceTargetMean").isJsonNull()) c.addProperty("targetPrice", ci.get("priceTargetMean").getAsString());
                if (ci.has("recommMean") && !ci.get("recommMean").isJsonNull()) c.addProperty("recommMean", ci.get("recommMean").getAsString());
            }
            if (root.has("totalInfos") && root.get("totalInfos").isJsonArray()) {
                for (com.google.gson.JsonElement el : root.getAsJsonArray("totalInfos")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject t = el.getAsJsonObject();
                    String key = t.has("code") ? t.get("code").getAsString() : "";
                    String val = t.has("value") && !t.get("value").isJsonNull() ? t.get("value").getAsString() : "";
                    if ("dividendYieldRatio".equals(key)) c.addProperty("dividendYield", val);
                    else if ("dividend".equals(key)) c.addProperty("dps", val);
                    else if ("cnsPer".equals(key)) c.addProperty("cnsPer", val);
                    else if ("cnsEps".equals(key)) c.addProperty("cnsEps", val);
                }
            }
            CONSENSUS_CACHE.put(code, new Object[]{ c, now });
            result.add("consensus", c);
        } catch (Exception e) {
            System.err.println("[StockInfoServlet] consensus query error " + code + ": " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 종목 관련 뉴스: 종목에 매핑된 분석 그룹의 대표 기사 목록(호재/악재·영향도 포함). */
    private void addStockNews(String code, JsonObject result) {
        JsonArray arr = new JsonArray();
        // news_stock_map은 LLM이 느슨하게 지목한 매핑(거시·지수 뉴스에 대형주 등)이 다수라
        // 종목명이 실제 뉴스 텍스트(기사 제목/그룹 제목/요약)에 등장하는 그룹만 노출해 오탐을 제거한다.
        String sql =
            "SELECT na.id, na.title, na.press, na.pub_date, na.naver_link, na.original_link, "
          + "       g.good_bad_type, g.impact_score "
          + "FROM news_stock_map m "
          + "JOIN stock_master sm ON sm.stock_code = m.stock_code "
          + "JOIN news_similarity_group g ON g.id = m.group_id "
          + "JOIN news_articles na ON na.id = g.representative_news_id "
          + "WHERE m.stock_code = ? AND g.analyzed_yn='Y' "
          + "  AND (na.title LIKE CONCAT('%', sm.stock_name, '%') "
          + "    OR g.group_title LIKE CONCAT('%', sm.stock_name, '%') "
          + "    OR g.group_summary LIKE CONCAT('%', sm.stock_name, '%')) "
          + "ORDER BY na.pub_date DESC LIMIT 20";
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
        try (Connection c = Db.conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", rs.getLong("id"));
                    o.addProperty("title", rs.getString("title"));
                    o.addProperty("press", rs.getString("press"));
                    java.sql.Timestamp ts = rs.getTimestamp("pub_date");
                    o.addProperty("pub_date", ts != null ? fmt.format(ts) : "");
                    o.addProperty("naver_link", rs.getString("naver_link"));
                    o.addProperty("original_link", rs.getString("original_link"));
                    o.addProperty("type", rs.getString("good_bad_type"));
                    o.addProperty("score", rs.getInt("impact_score"));
                    arr.add(o);
                }
            }
        } catch (Exception e) {
            System.err.println("[StockInfoServlet] addStockNews query error: " + e.getMessage());
        }
        result.add("news", arr);
    }

    /** 종목 호재/악재 타임라인: 종목명이 실제 등장하는 분석 뉴스의 날짜·영향도(시간순). */
    private void addStockTimeline(String code, JsonObject result) {
        JsonArray arr = new JsonArray();
        String sql =
            "SELECT DATE_FORMAT(na.pub_date,'%Y-%m-%d') d, g.impact_score sc, g.good_bad_type gb, LEFT(g.group_title,60) t "
          + "FROM news_stock_map m "
          + "JOIN stock_master sm ON sm.stock_code = m.stock_code "
          + "JOIN news_similarity_group g ON g.id = m.group_id "
          + "JOIN news_articles na ON na.id = g.representative_news_id "
          + "WHERE m.stock_code = ? AND g.analyzed_yn='Y' AND na.pub_date IS NOT NULL "
          + "  AND (na.title LIKE CONCAT('%', sm.stock_name, '%') "
          + "    OR g.group_title LIKE CONCAT('%', sm.stock_name, '%') "
          + "    OR g.group_summary LIKE CONCAT('%', sm.stock_name, '%')) "
          + "ORDER BY na.pub_date ASC LIMIT 120";
        try (Connection c = Db.conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("date", rs.getString("d"));
                    o.addProperty("score", rs.getInt("sc"));
                    o.addProperty("type", rs.getString("gb"));
                    o.addProperty("title", rs.getString("t"));
                    arr.add(o);
                }
            }
        } catch (Exception e) {
            System.err.println("[StockInfoServlet] timeline query error: " + e.getMessage());
        }
        result.add("timeline", arr);
    }

    /** 종목명/코드 자동완성 검색 (공개). /api/stock/info?type=search&q=... → {results:[{code,name}]} */
    private void handleSearch(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String q = req.getParameter("q");
        q = q == null ? "" : q.trim();
        JsonObject out = new JsonObject();
        JsonArray arr = new JsonArray();
        if (!q.isEmpty()) {
            try (Connection c = Db.conn();
                 PreparedStatement ps = c.prepareStatement(
                    "SELECT stock_code, stock_name FROM stock_master "
                  + "WHERE stock_name LIKE ? OR stock_code LIKE ? "
                  + "ORDER BY CASE WHEN stock_name=? THEN 0 WHEN stock_name LIKE ? THEN 1 ELSE 2 END, "
                  + "CASE market WHEN 'KOSPI' THEN 0 WHEN 'KOSDAQ' THEN 1 WHEN 'KONEX' THEN 2 ELSE 3 END, "
                  + "CHAR_LENGTH(stock_name), stock_name LIMIT 20")) {
                ps.setString(1, "%" + q + "%"); ps.setString(2, q + "%"); ps.setString(3, q); ps.setString(4, q + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject o = new JsonObject();
                        o.addProperty("code", rs.getString(1));
                        o.addProperty("name", rs.getString(2));
                        arr.add(o);
                    }
                }
            } catch (Exception e) {
                System.err.println("[StockInfoServlet] search error: " + e.getMessage());
            }
        }
        out.add("results", arr);
        try (PrintWriter w = resp.getWriter()) { w.print(new Gson().toJson(out)); }
    }

    /**
     * 종목이 속한 섹터(테마/업종)와, 같은 업종(industry)에서 뉴스 노출이 많은 비교 종목 5개를 채운다.
     * 비교 종목 시세는 KIS getStockPrice(5초 캐시) 재사용.
     */
    private void addSectorsAndPeers(String code, JsonObject result) {
        List<String[]> peerCodes = new ArrayList<String[]>();
        try (Connection c = Db.conn()) {
            // 소속 섹터
            JsonArray sectors = new JsonArray();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT s.sector_name, s.sector_type FROM sector_master s "
                  + "JOIN sector_stock_map sm ON s.id = sm.sector_id "
                  + "WHERE sm.stock_code = ? AND s.sector_type IN ('업종','테마') "
                  + "ORDER BY (s.sector_type='업종') DESC, s.sector_name")) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject o = new JsonObject();
                        o.addProperty("name", rs.getString(1));
                        o.addProperty("type", rs.getString(2));
                        sectors.add(o);
                    }
                }
            }
            result.add("sectors", sectors);

            // 동일 업종 비교 대상: 같은 industry 중 뉴스 노출(news_stock_map) 많은 순 5개
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT st.stock_code, st.stock_name FROM stock_master st "
                  + "LEFT JOIN news_stock_map nm ON nm.stock_code = st.stock_code "
                  + "WHERE st.industry = (SELECT industry FROM stock_master WHERE stock_code = ?) "
                  + "AND st.industry IS NOT NULL AND st.industry <> '' AND st.stock_code <> ? "
                  + "GROUP BY st.stock_code, st.stock_name "
                  + "ORDER BY COUNT(nm.group_id) DESC, st.stock_code LIMIT 5")) {
                ps.setString(1, code);
                ps.setString(2, code);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) peerCodes.add(new String[]{rs.getString(1), rs.getString(2)});
                }
            }
        } catch (Exception e) {
            System.err.println("[StockInfoServlet] sectors/peers query error: " + e.getMessage());
        }

        // 비교 종목 현재가 (KIS, best-effort)
        JsonArray peers = new JsonArray();
        for (String[] pc : peerCodes) {
            JsonObject o = new JsonObject();
            o.addProperty("code", pc[0]);
            o.addProperty("name", pc[1]);
            try {
                JsonObject pr = KisApiClient.getStockPrice(pc[0]);
                if (pr != null) {
                    if (pr.has("stck_prpr")) o.addProperty("price", pr.get("stck_prpr").getAsString());
                    if (pr.has("prdy_ctrt")) o.addProperty("ctrt", pr.get("prdy_ctrt").getAsString());
                    if (pr.has("prdy_vrss_sign")) o.addProperty("sign", pr.get("prdy_vrss_sign").getAsString());
                }
            } catch (Exception ignore) { /* 시세 실패해도 이름은 노출 */ }
            peers.add(o);
        }
        result.add("peers", peers);
    }

    private JsonObject getNaverStockBasic(String code) {
        try {
            java.net.URL url = new java.net.URL("https://m.stock.naver.com/api/stock/" + code + "/basic");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            if (conn.getResponseCode() == 200) {
                try (java.io.InputStreamReader isr = new java.io.InputStreamReader(conn.getInputStream(), "UTF-8")) {
                    JsonObject naverResp = new Gson().fromJson(isr, JsonObject.class);
                    JsonObject fakeKis = new JsonObject();
                    
                    if (naverResp.has("closePrice") && !naverResp.get("closePrice").isJsonNull()) {
                        String closePrice = naverResp.get("closePrice").getAsString().replace(",", "");
                        fakeKis.addProperty("stck_prpr", closePrice);
                    } else {
                        return null; // 가격 정보가 없으면 실패
                    }
                    
                    String signCode = "3";
                    if (naverResp.has("compareToPreviousPrice") && !naverResp.get("compareToPreviousPrice").isJsonNull()) {
                        JsonObject comp = naverResp.getAsJsonObject("compareToPreviousPrice");
                        if (comp.has("code") && !comp.get("code").isJsonNull()) {
                            signCode = comp.get("code").getAsString();
                        }
                    }
                    fakeKis.addProperty("prdy_vrss_sign", signCode);
                    
                    if (naverResp.has("compareToPreviousClosePrice") && !naverResp.get("compareToPreviousClosePrice").isJsonNull()) {
                        String diff = naverResp.get("compareToPreviousClosePrice").getAsString().replace(",", "").replace("-", "");
                        fakeKis.addProperty("prdy_vrss", diff);
                    } else {
                        fakeKis.addProperty("prdy_vrss", "0");
                    }
                    
                    if (naverResp.has("fluctuationsRatio") && !naverResp.get("fluctuationsRatio").isJsonNull()) {
                        String ratio = naverResp.get("fluctuationsRatio").getAsString();
                        fakeKis.addProperty("prdy_ctrt", ratio);
                    } else {
                        fakeKis.addProperty("prdy_ctrt", "0.00");
                    }
                    
                    // 거래량/거래대금/시가총액은 네이버 basic에 없음 →
                    // "0"으로 덮지 말고 생략한다(프론트가 직전 값 유지, 0 깜빡임 방지).

                    return fakeKis;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
