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
     * 그룹 요약 갱신용 공유 스레드풀. 기존엔 그룹마다 new Thread를 띄워(수집 1회에 수백 개)
     * 스레드가 폭증했다 → 데몬 고정풀(소수)로 제한. 큐가 차면 호출 스레드가 직접 실행(CallerRuns).
     */
    private static final java.util.concurrent.ExecutorService SUMMARY_POOL =
            new java.util.concurrent.ThreadPoolExecutor(
                    2, 3, 60L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<Runnable>(1000),
                    new java.util.concurrent.ThreadFactory() {
                        private final java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger(1);
                        @Override public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "group-summary-" + seq.getAndIncrement());
                            t.setDaemon(true); // Tomcat 종료 차단 방지
                            return t;
                        }
                    },
                    new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

    /**
     * 수집된 뉴스 기사 목록을 저장하고 중복 제거 및 유사도 그룹화를 진행합니다.
     */
    public void saveAll(List<NewsArticleDTO> articles, String keyword) {
        cleanupOldData(3); // 수집 전이나 후에 과거 데이터 정리 (3일치 유지)
        
        if (articles == null || articles.isEmpty()) {
            writeLog(keyword, 0, 0, 0, "SUCCESS", "No articles collected");
            return;
        }

        int newCnt = 0;
        int dupCnt = 0;
        int filteredCnt = 0;

        try (Connection conn = Db.conn()) {
            conn.setAutoCommit(false);
            try {
                // 최근 24시간 이내의 활성 유사도 그룹 목록 조회
                List<ActiveGroup> activeGroups = fetchActiveGroups(conn);

                // 유사도 방식: jaccard(기본) | tfidf(IDF 가중 코사인 — 변별력 있는 용어 중심, 과병합↓).
                boolean useTfidf = "tfidf".equalsIgnoreCase(SettingsService.get("similarity.method", "jaccard"));
                double thSec   = SettingsService.getDouble("similarity.tfidf.sector", 0.16);
                double thTitle = SettingsService.getDouble("similarity.tfidf.title", 0.28);
                java.util.Map<String, Double> idf = null;
                if (useTfidf) {
                    List<String> corpus = new ArrayList<>();
                    for (ActiveGroup g : activeGroups) corpus.add(g.title);
                    for (NewsArticleDTO a : articles) corpus.add(a.title);
                    idf = similarityService.computeIdf(corpus);
                    for (ActiveGroup g : activeGroups) g.tfidf = similarityService.tfidfVector(g.title, idf);
                }

                String[] blockPatterns = parseBlockPatterns();
                for (NewsArticleDTO article : articles) {
                    // 0. 관련성·품질 필터: 주식 무관 / 단순 시황 recap / 찍어내기성(리스트·평판) 기사 제외
                    if (isNoise(article.title, blockPatterns)) { filteredCnt++; continue; }

                    // 1. content_hash 계산
                    String hash = similarityService.contentHash(article.title, article.press);
                    article.contentHash = hash;

                    // 2. 완전 중복 검사 (동일 content_hash = 같은 제목·언론사 기사)
                    Long existingId = checkDuplicateHash(conn, hash);
                    if (existingId != null) {
                        dupCnt++;
                        // 같은 기사 '재수집'은 새 보도가 아니므로 duplicate_count를 올리지 않는다.
                        //   (수집이 10분마다 같은 인기기사를 반복 가져와 dup이 실제 기사수의 수십 배로 폭증하던 버그)
                        continue;
                    }

                    // 3. 신규 기사 저장 (초기 duplicate_yn = 'N')
                    Long articleId = insertArticle(conn, article);
                    article.id = articleId;
                    newCnt++;

                    // 4. 유사 이슈 매칭 (최근 24시간 활성 그룹 대상)
                    java.util.Map<String, Double> aVec = useTfidf ? similarityService.tfidfVector(article.title, idf) : null;
                    ActiveGroup matchedGroup = null;
                    for (ActiveGroup group : activeGroups) {
                        boolean same = useTfidf
                                ? similarityService.isSameIssueVec(aVec, article.sectorKeywords, group.tfidf, group.sectors, thSec, thTitle)
                                : similarityService.isSameIssue(article.title, article.sectorKeywords, group.title, group.sectors);
                        if (same) {
                            matchedGroup = group;
                            break;
                        }
                    }

                    if (matchedGroup != null) {
                        // 매칭되는 그룹이 있는 경우: 기존 그룹에 매핑 (duplicate_yn = 'Y')
                        mapToGroup(conn, matchedGroup.id, articleId, useTfidf ? similarityService.cosine(aVec, matchedGroup.tfidf) : similarityService.jaccard(article.title, matchedGroup.title));

                        // 새로운 기사가 기존 그룹 대표 기사보다 최신인 경우 대표 기사 교체
                        if (article.pubDate != null && (matchedGroup.repPubDate == null || article.pubDate.isAfter(matchedGroup.repPubDate))) {
                            updateGroupRepresentative(conn, matchedGroup.id, articleId, article.title);
                            matchedGroup.title = article.title;
                            matchedGroup.repPubDate = article.pubDate;
                            if (useTfidf) matchedGroup.tfidf = aVec;
                        }
                        
                        updateGroup(conn, matchedGroup.id, article.sectorKeywords, matchedGroup.sectors);
                        // 매칭된 메모리 상의 섹터 리스트 갱신
                        if (article.sectorKeywords != null) {
                            for (String s : article.sectorKeywords) {
                                if (!matchedGroup.sectors.contains(s)) matchedGroup.sectors.add(s);
                            }
                        }
                        // 기사가 추가된 그룹의 요약문 갱신
                        final long gid = matchedGroup.id;
                        SUMMARY_POOL.submit(() -> groupSummaryService.generateAndSave(gid));
                    } else {
                        // 매칭되는 그룹이 없는 경우: 신규 유사도 그룹 생성 (대표 기사 등록)
                        Long groupId = createGroup(conn, article);
                        mapToGroupRepresentative(conn, groupId, articleId);

                        // 생성된 그룹을 활성 그룹 목록에 추가하여 후속 기사가 매칭될 수 있도록 함
                        ActiveGroup newGroup = new ActiveGroup();
                        newGroup.id = groupId;
                        newGroup.title = article.title;
                        newGroup.repPubDate = article.pubDate;
                        newGroup.sectors = new ArrayList<>();
                        if (article.sectorKeywords != null) {
                            newGroup.sectors.addAll(article.sectorKeywords);
                        }
                        if (useTfidf) newGroup.tfidf = aVec;
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
                        SUMMARY_POOL.submit(() -> groupSummaryService.generateAndSave(gid));
                    }
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
            writeLog(keyword, articles.size(), newCnt, dupCnt, "SUCCESS", "Saved " + newCnt + " new, " + dupCnt + " duplicates, " + filteredCnt + " filtered");
        } catch (Exception e) {
            System.err.println("[ArticleService] saveAll failed: " + e.getMessage());
            e.printStackTrace();
            writeLog(keyword, articles.size(), 0, 0, "ERROR", e.getMessage());
        }
    }

    /**
     * 수집 노이즈 필터 패턴(제목 기준). 주식 무관 기사 + 단순 시황 recap + 찍어내기성 리스트/평판 차단.
     * `collect.block.patterns`(콤마구분) 설정으로 코드 수정 없이 튜닝 가능.
     */
    private static String[] parseBlockPatterns() {
        String def = "[상한가,[하한가,[52주,[표],fnRASSI,브랜드평판,약보합,강보합,상승 마감,하락 마감,혼조 마감,보합 마감,상승 출발,하락 출발,장마감,증시 마감,코스피 마감,코스닥 마감,지수 마감,마감 시황,포토],육상대회,교육감배,메달 획득,구인·구직,구인구직,만남의 날,관광청,채용설명회,봉사활동,헌혈,[부고,[인사,[동정,[게시판";
        String raw = SettingsService.get("collect.block.patterns", def);
        List<String> ps = new ArrayList<>();
        for (String s : raw.split(",")) { s = s.trim(); if (!s.isEmpty()) ps.add(s); }
        return ps.toArray(new String[0]);
    }

    /** 제목에 차단 패턴이 하나라도 포함되면 노이즈(주식 무관/단순 시황/찍어내기)로 간주. */
    private static boolean isNoise(String title, String[] patterns) {
        if (title == null || title.isEmpty()) return false;
        for (String p : patterns) if (title.contains(p)) return true;
        return false;
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
        String sql = "SELECT g.id, g.group_title, g.related_sectors, a.pub_date " +
                     "FROM news_similarity_group g " +
                     "LEFT JOIN news_articles a ON g.representative_news_id = a.id " +
                     "WHERE g.last_collected_at >= ?";
        // 최근 24시간
        Timestamp threshold = new Timestamp(System.currentTimeMillis() - 24 * 60 * 60 * 1000L);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, threshold);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ActiveGroup g = new ActiveGroup();
                    g.id = rs.getLong("id");
                    g.title = rs.getString("group_title");
                    Timestamp pubDate = rs.getTimestamp("pub_date");
                    if (pubDate != null) {
                        g.repPubDate = pubDate.toLocalDateTime();
                    }
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
        if (merged.size() > 6) merged = new ArrayList<>(merged.subList(0, 6)); // 칩 과다 방지(기사별 누적으로 30개까지 불어남)
        String sectorsStr = String.join(",", merged);

        // 이미 분석된 그룹의 related_sectors(LLM 정제값)는 보존, 미분석 그룹만 갱신
        String sql = "UPDATE news_similarity_group SET duplicate_count = duplicate_count + 1, related_sectors = IF(analyzed_yn='Y', related_sectors, ?), last_collected_at = NOW(), updated_at = NOW() WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sectorsStr);
            ps.setLong(2, groupId);
            ps.executeUpdate();
        }

        // 이미 분석된 그룹은 news_sector_map(섹터 패널 집계용)을 더 늘리지 않는다(LLM 정제 매핑 유지, 패널 재팽창 방지).
        if (articleSectors != null && !isGroupAnalyzed(conn, groupId)) {
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

    private boolean isGroupAnalyzed(Connection conn, Long groupId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT analyzed_yn FROM news_similarity_group WHERE id = ?")) {
            ps.setLong(1, groupId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && "Y".equals(rs.getString(1)); }
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
        String sql1 = "UPDATE news_articles SET similarity_group_id = ?, duplicate_yn = 'N' WHERE id = ?";
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
    
    private void updateGroupRepresentative(Connection conn, Long groupId, Long newArticleId, String newTitle) throws SQLException {
        // 기존 대표 기사를 중복 기사로 강등
        String sql1 = "UPDATE news_articles SET duplicate_yn = 'Y' WHERE similarity_group_id = ? AND duplicate_yn = 'N'";
        try (PreparedStatement ps = conn.prepareStatement(sql1)) {
            ps.setLong(1, groupId);
            ps.executeUpdate();
        }
        String sql2 = "UPDATE news_similarity_map SET is_representative = 'N' WHERE group_id = ? AND is_representative = 'Y'";
        try (PreparedStatement ps = conn.prepareStatement(sql2)) {
            ps.setLong(1, groupId);
            ps.executeUpdate();
        }
        
        // 새 기사를 대표로 승격
        String sql3 = "UPDATE news_articles SET duplicate_yn = 'N' WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql3)) {
            ps.setLong(1, newArticleId);
            ps.executeUpdate();
        }
        String sql4 = "UPDATE news_similarity_map SET is_representative = 'Y' WHERE group_id = ? AND news_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql4)) {
            ps.setLong(1, groupId);
            ps.setLong(2, newArticleId);
            ps.executeUpdate();
        }
        
        // 그룹 타이틀 갱신
        String sql5 = "UPDATE news_similarity_group SET group_title = ?, representative_news_id = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql5)) {
            ps.setString(1, newTitle);
            ps.setLong(2, newArticleId);
            ps.setLong(3, groupId);
            ps.executeUpdate();
        }
    }

    private static final List<String> ALLOWED_SECTORS = Arrays.asList(
        "반도체", "바이오", "2차전지", "자동차", "조선", "방산", "금융", "원전",
        "코스피", "코스닥", "나스닥", "환율", "금리", "지수", "거시경제",
        "유가", "금", "은", "달러",
        "사료", "철강", "건설", "화학", "엔터테인먼트", "IT", "게임", "통신", "기계", "항공", "화장품", "음식료",
        "로봇", "비만치료제", "AI소프트웨어", "수소"
    );

    public static boolean isAllowedSector(String s) {
        if (s == null || s.trim().isEmpty()) return false;
        return ALLOWED_SECTORS.contains(s.trim());
    }

    /**
     * 짧은/흔한 글자라 contains로 매칭하면 무관한 단어까지 잡히는 모호한 키워드.
     * (예: 은→은행/은퇴, 금→금융/현금, 유가→유가증권, 조선→조선일보/조선시대,
     *  사료→"사료됩니다"/史料, 통신→통신사(언론)/ETRI, 기계→기계공학/기계적)
     * 이들은 일반 contains 루프에서 제외하고 아래 문맥 매칭으로만 추가한다.
     */
    private static final List<String> AMBIGUOUS_SECTORS =
            Arrays.asList("유가", "조선", "사료", "통신", "기계");

    /**
     * 본문에서 자동 추출하지 않는 섹터.
     * - 지수/거시경제: 실제 업종/테마 아닌 라벨(코스피/코스닥/나스닥만 인정).
     * - 금/은: 한국어에서 흔한 음절·조사라(현금/세금/받은/같은 + 띄어쓰기) 부분일치 거짓 양성이
     *   근본적으로 불가피 → 본문 추출 제외하고, LLM이 의미로 판단한 related_macro로만 인정.
     */
    private static final List<String> NON_EXTRACTABLE_SECTORS = Arrays.asList("지수", "거시경제", "금", "은");

    public static List<String> extractSectorsFromText(String text) {
        List<String> found = new ArrayList<>();
        if (text == null) return found;

        for (String s : ALLOWED_SECTORS) {
            if (AMBIGUOUS_SECTORS.contains(s) || NON_EXTRACTABLE_SECTORS.contains(s)) continue;
            if (text.contains(s) && !found.contains(s)) {
                found.add(s);
            }
        }

        // 모호 키워드: 명확한 상품/시세 문맥에서만 섹터로 인정 (거짓 양성 방지)
        // (금/은은 NON_EXTRACTABLE → 본문 추출 안 함. LLM related_macro로만 인정)
        // 유가(원유): 유가증권/유가족 제외, 원유 시세 문맥만
        if (!found.contains("유가") && containsAny(text,
                "국제유가", "국제 유가", "유가 상승", "유가 하락", "유가 급등", "유가 급락", "유가 반등",
                "WTI", "브렌트", "원유", "두바이유", "서부텍사스")) {
            found.add("유가");
        }
        // 조선(조선업/해양): 조선일보/조선시대/조선족/북조선 제외, 조선업 문맥만
        if (!found.contains("조선") && containsAny(text,
                "조선업", "조선소", "조선해양", "조선사", "조선3사", "조선 3사", "케이조선", "대우조선",
                "현대미포", "삼성중공업", "한화오션", "HD현대중공업", "선박 수주", "수주잔량",
                "LNG운반선", "LNG선", "컨테이너선", "유조선", "상선", "해운")) {
            found.add("조선");
        }
        // 사료(축산 사료): "사료됩니다"(=생각된다)/史料 제외, 축산·사료 문맥만
        if (!found.contains("사료") && containsAny(text,
                "배합사료", "사료값", "사료 가격", "사료업체", "농협사료", "축산", "양돈", "양계",
                "가축", "펫푸드", "반려동물", "비료", "농업")) {
            found.add("사료");
        }
        // 통신(텔레콤): 통신사(언론)/통신원/ETRI(전자통신연구원) 제외, 텔레콤 문맥만
        if (!found.contains("통신") && containsAny(text,
                "이동통신", "통신3사", "통신 3사", "통신주", "5G", "6G", "알뜰폰", "통신요금",
                "통신비", "통신망", "통신장비", "SK텔레콤", "LG유플러스", "유플러스", "이통사")) {
            found.add("통신");
        }
        // 기계(기계산업): 기계공학/기계적/기계화 제외, 기계 산업 문맥만
        if (!found.contains("기계") && containsAny(text,
                "기계산업", "공작기계", "산업기계", "건설기계", "기계장비", "기계설비",
                "정밀기계", "기계 수주", "농기계")) {
            found.add("기계");
        }

        // Aliases / Keywords mapping
        if ((text.contains("스틸") || text.contains("제철")) && !found.contains("철강")) found.add("철강");
        if ((text.contains("농업") || text.contains("비료")) && !found.contains("사료")) found.add("사료");
        if ((text.contains("오토") || text.contains("모터스") || text.contains("차 부품")) && !found.contains("자동차")) found.add("자동차");
        if ((text.contains("엔터") || text.contains("기획사") || text.contains("스튜디오")) && !found.contains("엔터테인먼트")) found.add("엔터테인먼트");
        if ((text.contains("제약") || text.contains("메디") || text.contains("신약")) && !found.contains("바이오")) found.add("바이오");
        if (text.contains("뷰티") && !found.contains("화장품")) found.add("화장품");
        if (text.contains("소프트웨어") && !found.contains("IT")) found.add("IT");
        if (text.contains("식품") && !found.contains("음식료")) found.add("음식료");
        if ((text.contains("해운") || text.contains("상선")) && !found.contains("조선")) found.add("조선");

        return found;
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null) return false;
        for (String n : needles) {
            if (text.contains(n)) return true;
        }
        return false;
    }

    private Long getOrCreateSectorId(Connection conn, String sectorName) throws SQLException {
        if (!isAllowedSector(sectorName)) {
            return null;
        }
        
        String selectSql = "SELECT id FROM sector_master WHERE sector_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setString(1, sectorName.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        
        String type = "테마";
        if (sectorName.contains("코스피") || sectorName.contains("코스닥") || sectorName.contains("나스닥")) {
            type = "지수";
        } else if (sectorName.contains("환율") || sectorName.contains("금리")) {
            type = "거시경제";
        }
        
        String insertSql = "INSERT INTO sector_master (sector_name, sector_type) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, sectorName.trim());
            ps.setString(2, type);
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

    public void cleanupOldData(int daysToKeep) {
        // daysToKeep일(7일) 이전 기사 및 관련 매핑 정보 정리
        String sql1 = "DELETE FROM news_articles WHERE pub_date < DATE_SUB(NOW(), INTERVAL ? DAY)";
        String sql2 = "DELETE FROM news_similarity_map WHERE news_id NOT IN (SELECT id FROM news_articles)";
        String sql3 = "DELETE FROM news_similarity_group WHERE id NOT IN (SELECT similarity_group_id FROM news_articles WHERE similarity_group_id IS NOT NULL)";
        
        try (Connection conn = Db.conn()) {
            try (PreparedStatement ps = conn.prepareStatement(sql1)) {
                ps.setInt(1, daysToKeep);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(sql2)) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(sql3)) {
                ps.executeUpdate();
            }
        } catch (Exception e) {
            System.err.println("[ArticleService] cleanup failed: " + e.getMessage());
        }
    }

    private static class ActiveGroup {
        Long id;
        String title;
        LocalDateTime repPubDate;
        List<String> sectors;
        java.util.Map<String, Double> tfidf; // similarity.method=tfidf 일 때 대표 제목의 TF-IDF 벡터
    }
}
