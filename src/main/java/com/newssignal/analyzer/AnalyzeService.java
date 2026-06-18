package com.newssignal.analyzer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.newssignal.common.Db;
import com.newssignal.collector.SettingsService;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AnalyzeService {

    private static final Gson GSON = new Gson();

    /**
     * 아직 분석되지 않은 유사도 그룹 대표 기사를 AI로 분석합니다.
     */
    public void analyzeUnanalyzedGroups() {
        // 일일 분석 한도 체크
        int dailyLimit = SettingsService.getInt("analyze.daily.limit", 2000);
        int todayCount = getTodayAnalysisCount();
        if (todayCount >= dailyLimit) {
            System.out.println("[AnalyzeService] Daily analysis limit reached (" + todayCount + "/" + dailyLimit + "). Skipping.");
            return;
        }

        List<UnanalyzedGroup> targets = fetchUnanalyzedGroups();
        if (targets.isEmpty()) {
            return;
        }

        System.out.println("[AnalyzeService] Found " + targets.size() + " groups to analyze.");

        String geminiKey = System.getenv("GEMINI_API_KEY");
        String openaiKey = System.getenv("OPENAI_API_KEY");
        boolean hasGemini = geminiKey != null && !geminiKey.trim().isEmpty() && !"dummy_key".equals(geminiKey);
        boolean hasOpenAI = openaiKey != null && !openaiKey.trim().isEmpty() && !"dummy_key".equals(openaiKey);

        // 분석 제공자 선택. ollama=로컬 LLM(무료, CPU 느림), groq=클라우드(무료·빠름, OpenAI 호환). 기본 gemini.
        String provider = SettingsService.get("analyze.provider", "gemini").trim().toLowerCase();
        boolean useOllama = "ollama".equals(provider);
        boolean useGroq = "groq".equals(provider);
        String ollamaModel = SettingsService.get("ollama.model", "exaone3.5:7.8b");
        String groqModel = SettingsService.get("groq.model", "llama-3.3-70b-versatile");
        String groqKey = System.getenv("GROQ_API_KEY");
        boolean hasGroq = groqKey != null && !groqKey.trim().isEmpty() && !"dummy_key".equals(groqKey);

        if (useGroq && !hasGroq) {
            System.err.println("[AnalyzeService] analyze.provider=groq 인데 GROQ_API_KEY가 없습니다. "
                    + "console.groq.com/keys 에서 무료 키 발급 후 환경변수 GROQ_API_KEY에 설정하세요. (이번 실행 건너뜀)");
            return;
        }

        // 여러 그룹을 한 번의 LLM 호출로 묶어 호출 수를 줄인다.
        int batchSize;
        if (useOllama)      batchSize = Math.max(1, SettingsService.getInt("ollama.batch.size", 2));
        else if (useGroq)   batchSize = Math.max(1, SettingsService.getInt("groq.batch.size", 3));
        else if (hasGemini) batchSize = Math.max(1, SettingsService.getInt("analyze.batch.size", 8));
        else                batchSize = 1;
        // 같은 배치에서 연속 rate limit(429)을 몇 번까지 재시도할지. 초과 시 이번 실행 종료
        // (60초 뒤 스케줄러가 다시 시도). 무한 재시도로 livelock에 빠지지 않도록 상한을 둔다.
        int maxRetryPerBatch = Math.max(1, SettingsService.getInt("analyze.batch.max.retry", 2));
        int currentCount = todayCount;
        int retryStreak = 0;

        for (int i = 0; i < targets.size(); i += batchSize) {
            if (currentCount >= dailyLimit) {
                System.out.println("[AnalyzeService] Daily limit reached mid-run (" + currentCount + "/" + dailyLimit + ").");
                break;
            }
            List<UnanalyzedGroup> batch = targets.subList(i, Math.min(i + batchSize, targets.size()));
            try {
                if (useOllama) {
                    // 로컬 Ollama: 호출 한도 없음 → sleep 없이 바로 다음 배치
                    Map<Long, AnalysisResult> results = callOllamaBatch(batch, ollamaModel);
                    for (UnanalyzedGroup g : batch) {
                        AnalysisResult r = results.get(g.groupId);
                        if (r != null) {
                            saveAnalysisResult(g.groupId, g.newsId, r, g.title + "\n" + g.description);
                            currentCount++;
                        }
                    }
                    retryStreak = 0;
                } else if (useGroq) {
                    // Groq 클라우드(OpenAI 호환, 빠름). 무료 RPM/TPM 한도 → 배치 사이 짧은 간격.
                    Map<Long, AnalysisResult> results = callGroqBatch(batch, groqKey, groqModel);
                    for (UnanalyzedGroup g : batch) {
                        AnalysisResult r = results.get(g.groupId);
                        if (r != null) {
                            saveAnalysisResult(g.groupId, g.newsId, r, g.title + "\n" + g.description);
                            currentCount++;
                        }
                    }
                    retryStreak = 0;
                    Thread.sleep(2000); // 무료 RPM(~30/분) 준수
                } else if (hasGemini) {
                    Map<Long, AnalysisResult> results = callGeminiBatch(batch, geminiKey);
                    for (UnanalyzedGroup g : batch) {
                        AnalysisResult r = results.get(g.groupId);
                        if (r != null) {
                            saveAnalysisResult(g.groupId, g.newsId, r, g.title + "\n" + g.description);
                            currentCount++;
                        }
                    }
                    retryStreak = 0; // 성공 → 연속 카운트 리셋
                    // 무료 티어 RPM(분당 ~15회) 준수: 배치(=1 호출) 사이 4초 간격
                    Thread.sleep(4000);
                } else {
                    // 폴백: OpenAI 또는 목업을 그룹 단위로 처리
                    for (UnanalyzedGroup g : batch) {
                        AnalysisResult r = hasOpenAI
                                ? callOpenAI(g.title, g.description, openaiKey)
                                : generateMockAnalysis(g.title, g.description);
                        if (r != null) {
                            saveAnalysisResult(g.groupId, g.newsId, r, g.title + "\n" + g.description);
                            currentCount++;
                        }
                        if (hasOpenAI) Thread.sleep(4000);
                    }
                    retryStreak = 0;
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                boolean rateLimited = msg != null && (msg.contains("429") || msg.contains("RESOURCE_EXHAUSTED")
                        || msg.contains("Rate limit") || msg.contains("rate_limit"));
                // Gemini는 일일 한도(20/일)가 진짜 병목 → PerDay면 즉시 중단.
                // Groq는 일일 한도가 넉넉하고 분당(RPM)이 병목 → 429라도 잠깐 대기 후 재시도.
                if (rateLimited && !useGroq && (msg.contains("PerDay") || msg.contains("per day"))) {
                    System.err.println("[AnalyzeService] LLM daily quota (RPD) exhausted. Stopping this run.");
                    break;
                } else if (rateLimited) {
                    retryStreak++;
                    if (retryStreak > maxRetryPerBatch) {
                        System.err.println("[AnalyzeService] Rate limit persists ("
                                + retryStreak + "x). Ending this run; will resume next schedule.");
                        break;
                    }
                    // 429: 응답이 알려주는 'try again in Xs'만큼(없으면 20s) 대기 후 같은 배치 재시도
                    long waitMs = parseRetryMillis(msg, 20000);
                    String detail = msg.length() > 200 ? msg.substring(0, 200) : msg;
                    System.err.println("[AnalyzeService] Rate limit (429) on batch starting " + i
                            + " (retry " + retryStreak + "/" + maxRetryPerBatch + "), wait " + (waitMs / 1000) + "s. " + detail);
                    try { Thread.sleep(waitMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    i -= batchSize;
                } else {
                    System.err.println("[AnalyzeService] Failed to analyze batch starting " + i + ": " + msg);
                    e.printStackTrace();
                    retryStreak = 0;
                }
            }
        }
    }

    private int getTodayAnalysisCount() {
        String sql = "SELECT COUNT(*) FROM news_ai_analysis WHERE analyzed_at >= CURDATE()";
        try (Connection conn = Db.conn(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (Exception e) {
            System.err.println("[AnalyzeService] Failed to get today analysis count: " + e.getMessage());
        }
        return 0;
    }

    private List<UnanalyzedGroup> fetchUnanalyzedGroups() {
        List<UnanalyzedGroup> list = new ArrayList<>();
        // 노출도 컷: 여러 매체가 보도(중복)한 그룹만 분석 → 1회성 자잘한 뉴스 제외로 분석량 급감.
        // (analyze.min.duplicate.count 기본 2). 무료 RPD가 한정적이므로 노출도 높은·최신 순으로,
        // 한 실행에서 처리할 최대 그룹 수도 제한한다(analyze.max.per.run).
        int minDup = SettingsService.getInt("analyze.min.duplicate.count", 2);
        int maxPerRun = SettingsService.getInt("analyze.max.per.run", 200);
        // 분석 대상 기간: 어제·오늘 뉴스만 (analyze.recent.days 기본 2 = 오늘+어제).
        // last_collected_at >= 자정 기준 (days-1)일 전 → days=2면 어제 00:00부터.
        int recentDays = Math.max(1, SettingsService.getInt("analyze.recent.days", 2));
        String sql = "SELECT id, representative_news_id FROM news_similarity_group "
                   + "WHERE analyzed_yn = 'N' AND duplicate_count >= ? "
                   + "AND last_collected_at >= (CURDATE() - INTERVAL ? DAY) "
                   + "ORDER BY duplicate_count DESC, last_collected_at DESC LIMIT ?";
        try (Connection conn = Db.conn(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, minDup);
            ps.setInt(2, recentDays - 1);
            ps.setInt(3, maxPerRun);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UnanalyzedGroup g = new UnanalyzedGroup();
                    g.groupId = rs.getLong("id");
                    g.newsId = rs.getLong("representative_news_id");
                    list.add(g);
                }
            }
        } catch (Exception e) {
            System.err.println("[AnalyzeService] Failed to fetch unanalyzed groups: " + e.getMessage());
            return list;
        }

        String articleSql = "SELECT title, description FROM news_articles WHERE similarity_group_id = ? ORDER BY pub_date DESC LIMIT 10";
        try (Connection conn = Db.conn(); PreparedStatement ps = conn.prepareStatement(articleSql)) {
            for (UnanalyzedGroup g : list) {
                ps.setLong(1, g.groupId);
                StringBuilder tb = new StringBuilder();
                StringBuilder db = new StringBuilder();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        tb.append(rs.getString("title")).append("\n");
                        String d = rs.getString("description");
                        if (d != null) db.append(d).append("\n");
                    }
                }
                g.title = tb.toString();
                g.description = db.toString();
            }
        } catch (Exception e) {
            System.err.println("[AnalyzeService] Failed to fetch group articles: " + e.getMessage());
        }

        return list;
    }

    private void saveAnalysisResult(Long groupId, Long newsId, AnalysisResult result, String groupText) throws SQLException {
        String insertSql = "INSERT INTO news_ai_analysis (group_id, news_id, summary_short, summary_detail, " +
                           "good_bad_type, impact_score, impact_reason, risk_factor, sector_keywords, confidence_score) " +
                           "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String updateGroupSql = "UPDATE news_similarity_group SET good_bad_type = ?, impact_score = ?, group_summary = ?, analyzed_yn = 'Y', updated_at = NOW() WHERE id = ?";
        String updateSectorMapSql = "UPDATE news_sector_map SET good_bad_type = ? WHERE group_id = ?";

        try (Connection conn = Db.conn()) {
            conn.setAutoCommit(false);
            try {
                // 1. AI 분석 결과 저장
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setLong(1, groupId);
                    ps.setLong(2, newsId);
                    ps.setString(3, result.summary_short);
                    ps.setString(4, result.summary_detail);
                    ps.setString(5, result.good_bad_type);
                    ps.setInt(6, result.impact_score);
                    ps.setString(7, result.impact_reason);
                    ps.setString(8, result.risk_factor);
                    ps.setString(9, result.sector_keywords);
                    ps.setInt(10, result.confidence_score);
                    ps.executeUpdate();
                }

                // 2. 그룹 테이블 업데이트 (group_summary 포함)
                try (PreparedStatement ps = conn.prepareStatement(updateGroupSql)) {
                    ps.setString(1, result.good_bad_type);
                    ps.setInt(2, result.impact_score);
                    ps.setString(3, result.summary_short);
                    ps.setLong(4, groupId);
                    ps.executeUpdate();
                }

                // 3. 섹터 매핑 테이블 업데이트 (이건 기존에 매핑된 것의 타입만 업데이트)
                try (PreparedStatement ps = conn.prepareStatement(updateSectorMapSql)) {
                    ps.setString(1, result.good_bad_type);
                    ps.setLong(2, groupId);
                    ps.executeUpdate();
                }

                // 3-1. AI가 찾은 섹터 추출 및 저장
                if (result.sector_keywords != null && !result.sector_keywords.trim().isEmpty()) {
                    String[] aiSectors = result.sector_keywords.split(",");
                    for (String s : aiSectors) {
                        String sName = s.trim();
                        Long sectorId = getOrCreateSector(conn, sName);
                        if (sectorId != null) {
                            insertNewsSectorMap(conn, groupId, sectorId, result.good_bad_type);
                        }
                    }
                }

                // 4. 종목 매핑 저장 (LLM이 지목한 종목)
                if (result.related_stocks != null) {
                    for (StockInfo stock : result.related_stocks) {
                        String code = getOrCreateStock(conn, stock);
                        if (code != null) {
                            insertNewsStockMap(conn, groupId, code, result.good_bad_type);
                        }
                    }
                }

                // 4-1. 기사 텍스트에서 stock_master 종목명 직접 매칭 (LLM 코드 누락/목업 보완)
                for (String code : StockResolver.codesFor(groupText)) {
                    insertNewsStockMap(conn, groupId, code, result.good_bad_type);
                }

                // 5. 거시 지표/지수/환율 매핑 저장
                if (result.related_macro != null) {
                    for (String macro : result.related_macro) {
                        if (macro == null) continue;
                        // LLM이 콤마로 합쳐 보내는 경우 분리 ("코스피, 환율" → 코스피 / 환율)
                        for (String part : macro.split(",")) {
                            Long sectorId = getOrCreateMacroSector(conn, part);
                            if (sectorId != null) {
                                insertNewsSectorMap(conn, groupId, sectorId, result.good_bad_type);
                            }
                        }
                    }
                }

                // 6. DB의 sector_map과 UI 태그(related_sectors) 동기화
                syncRelatedSectors(conn, groupId);

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private String getOrCreateStock(Connection conn, StockInfo stock) throws SQLException {
        if (stock.name == null || stock.name.trim().isEmpty()) return null;
        String selectSql = "SELECT stock_code FROM stock_master WHERE stock_name = ? OR stock_code = ?";
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setString(1, stock.name.trim());
            ps.setString(2, stock.code != null ? stock.code.trim() : "");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        // stock_master에 없는 종목은 합성코드(S#####)를 만들지 않는다.
        // LLM이 유효한 6자리 코드를 준 경우에만 신규 등재, 아니면 매핑 생략.
        String code = stock.code != null && stock.code.trim().length() == 6 ? stock.code.trim() : null;
        if (code == null) {
            return null;
        }
        String insertSql = "INSERT INTO stock_master (stock_code, stock_name, market) VALUES (?, ?, 'KRX')";
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setString(1, code);
            ps.setString(2, stock.name.trim());
            ps.executeUpdate();
            return code;
        } catch (SQLException e) {
            return code;
        }
    }

    private void insertNewsStockMap(Connection conn, Long groupId, String stockCode, String type) throws SQLException {
        String checkSql = "SELECT id FROM news_stock_map WHERE group_id = ? AND stock_code = ?";
        try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setLong(1, groupId);
            ps.setString(2, stockCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return;
            }
        }
        String insertSql = "INSERT INTO news_stock_map (group_id, stock_code, good_bad_type) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setLong(1, groupId);
            ps.setString(2, stockCode);
            ps.setString(3, type);
            ps.executeUpdate();
        }
    }

    private void syncRelatedSectors(Connection conn, Long groupId) throws SQLException {
        List<String> allSectors = new ArrayList<>();
        String sql = "SELECT s.sector_name FROM sector_master s JOIN news_sector_map m ON s.id = m.sector_id WHERE m.group_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    allSectors.add(rs.getString("sector_name"));
                }
            }
        }
        if (!allSectors.isEmpty()) {
            String merged = String.join(",", allSectors);
            try (PreparedStatement ps = conn.prepareStatement("UPDATE news_similarity_group SET related_sectors = ? WHERE id = ?")) {
                ps.setString(1, merged);
                ps.setLong(2, groupId);
                ps.executeUpdate();
            }
        }
    }

    private Long getOrCreateMacroSector(Connection conn, String macroName) throws SQLException {
        if (macroName == null || macroName.trim().isEmpty()) return null;
        macroName = macroName.trim();
        // LLM이 자유서술형 거시명을 만들어 쓰레기 섹터가 생기는 것 방지
        // (예: "반도체 시장 동향", "글로벌 AI 투자 트렌드"). 정상 거시명은 짧은 단어(코스피/환율/유가/금리/달러).
        if (macroName.contains(" ") || macroName.length() > 8) return null;
        String selectSql = "SELECT id FROM sector_master WHERE sector_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setString(1, macroName.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        String type = "지수";
        if (macroName.contains("환율") || macroName.contains("달러") || macroName.contains("금리") || macroName.contains("유가") || macroName.contains("금") || macroName.contains("은")) {
            type = "거시경제";
        }
        String insertSql = "INSERT INTO sector_master (sector_name, sector_type) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insertSql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, macroName.trim());
            ps.setString(2, type);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return null;
    }

    private Long getOrCreateSector(Connection conn, String sectorName) throws SQLException {
        if (sectorName == null || !com.newssignal.collector.ArticleService.isAllowedSector(sectorName.trim())) {
            return null;
        }
        String selectSql = "SELECT id FROM sector_master WHERE sector_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setString(1, sectorName.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        String insertSql = "INSERT INTO sector_master (sector_name, sector_type) VALUES (?, '테마')";
        try (PreparedStatement ps = conn.prepareStatement(insertSql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, sectorName.trim());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return null;
    }

    private void insertNewsSectorMap(Connection conn, Long groupId, Long sectorId, String type) throws SQLException {
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
            ps.setString(3, type);
            ps.executeUpdate();
        }
    }

    /**
     * 여러 그룹을 한 번의 Gemini 호출로 묶어 분석한다(무료 RPD 절감의 핵심).
     * 입력 각 그룹에 groupId를 부여하고, 응답 JSON 배열의 'id'로 매핑해 돌려준다.
     * 매핑 안 된 그룹은 결과에서 빠지며 다음 실행에서 재시도된다.
     */
    private Map<Long, AnalysisResult> callGeminiBatch(List<UnanalyzedGroup> batch, String apiKey) throws Exception {
        // 입력 묶음 구성 (TPM 절감을 위해 그룹별 본문 길이 제한)
        JsonArray inputs = new JsonArray();
        for (UnanalyzedGroup g : batch) {
            JsonObject in = new JsonObject();
            in.addProperty("id", g.groupId);
            in.addProperty("title", trunc(g.title, 400));
            in.addProperty("desc", trunc(g.description, 1200));
            inputs.add(in);
        }

        String prompt = "You are a Korean stock-market analyst. Analyze EACH news group in the input array below "
                + "(KRX/KOSPI/KOSDAQ/KONEX listed stocks, prices, indices, exchange rates).\n"
                + "Return ONLY a raw JSON ARRAY (no markdown, no backticks). Each element MUST repeat the same \"id\" "
                + "as its input group and follow this schema:\n"
                + "{\n"
                + "  \"id\": [the input group id, integer],\n"
                + "  \"summary_short\": \"2~3줄 핵심 요약 (한국어, 약 150자)\",\n"
                + "  \"summary_detail\": \"상세 요약 (한국어, 약 300자)\",\n"
                + "  \"good_bad_type\": \"GOOD or BAD or NEUTRAL or MIXED\",\n"
                + "  \"impact_score\": [integer between -5 and +5],\n"
                + "  \"impact_reason\": \"증시/섹터에 미치는 영향 이유 (한국어, 약 300자)\",\n"
                + "  \"risk_factor\": \"주의할 리스크 요인 (한국어, 약 200자)\",\n"
                + "  \"confidence_score\": [integer between 0 and 100],\n"
                + "  \"sector_keywords\": \"comma-separated. You MUST ONLY use these sectors: 반도체, 바이오, 2차전지, 자동차, 조선, 방산, 금융, 원전, 사료, 철강, 건설, 화학, 엔터테인먼트, IT, 게임, 통신, 기계, 항공, 화장품, 음식료. DO NOT use stock names or other words.\",\n"
                + "  \"related_stocks\": [{\"name\": \"종목명 (예: 삼성전자)\", \"code\": \"6-digit code if known\"}],\n"
                + "  \"related_macro\": [\"코스피, 코스닥, 환율, 유가, 금, 은 등 언급된 지수/환율\"]\n"
                + "}\n"
                + "The output array length MUST equal the input array length.\n"
                + "Input groups (JSON array):\n" + inputs.toString();

        String jsonText = postGeminiText(prompt, apiKey);
        return parseBatchResults(jsonText, batch);
    }

    /**
     * 로컬 Ollama로 그룹 배치를 분석한다(호출 한도 없음·무료). /api/chat, format=json.
     * 작은 로컬 모델 안정성을 위해 {"results":[...]} 객체 형태로 받게 유도한다.
     */
    private Map<Long, AnalysisResult> callOllamaBatch(List<UnanalyzedGroup> batch, String model) throws Exception {
        String host = SettingsService.get("ollama.host", "http://localhost:11434");

        JsonArray inputs = new JsonArray();
        for (UnanalyzedGroup g : batch) {
            JsonObject in = new JsonObject();
            in.addProperty("id", g.groupId);
            in.addProperty("title", trunc(g.title, 300));
            in.addProperty("desc", trunc(g.description, 800));
            inputs.add(in);
        }

        String prompt = "당신은 한국 주식시장 애널리스트입니다. 아래 입력 배열의 각 뉴스 그룹을 분석하세요.\n"
                + "반드시 JSON 객체 하나만 출력: {\"results\":[ ... ]}. results 배열의 각 원소는 입력 그룹의 \"id\"를 그대로 포함하고 아래 스키마를 따릅니다.\n"
                + "{\n"
                + "  \"id\": 입력 그룹 id(정수),\n"
                + "  \"summary_short\": \"2~3줄 핵심 요약(한국어, 약 120자)\",\n"
                + "  \"summary_detail\": \"상세 요약(한국어, 약 250자)\",\n"
                + "  \"good_bad_type\": \"GOOD 또는 BAD 또는 NEUTRAL 또는 MIXED\",\n"
                + "  \"impact_score\": -5~5 사이 정수,\n"
                + "  \"impact_reason\": \"증시/섹터 영향 이유(한국어, 약 200자)\",\n"
                + "  \"risk_factor\": \"주의할 리스크(한국어, 약 150자)\",\n"
                + "  \"confidence_score\": 0~100 사이 정수,\n"
                + "  \"sector_keywords\": \"콤마 구분. 반드시 이 섹터만 사용: 반도체,바이오,2차전지,자동차,조선,방산,금융,원전,사료,철강,건설,화학,엔터테인먼트,IT,게임,통신,기계,항공,화장품,음식료. 종목명 사용 금지\",\n"
                + "  \"related_stocks\": [{\"name\":\"종목명\",\"code\":\"6자리코드(알면)\"}],\n"
                + "  \"related_macro\": [\"코스피,코스닥,환율,유가,금,은 등 언급된 지수/환율\"]\n"
                + "}\n"
                + "입력 그룹 배열:\n" + inputs.toString();

        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", model);
        reqBody.addProperty("stream", false);
        reqBody.addProperty("format", "json");
        JsonArray messages = new JsonArray();
        JsonObject um = new JsonObject();
        um.addProperty("role", "user");
        um.addProperty("content", prompt);
        messages.add(um);
        reqBody.add("messages", messages);
        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0.2);
        // CPU 추론에서 출력 토큰이 많으면 매우 느려짐 → 배치 크기에 맞춰 적당히 제한
        options.addProperty("num_predict", SettingsService.getInt("ollama.num.predict", 1536));
        reqBody.add("options", options);

        URL url = new URL(host + "/api/chat");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(SettingsService.getInt("ollama.timeout.ms", 300000)); // 로컬 추론은 느릴 수 있음

        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(reqBody.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("Ollama API returned HTTP " + code + ": " + readErrorBody(conn));
        }

        StringBuilder response = new StringBuilder();
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) response.append(line);
        }

        JsonObject root = com.google.gson.JsonParser.parseString(response.toString()).getAsJsonObject();
        String content = root.getAsJsonObject("message").get("content").getAsString();
        return parseBatchResults(content, batch);
    }

    /**
     * Groq 클라우드(OpenAI 호환 /chat/completions)로 그룹 배치를 분석한다.
     * JSON 모드({"results":[...]})로 받아 parseBatchResults로 매핑.
     */
    private Map<Long, AnalysisResult> callGroqBatch(List<UnanalyzedGroup> batch, String apiKey, String model) throws Exception {
        JsonArray inputs = new JsonArray();
        for (UnanalyzedGroup g : batch) {
            JsonObject in = new JsonObject();
            in.addProperty("id", g.groupId);
            // Groq 무료 TPM(분당 토큰) 절약: 입력 길이를 짧게(대표 제목/요약 위주로 판정 충분)
            in.addProperty("title", trunc(g.title, 220));
            in.addProperty("desc", trunc(g.description, 450));
            inputs.add(in);
        }

        String prompt = "You are a Korean stock-market analyst. Analyze EACH news group in the input array "
                + "(KRX/KOSPI/KOSDAQ listed stocks, prices, indices, FX).\n"
                + "Return ONLY a JSON object: {\"results\":[ ... ]}. Each element MUST repeat its input \"id\" "
                + "and follow this schema. ALL text values MUST be in Korean:\n"
                + "{\"id\":int, \"summary_short\":\"2~3줄 한국어 요약\", \"summary_detail\":\"상세 한국어 요약\", "
                + "\"good_bad_type\":\"GOOD|BAD|NEUTRAL|MIXED\", \"impact_score\":int(-5..5), "
                + "\"impact_reason\":\"한국어\", \"risk_factor\":\"한국어\", \"confidence_score\":int(0..100), "
                + "\"sector_keywords\":\"comma-separated; ONLY use: 반도체,바이오,2차전지,자동차,조선,방산,금융,원전,사료,철강,건설,화학,엔터테인먼트,IT,게임,통신,기계,항공,화장품,음식료\", "
                + "\"related_stocks\":[{\"name\":\"종목명\",\"code\":\"6-digit\"}], "
                + "\"related_macro\":[\"코스피,코스닥,환율,유가,금,은 등\"]}\n"
                + "Input groups (JSON array):\n" + inputs.toString();

        String urlStr = SettingsService.get("groq.host", "https://api.groq.com/openai/v1") + "/chat/completions";
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        // Groq는 Cloudflare 뒤에 있어 Java 기본 User-Agent를 차단(HTTP 403, error 1010)한다 → 브라우저 UA 지정
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(60000);

        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", model);
        reqBody.addProperty("temperature", 0.2);
        JsonObject rf = new JsonObject();
        rf.addProperty("type", "json_object");
        reqBody.add("response_format", rf);
        JsonArray messages = new JsonArray();
        JsonObject um = new JsonObject();
        um.addProperty("role", "user");
        um.addProperty("content", prompt);
        messages.add(um);
        reqBody.add("messages", messages);

        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(reqBody.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("Groq API returned HTTP " + code + ": " + readErrorBody(conn));
        }

        StringBuilder response = new StringBuilder();
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) response.append(line);
        }

        JsonObject root = com.google.gson.JsonParser.parseString(response.toString()).getAsJsonObject();
        String content = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString();
        return parseBatchResults(content, batch);
    }

    /**
     * LLM 배치 응답(JSON 배열 또는 {"results":[...]} 객체)을 파싱해 groupId→결과로 매핑.
     * 응답의 "id"로 매칭하고, 없으면 위치 기반으로 보정한다.
     */
    private Map<Long, AnalysisResult> parseBatchResults(String text, List<UnanalyzedGroup> batch) {
        Map<Long, AnalysisResult> out = new HashMap<>();
        com.google.gson.JsonElement rootEl = com.google.gson.JsonParser.parseString(stripFences(text));
        JsonArray arr;
        if (rootEl.isJsonArray()) {
            arr = rootEl.getAsJsonArray();
        } else if (rootEl.isJsonObject() && rootEl.getAsJsonObject().has("results")
                && rootEl.getAsJsonObject().get("results").isJsonArray()) {
            arr = rootEl.getAsJsonObject().getAsJsonArray("results");
        } else if (rootEl.isJsonObject()
                && (rootEl.getAsJsonObject().has("good_bad_type") || rootEl.getAsJsonObject().has("summary_short"))) {
            // 모델이 래퍼 없이 분석 객체 하나만 반환한 경우(주로 배치=1) → 단일 원소 배열로 취급
            arr = new JsonArray();
            arr.add(rootEl.getAsJsonObject());
        } else {
            return out;
        }
        for (int idx = 0; idx < arr.size(); idx++) {
            if (!arr.get(idx).isJsonObject()) continue;
            JsonObject el = arr.get(idx).getAsJsonObject();
            Long id = null;
            if (el.has("id") && !el.get("id").isJsonNull()) {
                try { id = el.get("id").getAsLong(); } catch (Exception ignore) { id = null; }
            }
            // id가 없거나 매칭 안 되면 위치 기반으로 보정 (모델이 순서 유지한 경우)
            if ((id == null || !batchContains(batch, id)) && idx < batch.size()) {
                id = batch.get(idx).groupId;
            }
            if (id == null) continue;
            // 일부 모델(EXAONE 등)이 sector_keywords를 배열로 내보냄 → 콤마 문자열로 정규화
            // (필드 타입은 String. 허용 섹터 필터는 저장 단계 getOrCreateSector에서 수행)
            if (el.has("sector_keywords") && el.get("sector_keywords").isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (com.google.gson.JsonElement se : el.getAsJsonArray("sector_keywords")) {
                    if (se == null || se.isJsonNull()) continue;
                    if (sb.length() > 0) sb.append(",");
                    sb.append(se.getAsString());
                }
                el.addProperty("sector_keywords", sb.toString());
            }
            AnalysisResult r;
            try {
                r = GSON.fromJson(el, AnalysisResult.class);
            } catch (Exception parseErr) {
                System.err.println("[AnalyzeService] skip element (parse): " + parseErr.getMessage());
                continue;
            }
            if (r != null) {
                // 모델이 범위를 벗어난 점수를 줄 수 있어 방어적으로 clamp
                if (r.impact_score > 5) r.impact_score = 5; else if (r.impact_score < -5) r.impact_score = -5;
                if (r.confidence_score > 100) r.confidence_score = 100; else if (r.confidence_score < 0) r.confidence_score = 0;
                out.put(id, r);
            }
        }
        return out;
    }

    private static boolean batchContains(List<UnanalyzedGroup> batch, long id) {
        for (UnanalyzedGroup g : batch) if (g.groupId != null && g.groupId == id) return true;
        return false;
    }

    private static String trunc(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** 429 메시지의 'try again in 12.3s' 를 ms로 파싱(1~60s clamp). 없으면 def. */
    private static long parseRetryMillis(String msg, long def) {
        if (msg == null) return def;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("try again in ([0-9]+(?:\\.[0-9]+)?)\\s*s", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(msg);
            if (m.find()) {
                long ms = (long) (Double.parseDouble(m.group(1)) * 1000) + 800;
                return Math.min(Math.max(ms, 1000), 60000);
            }
        } catch (Exception ignore) {}
        return def;
    }

    /** Gemini generateContent 호출 후 후보 텍스트를 반환 (단일/배치 공용). */
    private String postGeminiText(String prompt, String apiKey) throws Exception {
        String urlStr = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent?key=" + apiKey;
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        JsonObject reqBody = new JsonObject();
        JsonArray contents = new JsonArray();
        JsonObject contentObj = new JsonObject();
        JsonArray parts = new JsonArray();
        JsonObject partObj = new JsonObject();
        partObj.addProperty("text", prompt);
        parts.add(partObj);
        contentObj.add("parts", parts);
        contents.add(contentObj);
        reqBody.add("contents", contents);

        JsonObject genConfig = new JsonObject();
        genConfig.addProperty("responseMimeType", "application/json");
        // 배치(여러 그룹)를 한 응답에 담으므로 출력이 잘려 JSON 파싱이 깨지지 않도록 상한을 키운다.
        genConfig.addProperty("maxOutputTokens", 8192);
        reqBody.add("generationConfig", genConfig);

        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(reqBody.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("Gemini API returned HTTP " + code + ": " + readErrorBody(conn));
        }

        StringBuilder response = new StringBuilder();
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) response.append(line);
        }

        JsonObject root = com.google.gson.JsonParser.parseString(response.toString()).getAsJsonObject();
        return root.getAsJsonArray("candidates")
                .get(0).getAsJsonObject()
                .getAsJsonObject("content")
                .getAsJsonArray("parts")
                .get(0).getAsJsonObject()
                .get("text").getAsString();
    }

    private AnalysisResult callOpenAI(String title, String desc, String apiKey) throws Exception {
        String urlStr = "https://api.openai.com/v1/chat/completions";
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);

        String prompt = "Analyze the following stock market news based on KRX/KOSPI/KOSDAQ/KONEX listed stocks and domestic/international individual stock prices, indices, and exchange rates. Output a JSON response.\n"
                + "News Title: " + title + "\n"
                + "News Description: " + desc + "\n"
                + "You must return a JSON object matching this schema:\n"
                + "{\n"
                + "  \"summary_short\": \"A 2~3 line summary combining the core contents of the articles in Korean (around 150-200 chars)\",\n"
                + "  \"summary_detail\": \"Detailed summary of the news in Korean (up to 1000 chars)\",\n"
                + "  \"good_bad_type\": \"GOOD or BAD or NEUTRAL or MIXED\",\n"
                + "  \"impact_score\": [integer between -5 and +5],\n"
                + "  \"impact_reason\": \"Detailed explanation of why this news impacts the stock market/sector in Korean (up to 1000 chars)\",\n"
                + "  \"risk_factor\": \"Potential risk factors to watch out for in Korean (up to 1000 chars)\",\n"
                + "  \"confidence_score\": [integer between 0 and 100],\n"
                + "  \"sector_keywords\": \"comma-separated sector keywords. You MUST ONLY use the following allowed sectors: 반도체, 바이오, 2차전지, 자동차, 조선, 방산, 금융, 원전, 사료, 철강, 건설, 화학, 엔터테인먼트, IT, 게임, 통신, 기계, 항공, 화장품, 음식료. DO NOT USE individual stock names or any other words as sectors.\",\n"
                + "  \"related_stocks\": [{\"name\": \"Stock Name (e.g. 삼성전자)\", \"code\": \"6-digit stock code if known (e.g. 005930)\"}],\n"
                + "  \"related_macro\": [\"names of indices/exchange rates mentioned, e.g. 코스피, 코스닥, 환율, 유가, 금, 은\"]\n"
                + "}";

        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", "gpt-4o-mini");
        
        JsonArray messages = new JsonArray();
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", "You are a financial analyst. Return a JSON response matching the requested schema.");
        messages.add(systemMsg);
        
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", prompt);
        messages.add(userMsg);
        
        reqBody.add("messages", messages);

        JsonObject respFormat = new JsonObject();
        respFormat.addProperty("type", "json_object");
        reqBody.add("response_format", respFormat);

        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(reqBody.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("OpenAI API returned HTTP " + code + ": " + readErrorBody(conn));
        }

        StringBuilder response = new StringBuilder();
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) response.append(line);
        }

        JsonObject root = com.google.gson.JsonParser.parseString(response.toString()).getAsJsonObject();
        String jsonText = root.getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString();

        return cleanAndParseJson(jsonText);
    }

    /** 비정상 응답(401/429 등)의 본문을 읽어 로그/예외에 남긴다. API 키 노출 방지를 위해 키는 포함하지 않음. */
    private String readErrorBody(HttpURLConnection conn) {
        try (java.io.InputStream es = conn.getErrorStream()) {
            if (es == null) return "(no body)";
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(es, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            String body = sb.toString();
            // 429 본문의 quotaId("...PerDay...")로 RPD/RPM을 구분하므로 충분히 길게 보존.
            // (Google 에러 본문에는 API 키가 포함되지 않음 — 키는 URL 쿼리에만 있음)
            return body.length() > 2000 ? body.substring(0, 2000) : body;
        } catch (Exception e) {
            return "(error body unreadable: " + e.getMessage() + ")";
        }
    }

    private AnalysisResult cleanAndParseJson(String jsonText) {
        return GSON.fromJson(stripFences(jsonText), AnalysisResult.class);
    }

    /** 모델이 ```json ... ``` 펜스를 붙인 경우 제거하고 순수 JSON 문자열을 반환. */
    private static String stripFences(String jsonText) {
        String cleaned = jsonText.trim();
        if (cleaned.startsWith("```")) {
            int firstLineBreak = cleaned.indexOf('\n');
            int lastBackticks = cleaned.lastIndexOf("```");
            if (firstLineBreak != -1 && lastBackticks != -1 && lastBackticks > firstLineBreak) {
                cleaned = cleaned.substring(firstLineBreak + 1, lastBackticks).trim();
            }
        }
        return cleaned;
    }

    private AnalysisResult generateMockAnalysis(String title, String desc) {
        AnalysisResult res = new AnalysisResult();
        res.summary_short = title.length() > 50 ? title.substring(0, 47) + "..." : title;
        res.summary_detail = desc != null ? desc : title;
        
        // 텍스트 분석에 따른 호재/악재 분류 및 점수
        if (title.contains("공급 임박") || title.contains("강세") || title.contains("수요 폭증") || title.contains("수출 계약") || title.contains("수혜") || title.contains("수주") || title.contains("처방 확대") || title.contains("청신호")) {
            res.good_bad_type = "GOOD";
            res.impact_score = 3 + (int)(Math.random() * 3); // 3 ~ 5
            res.impact_reason = "글로벌 수요 증가 및 대규모 계약 성사 가시화로 단기 및 중장기 매출 성장 동력 확보.";
            res.risk_factor = "경쟁사들의 생산 설비 증설 속도 및 환율 변동성 리스크가 상존합니다.";
        } else if (title.contains("캐즘") || title.contains("둔화") || title.contains("적자") || title.contains("우려") || title.contains("쇼크") || title.contains("약세") || title.contains("피크아웃")) {
            res.good_bad_type = "BAD";
            res.impact_score = -3 - (int)(Math.random() * 3); // -3 ~ -5
            res.impact_reason = "전방 산업의 성장 둔화 및 공급 과잉에 따른 가격 하락 압력 가중으로 실적 둔화 우려.";
            res.risk_factor = "글로벌 보호무역주의 강화 및 추가 관세 도입 여부를 모니터링해야 합니다.";
        } else if (title.contains("엇갈린") || title.contains("리스크 vs")) {
            res.good_bad_type = "MIXED";
            res.impact_score = 1;
            res.impact_reason = "특정 부문의 판매 호조와 대외 거시적 관세 우려가 공존하여 주가 영향은 혼조세 전망.";
            res.risk_factor = "대외 정책 리스크의 현실화 시점과 극복 속도에 따라 방향성이 결정될 예정입니다.";
        } else {
            res.good_bad_type = "NEUTRAL";
            res.impact_score = 0;
            res.impact_reason = "해당 소식은 기존 시장 예상 범위 내에 존재하여 주가에 미치는 직접적 영향은 제한적.";
            res.risk_factor = "시장 수급 및 단기 매크로 지표 발표 결과에 영향을 받을 수 있습니다.";
        }
        res.confidence_score = 80 + (int)(Math.random() * 20); // 80 ~ 100

        // 섹터 추출
        if (title.contains("HBM") || title.contains("반도체")) {
            res.sector_keywords = "반도체,HBM";
        } else if (title.contains("배터리") || title.contains("2차전지") || title.contains("에코프로")) {
            res.sector_keywords = "2차전지";
        } else if (title.contains("방산") || title.contains("수출 계약") || title.contains("K9")) {
            res.sector_keywords = "방산";
        } else if (title.contains("바이오") || title.contains("셀트리온")) {
            res.sector_keywords = "바이오";
        } else if (title.contains("조선") || title.contains("LNG선")) {
            res.sector_keywords = "조선";
        } else if (title.contains("원전")) {
            res.sector_keywords = "원전";
        } else if (title.contains("현대차") || title.contains("자동차")) {
            res.sector_keywords = "자동차";
        } else {
            res.sector_keywords = "기타";
        }

        // 종목 및 거시 지표 모의 추출
        res.related_stocks = new ArrayList<>();
        res.related_macro = new ArrayList<>();
        if (title.contains("삼성전자")) {
            StockInfo s = new StockInfo();
            s.name = "삼성전자";
            s.code = "005930";
            res.related_stocks.add(s);
        }
        if (title.contains("SK하이닉스") || title.contains("하이닉스")) {
            StockInfo s = new StockInfo();
            s.name = "SK하이닉스";
            s.code = "000660";
            res.related_stocks.add(s);
        }
        if (title.contains("LG에너지솔루션") || title.contains("에코프로")) {
            StockInfo s = new StockInfo();
            s.name = "LG에너지솔루션";
            s.code = "373220";
            res.related_stocks.add(s);
        }
        if (title.contains("현대차") || title.contains("자동차")) {
            StockInfo s = new StockInfo();
            s.name = "현대차";
            s.code = "005380";
            res.related_stocks.add(s);
        }
        if (title.contains("코스피")) {
            res.related_macro.add("코스피");
        }
        if (title.contains("코스닥")) {
            res.related_macro.add("코스닥");
        }
        if (title.contains("나스닥")) {
            res.related_macro.add("나스닥");
        }
        if (title.contains("환율") || title.contains("달러")) {
            res.related_macro.add("환율");
        }
        if (title.contains("유가") || Math.random() < 0.1) {
            res.related_macro.add("유가");
        }
        if (title.contains("금") || Math.random() < 0.1) {
            res.related_macro.add("금");
        }
        if (title.contains("은") || Math.random() < 0.05) {
            res.related_macro.add("은");
        }

        return res;
    }

    private static class UnanalyzedGroup {
        Long groupId;
        Long newsId;
        String title;
        String description;
    }

    public static class AnalysisResult {
        public String summary_short;
        public String summary_detail;
        public String good_bad_type;
        public int impact_score;
        public String impact_reason;
        public String risk_factor;
        public int confidence_score;
        public String sector_keywords;
        public List<StockInfo> related_stocks;
        public List<String> related_macro;
    }

    public static class StockInfo {
        public String name;
        public String code;
    }
}
