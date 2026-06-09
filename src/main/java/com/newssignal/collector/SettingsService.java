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

    public static int getInt(String key, int def) {
        try { return Integer.parseInt(get(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    public static double getDouble(String key, double def) {
        try { return Double.parseDouble(get(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    public static List<String> getKeywords() {
        String raw = get("collect.keywords", "반도체");
        return Arrays.stream(raw.split(","))
                     .map(String::trim).filter(s -> !s.isEmpty())
                     .collect(Collectors.toList());
    }
}
