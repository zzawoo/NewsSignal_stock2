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

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");

        String code = req.getParameter("code");
        String type = req.getParameter("type"); // list or details
        if (type == null) type = "list";

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
        }

        try (PrintWriter out = resp.getWriter()) {
            out.print(new Gson().toJson(result));
        }
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
