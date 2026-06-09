package com.newssignal.user;

import com.google.gson.Gson;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 대시보드 데이터 API (계획서 8장).
 * 사용자 화면(JSP)이 호출하는 조회 전용 엔드포인트.
 * 모든 쿼리는 PreparedStatement 사용 (계획서 9장 SQLi 방지).
 */
@WebServlet("/api/dashboard")
public class DashboardServlet extends HttpServlet {

    private static final Gson GSON = new Gson();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> result = new HashMap<>();
        try (Connection c = Db.conn()) {
            result.put("summary", summary(c));
            result.put("topIssues", topIssues(c));
            result.put("goodNews", newsByType(c, "GOOD", true));
            result.put("badNews", newsByType(c, "BAD", false));
        } catch (Exception e) {
            // 상세 스택트레이스 비노출 (계획서 9장)
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "data error");
            return;
        }
        resp.setContentType("application/json; charset=UTF-8");
        try (PrintWriter out = resp.getWriter()) {
            out.print(GSON.toJson(result));
        }
    }

    private Map<String, Object> summary(Connection c) throws Exception {
        Map<String, Object> m = new HashMap<>();
        String sql = "SELECT good_bad_type, COUNT(*) cnt FROM news_similarity_group GROUP BY good_bad_type";
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) m.put(rs.getString("good_bad_type"), rs.getInt("cnt"));
        }
        return m;
    }

    private List<Map<String, Object>> topIssues(Connection c) throws Exception {
        String sql = "SELECT group_title, good_bad_type, impact_score, related_sectors, duplicate_count "
                   + "FROM news_similarity_group "
                   + "ORDER BY duplicate_count DESC, ABS(impact_score) DESC, last_collected_at DESC "
                   + "LIMIT 10";
        return rows(c, sql, null, false);
    }

    private List<Map<String, Object>> newsByType(Connection c, String type, boolean desc) throws Exception {
        String sql = "SELECT group_title, good_bad_type, impact_score, related_sectors, duplicate_count "
                   + "FROM news_similarity_group WHERE good_bad_type = ? "
                   + "ORDER BY impact_score " + (desc ? "DESC" : "ASC") + ", duplicate_count DESC LIMIT 5";
        return rows(c, sql, type, false);
    }

    private List<Map<String, Object>> rows(Connection c, String sql, String param, boolean unused)
            throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (param != null) ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> r = new HashMap<>();
                    r.put("title", rs.getString("group_title"));
                    r.put("type", rs.getString("good_bad_type"));
                    r.put("score", rs.getInt("impact_score"));
                    r.put("sectors", rs.getString("related_sectors"));
                    r.put("dup", rs.getInt("duplicate_count"));
                    list.add(r);
                }
            }
        }
        return list;
    }
}
