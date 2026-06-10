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
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 특정 유사도 그룹에 매핑된 뉴스 기사 목록 조회 API.
 * - 대표 기사가 가장 먼저 조회되며(duplicate_yn='N'), 중복 기사들이 발행일 최신순으로 정렬됩니다.
 */
@WebServlet("/api/group/articles")
public class GroupArticlesServlet extends HttpServlet {

    private static final Gson GSON = new Gson();
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String groupIdParam = req.getParameter("groupId");
        if (groupIdParam == null || groupIdParam.trim().isEmpty()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing groupId");
            return;
        }

        long groupId;
        try {
            groupId = Long.parseLong(groupIdParam);
        } catch (NumberFormatException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid groupId");
            return;
        }

        List<Map<String, Object>> articles = new ArrayList<>();

        // ① similarity_group_id로 직접 연결된 기사 + ② 그룹의 representative_news_id 기사 합집합
        // → 해시 중복으로 인해 group_id 링크가 없는 경우에도 대표 기사를 반드시 포함
        String sql =
            "SELECT DISTINCT na.id, na.title, na.press, na.pub_date, na.naver_link, na.original_link, na.duplicate_yn "
          + "FROM news_articles na "
          + "WHERE na.similarity_group_id = ? "
          + "UNION "
          + "SELECT DISTINCT na.id, na.title, na.press, na.pub_date, na.naver_link, na.original_link, na.duplicate_yn "
          + "FROM news_articles na "
          + "JOIN news_similarity_group g ON g.representative_news_id = na.id "
          + "WHERE g.id = ? "
          + "ORDER BY duplicate_yn ASC, pub_date DESC";

        try (Connection conn = Db.conn(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, groupId);
            ps.setLong(2, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> article = new HashMap<>();
                    article.put("id", rs.getLong("id"));
                    article.put("title", rs.getString("title"));
                    article.put("press", rs.getString("press"));

                    Timestamp pubDate = rs.getTimestamp("pub_date");
                    if (pubDate != null) {
                        article.put("pub_date", DATE_FORMAT.format(pubDate));
                    } else {
                        article.put("pub_date", "");
                    }

                    article.put("naver_link", rs.getString("naver_link"));
                    article.put("original_link", rs.getString("original_link"));
                    article.put("duplicate_yn", rs.getString("duplicate_yn"));
                    articles.add(article);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Database error");
            return;
        }

        resp.setContentType("application/json; charset=UTF-8");
        try (PrintWriter out = resp.getWriter()) {
            out.print(GSON.toJson(articles));
        }
    }
}
