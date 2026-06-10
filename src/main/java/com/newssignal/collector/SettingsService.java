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
        // 1. 거시/주요 키워드
        String raw = get("collect.keywords", "코스피,코스닥,KRX,KONEX,다우,나스닥,환율,유가,금,은");
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
