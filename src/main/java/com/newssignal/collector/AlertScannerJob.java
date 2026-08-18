package com.newssignal.collector;

import com.google.gson.JsonObject;
import com.newssignal.common.Db;
import com.newssignal.common.KisApiClient;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 관심 키워드/종목 기반 알림 스캐너 (인앱 알림).
 *  1) 최근 분석된 그룹이 관심 종목/키워드와 매칭 + |impact|>=사용자 임계 → 호재/악재 알림
 *  2) 관심 종목 KIS 등락률(±%)이 사용자 임계 초과 → 급변 알림
 * 중복은 user_notification(uk_user_ref) UNIQUE + INSERT IGNORE 로 방지.
 */
public class AlertScannerJob implements Runnable {

    public static final AtomicBoolean isRunning = new AtomicBoolean(false);

    @Override
    public void run() {
        if (!isRunning.compareAndSet(false, true)) return;
        try (Connection c = Db.conn()) {
            if (userCount(c) == 0) return; // 사용자 없으면 스킵
            newsStockAlerts(c);
            newsKeywordAlerts(c);
            priceAlerts(c);
            pushKakao(c);   // 새 알림을 카카오톡 '나에게 보내기'로 발송
        } catch (Exception e) {
            System.err.println("[AlertScanner] failed: " + e.getMessage());
        } finally {
            isRunning.set(false);
        }
    }

    private int userCount(Connection c) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM users");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** 관심 종목이 등장한 최근 분석 그룹(|impact|>=세트 임계) → 알림. 동일 종목이 여러 세트에 있어도 ref_key로 1회만. */
    private void newsStockAlerts(Connection c) throws Exception {
        String sql =
            "INSERT IGNORE INTO user_notification (user_id, ntype, title, body, link, ref_key) " +
            "SELECT uws.user_id, 'news', " +
            "  CONCAT('[', CASE g.good_bad_type WHEN 'GOOD' THEN '호재' WHEN 'BAD' THEN '악재' ELSE '중립' END, " +
            "         ' ', g.impact_score, '] ', uws.stock_name, ' · ', LEFT(g.group_title,70)), " +
            "  LEFT(g.group_summary,300), '/user/dashboard.jsp', CONCAT('news:', g.id, ':s:', uws.stock_code) " +
            "FROM news_similarity_group g " +
            "JOIN news_stock_map m ON m.group_id = g.id " +
            "JOIN user_watch_stock uws ON uws.stock_code = m.stock_code " +
            "JOIN user_watch_set ws ON ws.id = uws.set_id " +
            "WHERE g.analyzed_yn='Y' AND g.updated_at >= NOW() - INTERVAL 20 MINUTE " +
            "  AND ws.enabled='Y' AND ABS(g.impact_score) >= ws.alert_impact_min";
        try (PreparedStatement ps = c.prepareStatement(sql)) { ps.executeUpdate(); }
    }

    /** 관심 키워드가 제목/섹터에 포함된 최근 분석 그룹(|impact|>=세트 임계) → 알림 */
    private void newsKeywordAlerts(Connection c) throws Exception {
        String sql =
            "INSERT IGNORE INTO user_notification (user_id, ntype, title, body, link, ref_key) " +
            "SELECT uwk.user_id, 'news', " +
            "  CONCAT('[', CASE g.good_bad_type WHEN 'GOOD' THEN '호재' WHEN 'BAD' THEN '악재' ELSE '중립' END, " +
            "         ' ', g.impact_score, '] #', uwk.keyword, ' · ', LEFT(g.group_title,70)), " +
            "  LEFT(g.group_summary,300), '/user/dashboard.jsp', CONCAT('news:', g.id, ':k:', uwk.keyword) " +
            "FROM news_similarity_group g " +
            "JOIN user_watch_keyword uwk " +
            "  ON (g.group_title LIKE CONCAT('%', uwk.keyword, '%') OR g.related_sectors LIKE CONCAT('%', uwk.keyword, '%')) " +
            "JOIN user_watch_set ws ON ws.id = uwk.set_id " +
            "WHERE g.analyzed_yn='Y' AND g.updated_at >= NOW() - INTERVAL 20 MINUTE " +
            "  AND ws.enabled='Y' AND ABS(g.impact_score) >= ws.alert_impact_min";
        try (PreparedStatement ps = c.prepareStatement(sql)) { ps.executeUpdate(); }
    }

