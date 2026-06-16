package com.newssignal.collector;

import com.newssignal.common.Db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** collect_settings 조회 헬퍼 */
public final class SettingsService {

    private SettingsService() {}

    public static String get(String key, String def) {
        String sql = "SELECT setting_value FROM collect_settings WHERE setting_key = ?";
        try (Connection c = Db.conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (Exception e) {
            System.err.println("[Settings] get failed: " + e.getMessage());
        }
        return def;
    }

    public static void set(String key, String value) {
        String sql = "INSERT INTO collect_settings (setting_key, setting_value, updated_at) VALUES (?, ?, NOW()) " +
                     "ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW()";
        try (Connection c = Db.conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[Settings] set failed: " + e.getMessage());
        }
    }

    public static int getInt(String key, int def) {
        try { return Integer.parseInt(get(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    public static double getDouble(String key, double def) {
        try { return Double.parseDouble(get(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    public static List<String> getKeywords() {
        List<String> list = new ArrayList<>();
        // 1. 수집 범위 키워드: 증시/경제/정책/과학·기술/산업 등 주가에 영향이 있는 분야.
        //    (상장 종목명은 아래 2)에서 stock_master 전체로 자동 포함)
        //    실제 운영값은 collect_settings 의 collect.keywords (db/collect_keywords_scope.sql) 가 우선.
        String raw = get("collect.keywords",
                "증시,코스피,코스닥,주가,상장,공모주,IPO,나스닥,다우,S&P500,"
                + "경제,금리,기준금리,환율,물가,인플레이션,경기,수출,수입,무역,관세,유가,원자재,부동산,한국은행,연준,달러,금,은,GDP,"
                + "정책,규제,세제,예산,파업,선거,"
                + "인공지능,AI,배터리,전기차,신약,로봇,우주항공,자율주행,데이터센터,특허,"
                + "반도체,바이오,2차전지,자동차,조선,방산,금융,원전,사료,철강,건설,화학,엔터테인먼트,IT,게임,통신,기계,항공,화장품,음식료");
        Arrays.stream(raw.split(","))
              .map(String::trim).filter(s -> !s.isEmpty())
              .forEach(list::add);
              
        // 2. 상장 종목 전체 (stock_master)
        String sql = "SELECT stock_name FROM stock_master";
        try (Connection c = Db.conn(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (name != null && !name.trim().isEmpty() && !list.contains(name.trim())) {
                    list.add(name.trim());
                }
            }
        } catch (Exception e) {
            System.err.println("[Settings] getKeywords failed: " + e.getMessage());
        }
        return list;
    }
}
