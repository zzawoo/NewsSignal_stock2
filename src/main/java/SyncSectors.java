import com.newssignal.common.Db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

public class SyncSectors {
    public static void main(String[] args) {
        try (Connection conn = Db.conn();
             PreparedStatement ps = conn.prepareStatement("SELECT id, related_sectors, good_bad_type FROM news_similarity_group WHERE related_sectors IS NOT NULL AND related_sectors != ''");
             ResultSet rs = ps.executeQuery()) {

            int count = 0;
            while (rs.next()) {
                long groupId = rs.getLong("id");
                String sectorsStr = rs.getString("related_sectors");
                String type = rs.getString("good_bad_type");
                
                String[] sectors = sectorsStr.split(",");
                for (String s : sectors) {
                    s = s.trim();
                    if (s.isEmpty()) continue;
                    
                    Long sectorId = getOrCreateSector(conn, s);
                    if (sectorId != null) {
                        insertNewsSectorMap(conn, groupId, sectorId, type);
                    }
                }
                count++;
            }
            System.out.println("Synced " + count + " groups.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Long getOrCreateSector(Connection conn, String sectorName) throws Exception {
        String selectSql = "SELECT id FROM sector_master WHERE sector_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setString(1, sectorName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        
        String type = "테마";
        if (sectorName.contains("코스피") || sectorName.contains("코스닥") || sectorName.contains("나스닥")) {
            type = "지수";
        } else if (sectorName.contains("환율") || sectorName.contains("금리") || sectorName.contains("달러") || sectorName.contains("유가") || sectorName.contains("금") || sectorName.contains("은")) {
            type = "거시경제";
        }
        
        String insertSql = "INSERT INTO sector_master (sector_name, sector_type) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, sectorName);
            ps.setString(2, type);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return null;
    }

    private static void insertNewsSectorMap(Connection conn, Long groupId, Long sectorId, String type) throws Exception {
        String checkSql = "SELECT id FROM news_sector_map WHERE group_id = ? AND sector_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setLong(1, groupId);
            ps.setLong(2, sectorId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return;
            }
        }
        String insertSql = "INSERT INTO news_sector_map (group_id, sector_id, good_bad_type) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setLong(1, groupId);
            ps.setLong(2, sectorId);
            ps.setString(3, type != null ? type : "NEUTRAL");
            ps.executeUpdate();
        }
    }
}