    /** 관심 종목 등락률 ±임계 초과 → 급변 알림 (하루 1회/종목) */
    private void priceAlerts(Connection c) throws Exception {
        java.util.List<String[]> codes = new java.util.ArrayList<String[]>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT DISTINCT stock_code, MAX(stock_name) FROM user_watch_stock GROUP BY stock_code");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) codes.add(new String[]{rs.getString(1), rs.getString(2)});
        }
        for (String[] cd : codes) {
            try {
                JsonObject p = KisApiClient.getStockPrice(cd[0]);
                if (p == null || !p.has("prdy_ctrt")) continue;
                double ctrt = parse(p.get("prdy_ctrt").getAsString());
                String sign = p.has("prdy_vrss_sign") ? p.get("prdy_vrss_sign").getAsString() : "3";
                boolean up = sign.equals("1") || sign.equals("2");
                String arrow = up ? "▲" : "▼";
                String name = cd[1] != null ? cd[1] : cd[0];
                String title = "[급변 " + arrow + " " + String.format("%.2f", Math.abs(ctrt)) + "%] " + name;
                String body = name + " 현재가 " + p.get("stck_prpr").getAsString() + "원, 전일대비 " + arrow + Math.abs(ctrt) + "%";
                String ref = "price:" + cd[0] + ":" + java.time.LocalDate.now();
                String sql =
                    "INSERT IGNORE INTO user_notification (user_id, ntype, title, body, link, ref_key) " +
                    "SELECT uws.user_id, 'price', ?, ?, '/user/dashboard.jsp', ? " +
                    "FROM user_watch_stock uws JOIN user_watch_set ws ON ws.id=uws.set_id " +
                    "WHERE uws.stock_code=? AND ws.enabled='Y' AND ? >= ws.alert_price_pct";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, title); ps.setString(2, body); ps.setString(3, ref);
                    ps.setString(4, cd[0]); ps.setDouble(5, Math.abs(ctrt));
                    ps.executeUpdate();
                }
            } catch (Exception ignore) { /* 종목 1건 실패 무시 */ }
        }
    }

    private static double parse(String s) {
        try { return Double.parseDouble(s.replace(",", "").replace("+", "").trim()); } catch (Exception e) { return 0; }
    }

    /** 카카오 연동 사용자의 미발송(pushed_yn='N') 알림을 '나에게 보내기'로 전송하고 발송표시. */
    private void pushKakao(Connection c) throws Exception {
        if (!com.newssignal.common.KakaoClient.isConfigured()) return;
        java.util.List<long[]> ids = new java.util.ArrayList<long[]>();   // [notifId, userId]
        java.util.List<String[]> msgs = new java.util.ArrayList<String[]>(); // [title, body, refKey]
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT n.id, n.user_id, n.title, n.body, n.ref_key FROM user_notification n "
              + "JOIN user_kakao k ON k.user_id = n.user_id "
              + "WHERE n.pushed_yn='N' ORDER BY n.id LIMIT 30");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(new long[]{ rs.getLong(1), rs.getLong(2) });
                msgs.add(new String[]{ rs.getString(3), rs.getString(4), rs.getString(5) });
            }
        }
        String base = com.newssignal.common.KakaoClient.appBaseUrl();
        for (int i = 0; i < ids.size(); i++) {
            long notifId = ids.get(i)[0], userId = ids.get(i)[1];
            String title = msgs.get(i)[0], body = msgs.get(i)[1], ref = msgs.get(i)[2];
            String text = (title == null ? "" : title) + (body == null || body.isEmpty() ? "" : "\n" + body);
            try { com.newssignal.common.KakaoClient.sendToMe(userId, text, base + linkFromRef(ref)); }
            catch (Exception ignore) { /* 1건 실패는 무시 */ }
            // 성공/실패와 무관하게 발송표시(재시도 폭주 방지; 인앱 알림은 그대로 남음)
            try (PreparedStatement up = c.prepareStatement("UPDATE user_notification SET pushed_yn='Y' WHERE id=?")) {
                up.setLong(1, notifId); up.executeUpdate();
            }
        }
    }

    /** ref_key → 대시보드 딥링크(컨텍스트 포함 상대경로). */
    private static String linkFromRef(String ref) {
        if (ref != null) {
            String[] p = ref.split(":");
            if (ref.startsWith("news:") && p.length >= 2)  return "/user/dashboard.jsp?group=" + p[1];
            if (ref.startsWith("price:") && p.length >= 2) return "/user/dashboard.jsp?stock=" + p[1];
        }
        return "/user/dashboard.jsp";
    }
}
