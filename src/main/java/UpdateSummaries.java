import com.newssignal.analyzer.GroupSummaryService;
import com.newssignal.common.Db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class UpdateSummaries {
    public static void main(String[] args) {
        try (Connection conn = Db.conn();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM news_similarity_group");
             ResultSet rs = ps.executeQuery()) {
            
            GroupSummaryService svc = new GroupSummaryService();
            int count = 0;
            while (rs.next()) {
                long id = rs.getLong("id");
                svc.generateAndSave(id);
                count++;
            }
            System.out.println("Updated " + count + " group summaries.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
