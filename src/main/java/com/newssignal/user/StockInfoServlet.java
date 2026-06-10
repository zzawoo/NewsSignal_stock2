package com.newssignal.user;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.newssignal.common.DartApiClient;
import com.newssignal.common.KisApiClient;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

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
            // 1. KIS 일별 차트 데이터
            try {
                JsonObject kisChart = KisApiClient.getDailyChartPrice(code);
                if (kisChart != null) result.add("chart", kisChart);
            } catch (Exception e) {
                System.err.println("[StockInfoServlet] KIS getDailyChartPrice error for " + code + ": " + e.getMessage());
                result.addProperty("chart_error", e.getMessage());
            }

            // 2. DART 기업 개황
            try {
                JsonObject dartCompany = DartApiClient.getCompanyInfo(code);
                if (dartCompany != null) result.add("company", dartCompany);
            } catch (Exception e) {
                System.err.println("[StockInfoServlet] DART getCompanyInfo error for " + code + ": " + e.getMessage());
                result.addProperty("company_error", e.getMessage());
            }
        }

        try (PrintWriter out = resp.getWriter()) {
            out.print(new Gson().toJson(result));
        }
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
                    
                    // 빈 값으로 거래량, 거래대금, 시가총액 채움
                    fakeKis.addProperty("acml_vol", "0");
                    fakeKis.addProperty("acml_tr_pbmn", "0");
                    fakeKis.addProperty("hts_avls", "0");
                    
                    return fakeKis;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
