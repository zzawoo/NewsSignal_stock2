package com.newssignal.user;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.newssignal.common.AuthUtil;
import com.newssignal.common.CsrfFilter;
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

/** 인앱 알림 조회/읽음 처리. /api/my/notifications (AuthFilter 보호). */
@WebServlet("/api/my/notifications")
public class NotificationServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        long uid = AuthUtil.userId(req);
        JsonObject out = new JsonObject();
        try (Connection c = Db.conn()) {
            int unread = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM user_notification WHERE user_id=? AND read_yn='N'")) {
                ps.setLong(1, uid);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) unread = rs.getInt(1); }
            }
            out.addProperty("unread", unread);

            if (!"count".equals(req.getParameter("action"))) {
                JsonArray items = new JsonArray();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id, ntype, title, body, ref_key, read_yn, created_at FROM user_notification " +
                        "WHERE user_id=? ORDER BY id DESC LIMIT 30")) {
                    ps.setLong(1, uid);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            JsonObject n = new JsonObject();
                            n.addProperty("id", rs.getLong("id"));
                            n.addProperty("type", rs.getString("ntype"));
                            n.addProperty("title", rs.getString("title"));
                            n.addProperty("body", rs.getString("body"));
                            n.addProperty("link", linkFromRef(rs.getString("ref_key")));
                            n.addProperty("read", "Y".equals(rs.getString("read_yn")));
                            n.addProperty("at", String.valueOf(rs.getTimestamp("created_at")));
                            items.add(n);
                        }
                    }
                }
                out.add("items", items);
            }
        } catch (Exception e) {
            e.printStackTrace();
            out.addProperty("error", "조회 실패");
        }
        write(resp, out);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        JsonObject out = new JsonObject();
        if (!CsrfFilter.validate(req)) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            out.addProperty("ok", false); write(resp, out); return;
        }
        long uid = AuthUtil.userId(req);
        try (Connection c = Db.conn()) {
            String idStr = req.getParameter("id");
            if (idStr != null && !idStr.trim().isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE user_notification SET read_yn='Y' WHERE user_id=? AND id=?")) {
                    ps.setLong(1, uid); ps.setLong(2, Long.parseLong(idStr.trim())); ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE user_notification SET read_yn='Y' WHERE user_id=? AND read_yn='N'")) {
                    ps.setLong(1, uid); ps.executeUpdate();
                }
            }
            out.addProperty("ok", true);
        } catch (Exception e) {
            e.printStackTrace();
            out.addProperty("ok", false);
        }
        write(resp, out);
    }

    /** ref_key(news:{gid}:s|k:... / price:{code}:{date})로 알림 대상 딥링크를 만든다. 컨텍스트는 프론트가 붙임. */
    private static String linkFromRef(String ref) {
        if (ref != null) {
            String[] p = ref.split(":");
            if (ref.startsWith("news:") && p.length >= 2)  return "/user/dashboard.jsp?group=" + p[1];
            if (ref.startsWith("price:") && p.length >= 2) return "/user/dashboard.jsp?stock=" + p[1];
        }
        return "/user/dashboard.jsp";
    }

    private static void write(HttpServletResponse resp, JsonObject o) throws IOException {
        try (PrintWriter w = resp.getWriter()) { w.print(o.toString()); }
    }
}
