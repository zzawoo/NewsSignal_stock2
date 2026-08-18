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
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * 관심 "세트" CRUD + 세트별 키워드/종목/알림기준. /api/my/watchlist (AuthFilter 보호).
 * 한 유저가 (키워드+종목+알림기준) 세트를 여러 개 두고 각각 독립 관리한다.
 */
@WebServlet("/api/my/watchlist")
public class WatchlistServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        if ("searchStock".equals(trim(req.getParameter("action")))) { handleSearch(req, resp); return; }
        long uid = AuthUtil.userId(req);
        JsonObject out = new JsonObject();
        try (Connection c = Db.conn()) {
            ensureDefaultSet(c, uid);

            try (PreparedStatement ps = c.prepareStatement("SELECT display_name, username FROM users WHERE id=?")) {
                ps.setLong(1, uid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) { out.addProperty("name", rs.getString("display_name")); out.addProperty("username", rs.getString("username")); }
                }
            }
            out.addProperty("kakaoConfigured", com.newssignal.common.KakaoClient.isConfigured());
            out.addProperty("kakaoLinked", com.newssignal.common.KakaoClient.isLinked(uid));

            JsonArray sets = new JsonArray();
            Map<Long, JsonObject> byId = new HashMap<Long, JsonObject>();
            long defaultSetId = -1;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, name, enabled, alert_impact_min, alert_price_pct FROM user_watch_set WHERE user_id=? ORDER BY id")) {
                ps.setLong(1, uid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long sid = rs.getLong("id");
                        if (defaultSetId < 0) defaultSetId = sid;
                        JsonObject s = new JsonObject();
                        s.addProperty("id", sid);
                        s.addProperty("name", rs.getString("name"));
                        s.addProperty("enabled", "Y".equals(rs.getString("enabled")));
                        s.addProperty("impactMin", rs.getInt("alert_impact_min"));
                        s.addProperty("pricePct", rs.getDouble("alert_price_pct"));
                        s.add("keywords", new JsonArray());
                        s.add("stocks", new JsonArray());
                        sets.add(s);
                        byId.put(sid, s);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT set_id, keyword FROM user_watch_keyword WHERE user_id=? ORDER BY keyword")) {
                ps.setLong(1, uid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) { JsonObject s = byId.get(rs.getLong("set_id")); if (s != null) s.getAsJsonArray("keywords").add(rs.getString("keyword")); }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT set_id, stock_code, stock_name FROM user_watch_stock WHERE user_id=? ORDER BY created_at DESC")) {
                ps.setLong(1, uid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject s = byId.get(rs.getLong("set_id"));
                        if (s != null) { JsonObject o = new JsonObject(); o.addProperty("code", rs.getString("stock_code")); o.addProperty("name", rs.getString("stock_name")); s.getAsJsonArray("stocks").add(o); }
                    }
                }
            }
            out.add("sets", sets);
            out.addProperty("defaultSetId", defaultSetId);
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
            out.addProperty("ok", false); out.addProperty("error", "보안 토큰 오류");
            write(resp, out); return;
        }
        long uid = AuthUtil.userId(req);
        String action = trim(req.getParameter("action"));
        try (Connection c = Db.conn()) {
            if ("createSet".equals(action)) {
                String name = trim(req.getParameter("name")); if (name.isEmpty()) name = "새 세트";
                long id = createSet(c, uid, name);
                out.addProperty("ok", true); out.addProperty("setId", id);

            } else if ("createAlert".equals(action)) {
                // 종목·키워드·알림기준을 자유 조합해 한 번에 생성 (종목만/키워드만/조합 모두 허용)
                String name = trim(req.getParameter("name"));
                int impact = clampInt(req.getParameter("impactMin"), 4, 1, 5);
                double pct = clampDouble(req.getParameter("pricePct"), 5.0, 0.5, 30.0);
                String[] kws = req.getParameterValues("keyword");
                String[] codes = req.getParameterValues("code");
                boolean hasKw = hasAny(kws), hasSt = hasAny(codes);
                if (!hasKw && !hasSt) { out.addProperty("ok", false); out.addProperty("error", "종목이나 키워드를 하나 이상 담아주세요."); write(resp, out); return; }
                if (name.isEmpty()) name = autoName(c, kws, codes);
                long sid = createSet(c, uid, name);
                try (PreparedStatement ps = c.prepareStatement("UPDATE user_watch_set SET alert_impact_min=?, alert_price_pct=? WHERE id=? AND user_id=?")) {
                    ps.setInt(1, impact); ps.setDouble(2, pct); ps.setLong(3, sid); ps.setLong(4, uid); ps.executeUpdate();
                }
                if (kws != null) for (String kw : kws) { kw = trim(kw); if (!kw.isEmpty()) insertKeyword(c, uid, sid, kw); }
                if (codes != null) for (String code : codes) {
                    code = trim(code); if (code.isEmpty()) continue;
                    String nm = lookupStockName(c, code); if (nm == null) nm = code;
                    insertStock(c, uid, sid, code, nm);
                }
                out.addProperty("ok", true); out.addProperty("setId", sid);

            } else if ("renameSet".equals(action)) {
                long sid = ownedSet(c, uid, req);
                if (sid < 0) { return403(resp, out); return; }
                String name = trim(req.getParameter("name")); if (name.isEmpty()) name = "세트";
                try (PreparedStatement ps = c.prepareStatement("UPDATE user_watch_set SET name=? WHERE id=? AND user_id=?")) {
                    ps.setString(1, name); ps.setLong(2, sid); ps.setLong(3, uid); ps.executeUpdate();
                }
                out.addProperty("ok", true);

            } else if ("deleteSet".equals(action)) {
                long sid = ownedSet(c, uid, req);
                if (sid < 0) { return403(resp, out); return; }
                exec(c, "DELETE FROM user_watch_keyword WHERE set_id=? AND user_id=?", sid, uid);
                exec(c, "DELETE FROM user_watch_stock WHERE set_id=? AND user_id=?", sid, uid);
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM user_watch_set WHERE id=? AND user_id=?")) {
                    ps.setLong(1, sid); ps.setLong(2, uid); ps.executeUpdate();
                }
                out.addProperty("ok", true);

            } else if ("setEnabled".equals(action)) {
                long sid = resolveSet(c, uid, req);
                if (sid < 0) { return403(resp, out); return; }
                String en = "N".equalsIgnoreCase(trim(req.getParameter("enabled"))) ? "N" : "Y";
                try (PreparedStatement ps = c.prepareStatement("UPDATE user_watch_set SET enabled=? WHERE id=? AND user_id=?")) {
                    ps.setString(1, en); ps.setLong(2, sid); ps.setLong(3, uid); ps.executeUpdate();
                }
                out.addProperty("ok", true);

            } else if ("settings".equals(action)) {
                long sid = resolveSet(c, uid, req);
                if (sid < 0) { return403(resp, out); return; }
                int impact = clampInt(req.getParameter("impactMin"), 4, 1, 5);
                double pct = clampDouble(req.getParameter("pricePct"), 5.0, 0.5, 30.0);
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE user_watch_set SET alert_impact_min=?, alert_price_pct=? WHERE id=? AND user_id=?")) {
                    ps.setInt(1, impact); ps.setDouble(2, pct); ps.setLong(3, sid); ps.setLong(4, uid); ps.executeUpdate();
                }
                out.addProperty("ok", true);

            } else if ("addKeyword".equals(action)) {
                long sid = resolveSet(c, uid, req);
                if (sid < 0) { return403(resp, out); return; }
                String kw = trim(req.getParameter("keyword"));
                if (!kw.isEmpty()) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT IGNORE INTO user_watch_keyword (user_id, set_id, keyword) VALUES (?,?,?)")) {
                        ps.setLong(1, uid); ps.setLong(2, sid); ps.setString(3, kw); ps.executeUpdate();
                    }
                }
                out.addProperty("ok", true);

            } else if ("delKeyword".equals(action)) {
                long sid = resolveSet(c, uid, req);
                if (sid < 0) { return403(resp, out); return; }
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM user_watch_keyword WHERE set_id=? AND user_id=? AND keyword=?")) {
                    ps.setLong(1, sid); ps.setLong(2, uid); ps.setString(3, trim(req.getParameter("keyword"))); ps.executeUpdate();
                }
                out.addProperty("ok", true);

            } else if ("addStock".equals(action)) {
                long sid = resolveSet(c, uid, req);
                if (sid < 0) { return403(resp, out); return; }
                String code = trim(req.getParameter("code"));
                String name = trim(req.getParameter("name"));
                if (code.isEmpty() && !name.isEmpty()) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT stock_code, stock_name FROM stock_master WHERE stock_name=? OR stock_name LIKE ? "
                          + "ORDER BY (stock_name=?) DESC, CHAR_LENGTH(stock_name) LIMIT 1")) {
                        ps.setString(1, name); ps.setString(2, "%" + name + "%"); ps.setString(3, name);
                        try (ResultSet rs = ps.executeQuery()) { if (rs.next()) { code = rs.getString(1); name = rs.getString(2); } }
                    }
                }
                if (name.isEmpty() && !code.isEmpty()) {
                    try (PreparedStatement ps = c.prepareStatement("SELECT stock_name FROM stock_master WHERE stock_code=?")) {
                        ps.setString(1, code);
                        try (ResultSet rs = ps.executeQuery()) { if (rs.next()) name = rs.getString(1); }
                    }
                }
                if (code.isEmpty()) { out.addProperty("ok", false); out.addProperty("error", "해당 종목을 찾을 수 없습니다."); write(resp, out); return; }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT IGNORE INTO user_watch_stock (user_id, set_id, stock_code, stock_name) VALUES (?,?,?,?)")) {
                    ps.setLong(1, uid); ps.setLong(2, sid); ps.setString(3, code); ps.setString(4, name); ps.executeUpdate();
                }
                out.addProperty("ok", true);

            } else if ("delStock".equals(action)) {
                long sid = resolveSet(c, uid, req);
                if (sid < 0) { return403(resp, out); return; }
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM user_watch_stock WHERE set_id=? AND user_id=? AND stock_code=?")) {
                    ps.setLong(1, sid); ps.setLong(2, uid); ps.setString(3, trim(req.getParameter("code"))); ps.executeUpdate();
                }
                out.addProperty("ok", true);

            } else {
                out.addProperty("ok", false); out.addProperty("error", "알 수 없는 action"); write(resp, out); return;
            }
        } catch (Exception e) {
            e.printStackTrace();
            out.addProperty("ok", false); out.addProperty("error", "처리 실패");
        }
        write(resp, out);
    }

    /* ── 세트 헬퍼 ───────────────────────────────────────────── */

    /** 유저에게 세트가 하나도 없으면 '기본 관심' 세트를 만든다. */
    private void ensureDefaultSet(Connection c, long uid) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM user_watch_set WHERE user_id=?")) {
            ps.setLong(1, uid);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next() && rs.getInt(1) > 0) return; }
        }
        createSet(c, uid, "기본 관심");
    }

    /** 세트 생성 — 알림기준은 유저 기본값(users.alert_*) 승계, 없으면 테이블 기본(4/5%). */
    private long createSet(Connection c, long uid, String name) throws Exception {
        int impact = 4; double pct = 5.0;
        try (PreparedStatement ps = c.prepareStatement("SELECT alert_impact_min, alert_price_pct FROM users WHERE id=?")) {
            ps.setLong(1, uid);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) { impact = rs.getInt(1); pct = rs.getDouble(2); } }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO user_watch_set (user_id, name, alert_impact_min, alert_price_pct) VALUES (?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, uid); ps.setString(2, name); ps.setInt(3, impact); ps.setDouble(4, pct);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); return rs.getLong(1); }
        }
    }

    /**
     * setId 해석:
     *  - 명시했고 소유 → 그 세트
     *  - 명시했지만 미소유 → -1 (거부; 타 유저 세트 조작 방지)
     *  - 미지정(대시보드 ★) → 기본 세트(가장 오래된, 없으면 생성)
     */
    private long resolveSet(Connection c, long uid, HttpServletRequest req) throws Exception {
        String raw = req.getParameter("setId");
        if (raw != null && !raw.trim().isEmpty()) {
            long sid = parseLong(raw);
            return (sid > 0 && isOwner(c, uid, sid)) ? sid : -1;
        }
        ensureDefaultSet(c, uid);
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM user_watch_set WHERE user_id=? ORDER BY id LIMIT 1")) {
            ps.setLong(1, uid);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getLong(1); }
        }
        return createSet(c, uid, "기본 관심");
    }

    /** 명시적으로 소유한 setId만 허용(rename/delete용). 미소유면 -1. */
    private long ownedSet(Connection c, long uid, HttpServletRequest req) throws Exception {
        long sid = parseLong(req.getParameter("setId"));
        return (sid > 0 && isOwner(c, uid, sid)) ? sid : -1;
    }

    private static boolean hasAny(String[] arr) {
        if (arr == null) return false;
        for (String s : arr) if (s != null && !s.trim().isEmpty()) return true;
        return false;
    }

    private String autoName(Connection c, String[] kws, String[] codes) throws Exception {
        if (hasAny(codes)) {
            String first = trim(codes[0]); String nm = lookupStockName(c, first); if (nm == null || nm.isEmpty()) nm = first;
            int n = 0; for (String s : codes) if (s != null && !s.trim().isEmpty()) n++;
            return n > 1 ? nm + " 외 " + (n - 1) : nm;
        }
        if (hasAny(kws)) {
            String k = trim(kws[0]); int n = 0; for (String s : kws) if (s != null && !s.trim().isEmpty()) n++;
            return n > 1 ? k + " 외 " + (n - 1) : k;
        }
        return "관심 알림";
    }

    private String lookupStockName(Connection c, String code) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT stock_name FROM stock_master WHERE stock_code=?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getString(1); }
        }
        return null;
    }

    private void insertKeyword(Connection c, long uid, long sid, String kw) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO user_watch_keyword (user_id, set_id, keyword) VALUES (?,?,?)")) {
            ps.setLong(1, uid); ps.setLong(2, sid); ps.setString(3, kw); ps.executeUpdate();
        }
    }

    private void insertStock(Connection c, long uid, long sid, String code, String name) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO user_watch_stock (user_id, set_id, stock_code, stock_name) VALUES (?,?,?,?)")) {
            ps.setLong(1, uid); ps.setLong(2, sid); ps.setString(3, code); ps.setString(4, name); ps.executeUpdate();
        }
    }

    private boolean isOwner(Connection c, long uid, long sid) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM user_watch_set WHERE id=? AND user_id=?")) {
            ps.setLong(1, sid); ps.setLong(2, uid);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    /** 종목명/코드 자동완성 검색. /api/my/watchlist?action=searchStock&q=... */
    private void handleSearch(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String q = trim(req.getParameter("q"));
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
            } catch (Exception e) { e.printStackTrace(); }
        }
        out.add("results", arr);
        write(resp, out);
    }

    private static void exec(Connection c, String sql, long a, long b) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) { ps.setLong(1, a); ps.setLong(2, b); ps.executeUpdate(); }
    }
    private static long parseLong(String s) { try { return Long.parseLong(s.trim()); } catch (Exception e) { return -1; } }
    private static int clampInt(String s, int def, int lo, int hi) {
        try { int v = Integer.parseInt(s.trim()); return Math.max(lo, Math.min(hi, v)); } catch (Exception e) { return def; }
    }
    private static double clampDouble(String s, double def, double lo, double hi) {
        try { double v = Double.parseDouble(s.trim()); return Math.max(lo, Math.min(hi, v)); } catch (Exception e) { return def; }
    }
    private static String trim(String s) { return s == null ? "" : s.trim(); }
    private static void return403(HttpServletResponse resp, JsonObject out) throws IOException {
        resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
        out.addProperty("ok", false); out.addProperty("error", "세트를 찾을 수 없습니다."); write(resp, out);
    }
    private static void write(HttpServletResponse resp, JsonObject o) throws IOException {
        try (PrintWriter w = resp.getWriter()) { w.print(o.toString()); }
    }
}
