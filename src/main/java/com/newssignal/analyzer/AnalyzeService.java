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
import java.util.List;

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

        for (UnanalyzedGroup target : targets) {
            if (todayCount >= dailyLimit) {
                System.out.println("[AnalyzeService] Daily limit reached during batch execution.");
                break;
            }

            try {
                AnalysisResult result;
                if (geminiKey != null && !geminiKey.trim().isEmpty() && !"dummy_key".equals(geminiKey)) {
                    result = callGemini(target.title, target.description, geminiKey);
                } else if (openaiKey != null && !openaiKey.trim().isEmpty() && !"dummy_key".equals(openaiKey)) {
                    result = callOpenAI(target.title, target.description, openaiKey);
                } else {
                    // API Key가 없는 경우 휴리스틱 룰 기반 목 분석 수행 (로컬 테스트용)
                    result = generateMockAnalysis(target.title, target.description);
                }

                if (result != null) {
                    saveAnalysisResult(target.groupId, target.newsId, result);
                    todayCount++;
                }
            } catch (Exception e) {
                System.err.println("[AnalyzeService] Failed to analyze group " + target.groupId + ": " + e.getMessage());
                e.printStackTrace();
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
        String sql = "SELECT g.id, g.representative_news_id, a.title, a.description " +
                     "FROM news_similarity_group g " +
                     "JOIN news_articles a ON g.representative_news_id = a.id " +
                     "WHERE g.analyzed_yn = 'N'";
        try (Connection conn = Db.conn(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UnanalyzedGroup g = new UnanalyzedGroup();
                g.groupId = rs.getLong("id");
                g.newsId = rs.getLong("representative_news_id");
                g.title = rs.getString("title");
                g.description = rs.getString("description");
                list.add(g);
            }
        } catch (Exception e) {
            System.err.println("[AnalyzeService] Failed to fetch unanalyzed groups: " + e.getMessage());
        }
        return list;
    }

    private void saveAnalysisResult(Long groupId, Long newsId, AnalysisResult result) throws SQLException {
        String insertSql = "INSERT INTO news_ai_analysis (group_id, news_id, summary_short, summary_detail, " +
                           "good_bad_type, impact_score, impact_reason, risk_factor, sector_keywords, confidence_score) " +
                           "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String updateGroupSql = "UPDATE news_similarity_group SET good_bad_type = ?, impact_score = ?, analyzed_yn = 'Y', updated_at = NOW() WHERE id = ?";
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

                // 2. 그룹 테이블 업데이트
                try (PreparedStatement ps = conn.prepareStatement(updateGroupSql)) {
                    ps.setString(1, result.good_bad_type);
                    ps.setInt(2, result.impact_score);
                    ps.setLong(3, groupId);
                    ps.executeUpdate();
                }

                // 3. 섹터 매핑 테이블 업데이트
                try (PreparedStatement ps = conn.prepareStatement(updateSectorMapSql)) {
                    ps.setString(1, result.good_bad_type);
                    ps.setLong(2, groupId);
                    ps.executeUpdate();
                }

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private AnalysisResult callGemini(String title, String desc, String apiKey) throws Exception {
        String urlStr = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + apiKey;
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);

        String prompt = "Analyze the following stock market news and output a JSON response. "
                + "News Title: " + title + "\n"
                + "News Description: " + desc + "\n"
                + "You must return ONLY a raw JSON object matching this schema. Do not include markdown formatting or backticks.\n"
                + "Schema:\n"
                + "{\n"
                + "  \"summary_short\": \"Short summary of the news in Korean (up to 300 chars)\",\n"
                + "  \"summary_detail\": \"Detailed summary of the news in Korean (up to 1000 chars)\",\n"
                + "  \"good_bad_type\": \"GOOD or BAD or NEUTRAL or MIXED\",\n"
                + "  \"impact_score\": [integer between -5 and +5],\n"
                + "  \"impact_reason\": \"Detailed explanation of why this news impacts the stock market/sector in Korean (up to 1000 chars)\",\n"
                + "  \"risk_factor\": \"Potential risk factors to watch out for in Korean (up to 1000 chars)\",\n"
                + "  \"confidence_score\": [integer between 0 and 100],\n"
                + "  \"sector_keywords\": \"comma-separated sector keywords\"\n"
                + "}";

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
        reqBody.add("generationConfig", genConfig);

        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(reqBody.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("Gemini API returned HTTP " + code);
        }

        StringBuilder response = new StringBuilder();
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) response.append(line);
        }

        JsonObject root = com.google.gson.JsonParser.parseString(response.toString()).getAsJsonObject();
        String jsonText = root.getAsJsonArray("candidates")
                .get(0).getAsJsonObject()
                .getAsJsonObject("content")
                .getAsJsonArray("parts")
                .get(0).getAsJsonObject()
                .get("text").getAsString();

        return cleanAndParseJson(jsonText);
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

        String prompt = "Analyze the following stock market news and output a JSON response.\n"
                + "News Title: " + title + "\n"
                + "News Description: " + desc + "\n"
                + "You must return a JSON object matching this schema:\n"
                + "{\n"
                + "  \"summary_short\": \"Short summary of the news in Korean (up to 300 chars)\",\n"
                + "  \"summary_detail\": \"Detailed summary of the news in Korean (up to 1000 chars)\",\n"
                + "  \"good_bad_type\": \"GOOD or BAD or NEUTRAL or MIXED\",\n"
                + "  \"impact_score\": [integer between -5 and +5],\n"
                + "  \"impact_reason\": \"Detailed explanation of why this news impacts the stock market/sector in Korean (up to 1000 chars)\",\n"
                + "  \"risk_factor\": \"Potential risk factors to watch out for in Korean (up to 1000 chars)\",\n"
                + "  \"confidence_score\": [integer between 0 and 100],\n"
                + "  \"sector_keywords\": \"comma-separated sector keywords\"\n"
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
            throw new IOException("OpenAI API returned HTTP " + code);
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

    private AnalysisResult cleanAndParseJson(String jsonText) {
        String cleaned = jsonText.trim();
        if (cleaned.startsWith("```")) {
            int firstLineBreak = cleaned.indexOf('\n');
            int lastBackticks = cleaned.lastIndexOf("```");
            if (firstLineBreak != -1 && lastBackticks != -1 && lastBackticks > firstLineBreak) {
                cleaned = cleaned.substring(firstLineBreak + 1, lastBackticks).trim();
            }
        }
        return GSON.fromJson(cleaned, AnalysisResult.class);
    }

    private AnalysisResult generateMockAnalysis(String title, String desc) {
        AnalysisResult res = new AnalysisResult();
        res.summary_short = title.length() > 100 ? title.substring(0, 97) + "..." : title;
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
    }
}
