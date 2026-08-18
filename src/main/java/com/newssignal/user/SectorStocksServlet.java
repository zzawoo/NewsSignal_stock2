package com.newssignal.user;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.newssignal.common.Db;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * 섹터명으로 구성 종목을 반환한다(핫이슈 강세 섹터 칩 클릭 → 종목 패널 연결용).
 *   /api/sector/stocks?name=반도체 → { name, stocks:[{name,code,market}] }
 */
@WebServlet("/api/sector/stocks")
public class SectorStocksServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        String name = req.getParameter("name");
        JsonObject out = new JsonObject();
        JsonArray stocks = new JsonArray();
        out.addProperty("name", name == null ? "" : name);
        if (name != null && !name.trim().isEmpty()) {
            String sql = "SELECT st.stock_name, st.stock_code, st.market "
                       + "FROM stock_master st "
                       + "JOIN sector_stock_map sm ON st.stock_code = sm.stock_code "
                       + "JOIN sector_master se ON se.id = sm.sector_id "
                       + "WHERE se.sector_name = ? ORDER BY st.stock_name";
            try (Connection c = Db.conn(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, name.trim());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject s = new JsonObject();
                        s.addProperty("name", rs.getString("stock_name"));
                        s.addProperty("code", rs.getString("stock_code"));
                        s.addProperty("market", rs.getString("market"));
                        stocks.add(s);
                    }
                }
            } catch (Exception e) {
                System.err.println("[SectorStocks] error: " + e.getMessage());
            }
        }
        out.add("stocks", stocks);
        try (PrintWriter w = resp.getWriter()) { w.print(out.toString()); }
    }
}
