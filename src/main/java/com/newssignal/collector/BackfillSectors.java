package com.newssignal.collector;

import com.newssignal.common.Db;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BackfillSectors {
    public static void main(String[] args) {
        try (Connection conn = Db.conn();
             PreparedStatement ps = conn.prepareStatement("SELECT id, group_title, group_summary, related_sectors FROM news_similarity_group");
             ResultSet rs = ps.executeQuery()) {
             
            int count = 0;
            while (rs.next()) {
                long id = rs.getLong("id");
                String title = rs.getString("group_title");
                String summary = rs.getString("group_summary");
                String existingSectors = rs.getString("related_sectors");
                
                String text = (title != null ? title : "") + " " + (summary != null ? summary : "");
                List<String> extracted = ArticleService.extractSectorsFromText(text);
                
                List<String> currentList = new ArrayList<>();
                if (existingSectors != null && !existingSectors.isEmpty()) {
                    currentList.addAll(Arrays.asList(existingSectors.split(",")));
                }
                
                boolean changed = false;
                for (String ex : extracted) {
                    if (!currentList.contains(ex)) {
                        currentList.add(ex);
                        changed = true;
                    }
                }
                
                if (changed) {
                    String newSectors = String.join(",", currentList);
                    try (PreparedStatement u = conn.prepareStatement("UPDATE news_similarity_group SET related_sectors = ? WHERE id = ?")) {
                        u.setString(1, newSectors);
                        u.setLong(2, id);
                        u.executeUpdate();
                    }
                    count++;
                }
            }
            System.out.println("Updated " + count + " groups.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
