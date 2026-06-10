package com.newssignal.collector;

import com.newssignal.analyzer.GroupSummaryService;
import com.newssignal.analyzer.SimilarityService;
import com.newssignal.common.Db;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 뉴스 저장, 중복 체크 및 유사도 그룹화 파이프라인 서비스 (TODO 3단계).
 */
public class ArticleService {

    private final SimilarityService similarityService = new SimilarityService();
    private final GroupSummaryService groupSummaryService = new GroupSummaryService();

    /**
     * 수집된 뉴스 기사 목록을 저장하고 중복 제거 및 유사도 그룹화를 진행합니다.
     */
    public void saveAll(List<NewsArticleDTO> articles, String keyword) {
        if (articles == null || articles.isEmpty()) {
            writeLog(keyword, 0, 0, 0, "SUCCESS", "No articles collected");
            return;
        }

        int newCnt = 0;
        int dupCnt = 0;

        try (Connection conn = Db.conn()) {
            conn.setAutoCommit(false);
            try {
                // 최근 24시간 이내의 활성 유사도 그룹 목록 조회
                List<ActiveGroup> activeGroups = fetchActiveGroups(conn);

                for (NewsArticleDTO article : articles) {
                    // 1. content_hash 계산
                    String hash = similarityService.contentHash(article.title, article.press);
                    article.contentHash = hash;

                    // 2. 완전 중복 검사 (동일 content_hash 기사 존재 여부)
                    Long existingId = checkDuplicateHash(conn, hash);
                    if (existingId != null) {
                        dupCnt++;
                        // 기존 기사의 그룹에 현재 기사도 연결 (모달에서 표시 가능하도록)
                        Long groupId = getGroupIdByArticle(conn, existingId);
                        if (groupId != null) {
                            // 새 기사도 저장하되 duplicate_yn='Y'로 그룹에 연결
                            Long newArticleId = insertArticleWithGroup(conn, article, groupId);
                            if (newArticleId != null) {
                                incrementGroupDuplicateCount(conn, groupId);
                            }
                        } else {
                            incrementGroupDuplicateCountByArticle(conn, existingId);
                        }
                        continue;
                    }

                    // 3. 신규 기사 저장 (초기 duplicate_yn = 'N')
                    Long articleId = insertArticle(conn, article);
                    article.id = articleId;
                    newCnt++;

                    // 4. 유사 이슈 매칭 (최근 24시간 활성 그룹 대상)
                    ActiveGroup matchedGroup = null;
                    for (ActiveGroup group : activeGroups) {
                        if (similarityService.isSameIssue(article.title, article.sectorKeywords, group.title, group.sectors)) {
                            matchedGroup = group;
                            break;
                        }
                    }

                    if (matchedGroup != null) {
                        // 매칭되는 그룹이 있는 경우: 기존 그룹에 매핑 (duplicate_yn = 'Y')
                        mapToGroup(conn, matchedGroup.id, articleId, similarityService.jaccard(article.title, matchedGroup.title));
                        updateGroup(conn, matchedGroup.id, article.sectorKeywords, matchedGroup.sectors);
                        // 매칭된 메모리 상의 섹터 리스트 갱신
                        if (article.sectorKeywords != null) {
                            for (String s : article.sectorKeywords) {
                                if (!matchedGroup.sectors.contains(s)) matchedGroup.sectors.add(s);
                            }
                        }
                        // 기사가 추가된 그룹의 요약문 갱신
                        final long gid = matchedGroup.id;
                        new Thread(() -> groupSummaryService.generateAndSave(gid)).start();
                    } else {
                        // 매칭되는 그룹이 없는 경우: 신규 유사도 그룹 생성 (대표 기사 등록)
                        Long groupId = createGroup(conn, article);
                        mapToGroupRepresentative(conn, groupId, articleId);

                        // 생성된 그룹을 활성 그룹 목록에 추가하여 후속 기사가 매칭될 수 있도록 함
                        ActiveGroup newGroup = new ActiveGroup();
                        newGroup.id = groupId;
                        newGroup.title = article.title;
                        newGroup.sectors = new ArrayList<>();
                        if (article.sectorKeywords != null) {
                            newGroup.sectors.addAll(article.sectorKeywords);
                        }
                        activeGroups.add(newGroup);

                        // 섹터 마스터 등록 및 그룹-섹터 관계 매핑
                        if (article.sectorKeywords != null) {
                            for (String s : article.sectorKeywords) {
                                Long sectorId = getOrCreateSectorId(conn, s);
                                if (sectorId != null) {
                                    insertNewsSectorMap(conn, groupId, sectorId);
                                }
                            }
                        }

                        // 그룹 요약문 생성 (커밋 전 DB 쓰기를 위해 커넥션 외부에서 호출)
                        final long gid = groupId;
                        new Thread(() -> groupSummaryService.generateAndSave(gid)).start();
                    }
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
            writeLog(keyword, articles.size(), newCnt, dupCnt, "SUCCESS", "Saved " + newCnt + " new, " + dupCnt + " duplicates");
        } catch (Exception e) {
            System.err.println("[ArticleService] saveAll failed: " + e.getMessage());
            e.printStackTrace();
            writeLog(keyword, articles.size(), 0, 0, "ERROR", e.getMessage());
        }
    }

    private Long checkDuplicateHash(Connection conn, String hash) throws SQLException {
        String sql = "SELECT id FROM news_articles WHERE content_hash = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return null;
    }

    private void incrementGroupDuplicateCountByArticle(Connection conn, Long articleId) throws SQLException {
        String sql = "SELECT similarity_group_id FROM news_articles WHERE id = ?";
        Long groupId = null;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, articleId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    if (!rs.wasNull()) groupId = id;
                }
            }
        }
        if (groupId != null) {
            incrementGroupDuplicateCount(conn, groupId);
        }
    }

    /** 기사 ID로 해당 기사의 similarity_group_id 조회 */
    private Long getGroupIdByArticle(Connection conn, Long articleId) throws SQLException {
        String sql = "SELECT similarity_group_id FROM news_articles WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, articleId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    if (!rs.wasNull()) return id;
                }
            }
        }
        return null;
    }

    /** 기사를 특정 그룹에 duplicate_yn='Y'로 저장 */
    private Long insertArticleWithGroup(Connection conn, NewsArticleDTO article, Long groupId) throws SQLException {
        String sql = "INSERT INTO news_articles "
                   + "(title, description, original_link, naver_link, press, pub_date, source_type, content_hash, duplicate_yn, similarity_group_id) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'Y', ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, article.title);
            ps.setString(2, article.description);
            ps.setString(3, article.originalLink);
            ps.setString(4, article.naverLink);
            ps.setString(5, article.press);
            ps.setTimestamp(6, article.pubDate != null ? Timestamp.valueOf(article.pubDate) : new Timestamp(System.currentTimeMillis()));
            ps.setString(7, article.sourceType != null ? article.sourceType : "NAVER_API");
            ps.setString(8, article.contentHash);
            ps.setLong(9, groupId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return null;
    }

    /** 그룹의 duplicate_count 직접 증가 */
    private void incrementGroupDuplicateCount(Connection conn, Long groupId) throws SQLException {
        String sql = "UPDATE news_similarity_group SET duplicate_count = duplicate_count + 1, last_collected_at = NOW() WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, groupId);
            ps.executeUpdate();
        }
    }

    private Long insertArticle(Connection conn, NewsArticleDTO article) throws SQLException {
        String sql = "INSERT INTO news_articles (title, description, original_link, naver_link, press, pub_date, source_type, content_hash, duplicate_yn) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'N')";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, article.title);
            ps.setString(2, article.description);
            ps.setString(3, article.originalLink);
            ps.setString(4, article.naverLink);
            ps.setString(5, article.press);
            ps.setTimestamp(6, article.pubDate != null ? Timestamp.valueOf(article.pubDate) : new Timestamp(System.currentTimeMillis()));
            ps.setString(7, article.sourceType != null ? article.sourceType : "NAVER_API");
            ps.setString(8, article.contentHash);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new SQLException("Failed to insert article: keys not generated");
    }

    private List<ActiveGroup> fetchActiveGroups(Connection conn) throws SQLException {
        List<ActiveGroup> list = new ArrayList<>();
        String sql = "SELECT id, group_title, related_sectors FROM news_similarity_group WHERE last_collected_at >= ?";
        // 최근 24시간
        Timestamp threshold = new Timestamp(System.currentTimeMillis() - 24 * 60 * 60 * 1000L);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, threshold);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ActiveGroup g = new ActiveGroup();
                    g.id = rs.getLong("id");
                    g.title = rs.getString("group_title");
                    String sectorsStr = rs.getString("related_sectors");
                    g.sectors = new ArrayList<>();
                    if (sectorsStr != null && !sectorsStr.trim().isEmpty()) {
                        g.sectors.addAll(Arrays.stream(sectorsStr.split(","))
                                .map(String::trim).filter(s -> !s.isEmpty())
                                .collect(Collectors.toList()));
                    }
                    list.add(g);
                }
            }
        }
        return list;
    }

    private void mapToGroup(Connection conn, Long groupId, Long articleId, double jaccard) throws SQLException {
        String sql1 = "UPDATE news_articles SET similarity_group_id = ?, duplicate_yn = 'Y' WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql1)) {
            ps.setLong(1, groupId);
            ps.setLong(2, articleId);
            ps.executeUpdate();
        }
        String sql2 = "INSERT INTO news_similarity_map (group_id, news_id, similarity_score, is_representative) VALUES (?, ?, ?, 'N')";
        try (PreparedStatement ps = conn.prepareStatement(sql2)) {
            ps.setLong(1, groupId);
            ps.setLong(2, articleId);
            ps.setDouble(3, jaccard);
            ps.executeUpdate();
        }
    }

    private void updateGroup(Connection conn, Long groupId, List<String> articleSectors, List<String> groupSectors) throws SQLException {
        List<String> merged = new ArrayList<>(groupSectors);
        if (articleSectors != null) {
            for (String s : articleSectors) {
                if (!merged.contains(s)) merged.add(s);
            }
        }
        String sectorsStr = String.join(",", merged);

        String sql = "UPDATE news_similarity_group SET duplicate_count = duplicate_count + 1, related_sectors = ?, last_collected_at = NOW(), updated_at = NOW() WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sectorsStr);
            ps.setLong(2, groupId);
            ps.executeUpdate();
        }

        if (articleSectors != null) {
            for (String s : articleSectors) {
                if (!groupSectors.contains(s)) {
                    Long sectorId = getOrCreateSectorId(conn, s);
                    if (sectorId != null) {
                        insertNewsSectorMap(conn, groupId, sectorId);
                    }
                }
            }
        }
    }

    private Long createGroup(Connection conn, NewsArticleDTO article) throws SQLException {
        String sql = "INSERT INTO news_similarity_group (group_title, normalized_title, representative_news_id, duplicate_count, related_sectors, first_collected_at, last_collected_at, created_at) VALUES (?, ?, ?, 1, ?, NOW(), NOW(), NOW())";
        String sectorsStr = article.sectorKeywords != null ? String.join(",", article.sectorKeywords) : "";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, article.title);
            ps.setString(2, similarityService.normalize(article.title));
            ps.setLong(3, article.id);
            ps.setString(4, sectorsStr);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new SQLException("Failed to create similarity group");
    }

    private void mapToGroupRepresentative(Connection conn, Long groupId, Long articleId) throws SQLException {
        String sql1 = "UPDATE news_articles SET similarity_group_id = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql1)) {
            ps.setLong(1, groupId);
            ps.setLong(2, articleId);
            ps.executeUpdate();
        }
        String sql2 = "INSERT INTO news_similarity_map (group_id, news_id, similarity_score, is_representative) VALUES (?, ?, 1.0, 'Y')";
        try (PreparedStatement ps = conn.prepareStatement(sql2)) {
            ps.setLong(1, groupId);
            ps.setLong(2, articleId);
            ps.executeUpdate();
        }
    }

    private Long getOrCreateSectorId(Connection conn, String sectorName) throws SQLException {
        String selectSql = "SELECT id FROM sector_master WHERE sector_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setString(1, sectorName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        String insertSql = "INSERT INTO sector_master (sector_name, sector_type) VALUES (?, '테마')";
        try (PreparedStatement ps = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, sectorName);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return null;
    }

    private void insertNewsSectorMap(Connection conn, Long groupId, Long sectorId) throws SQLException {
        String checkSql = "SELECT id FROM news_sector_map WHERE group_id = ? AND sector_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setLong(1, groupId);
            ps.setLong(2, sectorId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return;
            }
        }
        String insertSql = "INSERT INTO news_sector_map (group_id, sector_id) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setLong(1, groupId);
            ps.setLong(2, sectorId);
            ps.executeUpdate();
        }
    }

    private void writeLog(String keyword, int fetched, int newCnt, int dupCnt, String status, String message) {
        String sql = "INSERT INTO collect_log (collector, keyword, fetched_cnt, new_cnt, dup_cnt, status, message) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = Db.conn(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "NaverNewsApiCollector");
            ps.setString(2, keyword);
            ps.setInt(3, fetched);
            ps.setInt(4, newCnt);
            ps.setInt(5, dupCnt);
            ps.setString(6, status);
            ps.setString(7, message != null && message.length() > 500 ? message.substring(0, 497) + "..." : message);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[ArticleService] Failed to write collect log: " + e.getMessage());
        }
    }

    private static class ActiveGroup {
        Long id;
        String title;
        List<String> sectors;
    }
}
