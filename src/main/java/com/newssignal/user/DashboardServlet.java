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
        // 주요 이슈 기간 필터: all(기본, 당일+전일) | today(당일) | yesterday(전일)
        String period = req.getParameter("period");

        Map<String, Object> result = new HashMap<>();
        try (Connection c = Db.conn()) {
            result.put("summary", summary(c));
            result.put("topIssues", topIssues(c, period));
            result.put("moreIssues", moreIssues(c, period));
            result.put("goodNews", newsByType(c, "GOOD", true));
            result.put("badNews", newsByType(c, "BAD", false));
            result.put("sectors", sectors(c));
            result.put("macroSignals", macroSignals(c));
            result.put("stockSignals", stockSignals(c));
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

    private static final String ISSUE_ORDER = "ORDER BY ABS(impact_score) DESC, duplicate_count DESC, id DESC";
    private static final String ISSUE_COLS = "SELECT id, group_title, group_summary, good_bad_type, impact_score, related_sectors, duplicate_count FROM news_similarity_group ";
    private static final String TODAY_COND = "DATE(last_collected_at) = CURDATE()";
    private static final String YDAY_COND  = "DATE(last_collected_at) = DATE_SUB(CURDATE(), INTERVAL 1 DAY)";
    private static final int MORE_PER_DAY = 40; // 한 날짜당 헤드라인 외 표시 상한

    /**
     * 주요 이슈 기간 필터 SQL 조건. 고정 화이트리스트라 인젝션 안전.
     *  - today: 당일 뉴스, yesterday: 전일 뉴스
     *  - all(기본): 당일 + 전일(2일) 합산
     */
    private String issueDateCond(String period) {
        if ("today".equals(period))     return TODAY_COND;
        if ("yesterday".equals(period)) return YDAY_COND;
        return "DATE(last_collected_at) >= DATE_SUB(CURDATE(), INTERVAL 1 DAY)"; // 전체 = 당일+전일
    }

    /** 호재2+악재2+중립2 헤드라인 6개 id 집합 (LIMIT가 파생테이블 내부라 IN에서 허용됨). */
    private String top6IdsSubquery(String dc) {
        return "SELECT id FROM ("
            + "(SELECT id FROM news_similarity_group WHERE good_bad_type='GOOD' AND " + dc + " " + ISSUE_ORDER + " LIMIT 2) "
            + "UNION ALL (SELECT id FROM news_similarity_group WHERE good_bad_type='BAD' AND " + dc + " " + ISSUE_ORDER + " LIMIT 2) "
            + "UNION ALL (SELECT id FROM news_similarity_group WHERE good_bad_type IN ('NEUTRAL','MIXED') AND " + dc + " " + ISSUE_ORDER + " LIMIT 2)"
            + ") AS top6";
    }

    /** 헤드라인 6개: 각 타입별 |impact_score| 강한 순(기간 한정). */
    private List<Map<String, Object>> topIssues(Connection c, String period) throws Exception {
        String dc = issueDateCond(period);
        String sql =
            "(" + ISSUE_COLS + "WHERE good_bad_type='GOOD' AND " + dc + " " + ISSUE_ORDER + " LIMIT 2) "
          + "UNION ALL (" + ISSUE_COLS + "WHERE good_bad_type='BAD' AND " + dc + " " + ISSUE_ORDER + " LIMIT 2) "
          + "UNION ALL (" + ISSUE_COLS + "WHERE good_bad_type IN ('NEUTRAL','MIXED') AND " + dc + " " + ISSUE_ORDER + " LIMIT 2) "
          + ISSUE_ORDER;
        return rows(c, sql, null, false);
    }

    /**
     * 헤드라인 6개를 제외한 나머지 이슈.
     *  - 당일/전일: 해당 일자에서 최대 MORE_PER_DAY개.
     *  - 전체: 당일·전일 각각 MORE_PER_DAY개씩 합산(날짜 균형 — 한쪽 날짜가 상위를 독식하지 않게).
     */
    private List<Map<String, Object>> moreIssues(Connection c, String period) throws Exception {
        String dc = issueDateCond(period);
        String notTop = "id NOT IN (" + top6IdsSubquery(dc) + ")";
        boolean isAll = !"today".equals(period) && !"yesterday".equals(period);
        String sql;
        if (isAll) {
            sql = "(" + ISSUE_COLS + "WHERE " + TODAY_COND + " AND " + notTop + " " + ISSUE_ORDER + " LIMIT " + MORE_PER_DAY + ") "
                + "UNION ALL "
                + "(" + ISSUE_COLS + "WHERE " + YDAY_COND + " AND " + notTop + " " + ISSUE_ORDER + " LIMIT " + MORE_PER_DAY + ")";
        } else {
            sql = ISSUE_COLS + "WHERE " + dc + " AND " + notTop + " " + ISSUE_ORDER + " LIMIT " + MORE_PER_DAY;
        }
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
                   + "SUM(CASE WHEN g.good_bad_type = 'GOOD' THEN g.duplicate_count ELSE 0 END) as good_cnt, "
                   + "SUM(CASE WHEN g.good_bad_type = 'BAD' THEN g.duplicate_count ELSE 0 END) as bad_cnt, "
                   + "SUM(CASE WHEN g.good_bad_type IN ('NEUTRAL', 'MIXED') THEN g.duplicate_count ELSE 0 END) as neu_cnt, "
                   + "AVG(g.impact_score) as avg_score, "
                   + "SUM(g.duplicate_count) as total_cnt "
                   + "FROM sector_master s "
                   + "JOIN news_sector_map m ON s.id = m.sector_id "
                   + "JOIN news_similarity_group g ON m.group_id = g.id "
                   + "WHERE g.analyzed_yn = 'Y' AND s.sector_type NOT IN ('지수', '거시경제') "
                   + "AND s.sector_name NOT IN ('지수') "  // 화이트리스트 밖 레거시 쓰레기 섹터 방어
                   + "AND g.last_collected_at >= DATE_SUB(NOW(), INTERVAL 3 DAY) "
                   + "GROUP BY s.id, s.sector_name, s.sector_type "
                   + "ORDER BY SUM(g.duplicate_count) DESC, ABS(AVG(g.impact_score)) DESC";
        
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
        // 종목 수가 많아도 전체 다 반환 (프론트에서 표시 처리)
        String sql = "SELECT st.stock_name, st.stock_code, st.market "
                   + "FROM stock_master st "
                   + "JOIN sector_stock_map sm ON st.stock_code = sm.stock_code "
                   + "WHERE sm.sector_id = ? "
                   + "ORDER BY st.stock_name";
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
                    r.put("summary", rs.getString("group_summary")); // 2~3줄 요약 (UI에서 item-summary로 노출)
                    
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

    private List<Map<String, Object>> macroSignals(Connection c) throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT s.id, s.sector_name, s.sector_type, "
                   + "SUM(CASE WHEN g.good_bad_type = 'GOOD' THEN g.duplicate_count ELSE 0 END) as good_cnt, "
                   + "SUM(CASE WHEN g.good_bad_type = 'BAD' THEN g.duplicate_count ELSE 0 END) as bad_cnt, "
                   + "SUM(CASE WHEN g.good_bad_type IN ('NEUTRAL', 'MIXED') THEN g.duplicate_count ELSE 0 END) as neu_cnt, "
                   + "AVG(g.impact_score) as avg_score, "
                   + "SUM(g.duplicate_count) as total_cnt "
                   + "FROM sector_master s "
                   + "JOIN news_sector_map m ON s.id = m.sector_id "
                   + "JOIN news_similarity_group g ON m.group_id = g.id "
                   + "WHERE g.analyzed_yn = 'Y' AND s.sector_type IN ('지수', '거시경제') AND g.last_collected_at >= DATE_SUB(NOW(), INTERVAL 3 DAY) "
                   + "GROUP BY s.id, s.sector_name, s.sector_type "
                   + "ORDER BY s.sector_type DESC, SUM(g.duplicate_count) DESC";
        
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> r = new HashMap<>();
                r.put("name", rs.getString("sector_name"));
                r.put("type", rs.getString("sector_type"));
                r.put("good", rs.getInt("good_cnt"));
                r.put("bad", rs.getInt("bad_cnt"));
                r.put("neu", rs.getInt("neu_cnt"));
                r.put("avg", rs.getDouble("avg_score"));
                r.put("total", rs.getInt("total_cnt"));
                list.add(r);
            }
        }
        return list;
    }

    private List<Map<String, Object>> stockSignals(Connection c) throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT st.stock_code, st.stock_name, st.market, "
                   + "SUM(CASE WHEN m.good_bad_type = 'GOOD' THEN g.duplicate_count ELSE 0 END) as good_cnt, "
                   + "SUM(CASE WHEN m.good_bad_type = 'BAD' THEN g.duplicate_count ELSE 0 END) as bad_cnt, "
                   + "SUM(CASE WHEN m.good_bad_type IN ('NEUTRAL', 'MIXED') THEN g.duplicate_count ELSE 0 END) as neu_cnt, "
                   + "AVG(g.impact_score) as avg_score, "
                   + "SUM(g.duplicate_count) as total_cnt "
                   + "FROM stock_master st "
                   + "JOIN news_stock_map m ON st.stock_code = m.stock_code "
                   + "JOIN news_similarity_group g ON m.group_id = g.id "
                   + "WHERE g.analyzed_yn = 'Y' AND g.last_collected_at >= DATE_SUB(NOW(), INTERVAL 3 DAY) "
                   + "GROUP BY st.stock_code, st.stock_name, st.market "
                   + "ORDER BY SUM(g.duplicate_count) DESC, ABS(AVG(g.impact_score)) DESC LIMIT 15";
        
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> r = new HashMap<>();
                r.put("code", rs.getString("stock_code"));
                r.put("name", rs.getString("stock_name"));
                r.put("market", rs.getString("market"));
                r.put("good", rs.getInt("good_cnt"));
                r.put("bad", rs.getInt("bad_cnt"));
                r.put("neu", rs.getInt("neu_cnt"));
                r.put("avg", rs.getDouble("avg_score"));
                r.put("total", rs.getInt("total_cnt"));
                list.add(r);
            }
        }
        return list;
    }
}
