package com.newssignal.collector;

import com.newssignal.analyzer.StockResolver;
import com.newssignal.common.Db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 기존에 분석 완료된 유사도 그룹의 기사 텍스트를 stock_master 종목명과 매칭하여
 * news_stock_map을 보강한다.
 *
 * 배경: 기존 13k개 그룹은 LLM 키가 dummy인 상태에서 목업 분석으로 처리되어
 * 하드코딩된 4개 종목(삼성전자/SK하이닉스/현대차/LG엔솔)만 매핑되었다.
 * 이 백필은 StockResolver로 텍스트 내 모든 등재 종목을 추출해 매핑 커버리지를 넓힌다.
 *
 * 멱등(idempotent): (group_id, stock_code) 중복은 건너뛴다.
 * 실행: java -cp <classpath> com.newssignal.collector.BackfillStocks
 */
public class BackfillStocks {

    public static void main(String[] args) {
        long start = System.currentTimeMillis();

        // 1. 분석 완료 그룹의 good_bad_type 로드
        Map<Long, String> groupType = new HashMap<>();
        try (Connection conn = Db.conn();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, good_bad_type FROM news_similarity_group WHERE analyzed_yn = 'Y'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                groupType.put(rs.getLong("id"), rs.getString("good_bad_type"));
            }
        } catch (Exception e) {
            System.err.println("[BackfillStocks] Failed to load groups: " + e.getMessage());
            return;
        }
        System.out.println("[BackfillStocks] Analyzed groups: " + groupType.size());

        // 2. 그룹별 기사 텍스트 누적 (제목 + 요약)
        Map<Long, StringBuilder> groupText = new HashMap<>();
        try (Connection conn = Db.conn();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT similarity_group_id, title, description FROM news_articles "
               + "WHERE similarity_group_id IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long gid = rs.getLong("similarity_group_id");
                if (!groupType.containsKey(gid)) continue;
                StringBuilder sb = groupText.computeIfAbsent(gid, k -> new StringBuilder());
                String t = rs.getString("title");
                String d = rs.getString("description");
                if (t != null) sb.append(t).append('\n');
                if (d != null) sb.append(d).append('\n');
            }
        } catch (Exception e) {
            System.err.println("[BackfillStocks] Failed to load articles: " + e.getMessage());
            return;
        }
        System.out.println("[BackfillStocks] Groups with text: " + groupText.size());

        // 3. 매칭 + 삽입
        String checkSql = "SELECT 1 FROM news_stock_map WHERE group_id = ? AND stock_code = ?";
        String insertSql = "INSERT INTO news_stock_map (group_id, stock_code, good_bad_type) VALUES (?, ?, ?)";

        int processed = 0, inserted = 0;
        try (Connection conn = Db.conn();
             PreparedStatement check = conn.prepareStatement(checkSql);
             PreparedStatement ins = conn.prepareStatement(insertSql)) {

            for (Map.Entry<Long, StringBuilder> entry : groupText.entrySet()) {
                long gid = entry.getKey();
                String type = groupType.get(gid);
                List<String> codes = StockResolver.codesFor(entry.getValue().toString());
                for (String code : codes) {
                    check.setLong(1, gid);
                    check.setString(2, code);
                    boolean exists;
                    try (ResultSet rs = check.executeQuery()) {
                        exists = rs.next();
                    }
                    if (exists) continue;
                    ins.setLong(1, gid);
                    ins.setString(2, code);
                    ins.setString(3, type);
                    ins.executeUpdate();
                    inserted++;
                }
                if (++processed % 1000 == 0) {
                    System.out.println("[BackfillStocks] processed=" + processed + " inserted=" + inserted);
                }
            }
        } catch (Exception e) {
            System.err.println("[BackfillStocks] Insert failed: " + e.getMessage());
            e.printStackTrace();
        }

        long secs = (System.currentTimeMillis() - start) / 1000;
        System.out.println("[BackfillStocks] Done. processed=" + processed
                + " inserted=" + inserted + " (" + secs + "s)");
        Db.shutdown();
    }
}
