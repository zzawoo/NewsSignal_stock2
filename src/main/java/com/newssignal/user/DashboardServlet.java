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
            result.put("moreIssues", moreIssues(c));
            result.put("goodNews", newsByType(c, "GOOD", true));
            result.put("badNews", newsByType(c, "BAD", false));
            result.put("sectors", sectors(c));
        } catch (Exception e) {
            // 상세 스택트레이스 비노출 (계획서 9장) - 서버 콘솔에는 에러 로그 출력
            e.printStackTrace();
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

    /**
     * 호재 2 + 악재 2 + 중립·혼합 2 = 총 6개, 각 타입별 |impact_score| 강한 순.
     */
    private List<Map<String, Object>> topIssues(Connection c) throws Exception {
        String sql =
            "(SELECT id, group_title, group_summary, good_bad_type, impact_score, related_sectors, duplicate_count "
          + " FROM news_similarity_group WHERE good_bad_type = 'GOOD' "
          + " ORDER BY ABS(impact_score) DESC, duplicate_count DESC LIMIT 2) "
          + "UNION ALL "
          + "(SELECT id, group_title, group_summary, good_bad_type, impact_score, related_sectors, duplicate_count "
          + " FROM news_similarity_group WHERE good_bad_type = 'BAD' "
          + " ORDER BY ABS(impact_score) DESC, duplicate_count DESC LIMIT 2) "
          + "UNION ALL "
          + "(SELECT id, group_title, group_summary, good_bad_type, impact_score, related_sectors, duplicate_count "
          + " FROM news_similarity_group WHERE good_bad_type IN ('NEUTRAL','MIXED') "
          + " ORDER BY ABS(impact_score) DESC, duplicate_count DESC LIMIT 2) "
          + "ORDER BY ABS(impact_score) DESC";
        return rows(c, sql, null, false);
    }

    /**
     * 위 top 6에 포함되지 않은 나머지 전체 이슈, |impact_score| 강한 순.
     */
    private List<Map<String, Object>> moreIssues(Connection c) throws Exception {
        // top 6 id 집합을 서브쿼리로 제외
        String sql =
            "SELECT id, group_title, group_summary, good_bad_type, impact_score, related_sectors, duplicate_count "
          + "FROM news_similarity_group "
          + "WHERE id NOT IN ("
          +   "SELECT id FROM ("
          +     "(SELECT id FROM news_similarity_group WHERE good_bad_type = 'GOOD' "
          +      " ORDER BY ABS(impact_score) DESC, duplicate_count DESC LIMIT 2) "
          +     "UNION ALL "
          +     "(SELECT id FROM news_similarity_group WHERE good_bad_type = 'BAD' "
          +      " ORDER BY ABS(impact_score) DESC, duplicate_count DESC LIMIT 2) "
          +     "UNION ALL "
          +     "(SELECT id FROM news_similarity_group WHERE good_bad_type IN ('NEUTRAL','MIXED') "
          +      " ORDER BY ABS(impact_score) DESC, duplicate_count DESC LIMIT 2)"
          +   ") AS top6"
          + ") "
          + "ORDER BY ABS(impact_score) DESC, duplicate_count DESC";
        return rows(c, sql, null, false);
    }

    private List<Map<String, Object>> newsByType(Connection c, String type, boolean desc) throws Exception {
        String sql = "SELECT id, group_title, group_summary, good_bad_type, impact_score, related_sectors, duplicate_count "
                   + "FROM news_similarity_group WHERE good_bad_type = ? "
                   + "ORDER BY impact_score " + (desc ? "DESC" : "ASC") + ", duplicate_count DESC LIMIT 5";
        return rows(c, sql, type, false);
    }

    private List<Map<String, Object>> sectors(Connection c) throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT s.id, s.sector_name, s.sector_type, "
                   + "SUM(CASE WHEN g.good_bad_type = 'GOOD' THEN 1 ELSE 0 END) as good_cnt, "
                   + "SUM(CASE WHEN g.good_bad_type = 'BAD' THEN 1 ELSE 0 END) as bad_cnt, "
                   + "SUM(CASE WHEN g.good_bad_type IN ('NEUTRAL', 'MIXED') THEN 1 ELSE 0 END) as neu_cnt, "
                   + "AVG(g.impact_score) as avg_score, "
                   + "COUNT(g.id) as total_cnt "
                   + "FROM sector_master s "
                   + "JOIN news_sector_map m ON s.id = m.sector_id "
                   + "JOIN news_similarity_group g ON m.group_id = g.id "
                   + "WHERE g.analyzed_yn = 'Y' "
                   + "GROUP BY s.id, s.sector_name, s.sector_type "
                   + "ORDER BY COUNT(g.id) DESC, ABS(AVG(g.impact_score)) DESC";
        
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> r = new HashMap<>();
                long sectorId = rs.getLong("id");
                r.put("name", rs.getString("sector_name"));
                r.put("type", rs.getString("sector_type"));
                r.put("good", rs.getInt("good_cnt"));
                r.put("bad", rs.getInt("bad_cnt"));
                r.put("neu", rs.getInt("neu_cnt"));
                r.put("avg", rs.getDouble("avg_score"));
                r.put("total", rs.getInt("total_cnt"));
                r.put("stocks", fetchStocksForSector(c, sectorId));
                list.add(r);
            }
        }
        return list;
    }

    private List<Map<String, Object>> fetchStocksForSector(Connection c, long sectorId) throws Exception {
        List<Map<String, Object>> stocks = new ArrayList<>();
        String sql = "SELECT st.stock_name, st.stock_code, st.market "
                   + "FROM stock_master st "
                   + "JOIN sector_stock_map sm ON st.stock_code = sm.stock_code "
                   + "WHERE sm.sector_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, sectorId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> s = new HashMap<>();
                    s.put("name", rs.getString("stock_name"));
                    s.put("code", rs.getString("stock_code"));
                    s.put("market", rs.getString("market"));
                    stocks.add(s);
                }
            }
        }
        return stocks;
    }

    private List<Map<String, Object>> rows(Connection c, String sql, String param, boolean unused)
            throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (param != null) ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> r = new HashMap<>();
                    r.put("id", rs.getLong("id"));
                    r.put("title", rs.getString("group_title"));
                    r.put("summary", rs.getString("group_summary")); // 추가: 2~3줄 요약
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
