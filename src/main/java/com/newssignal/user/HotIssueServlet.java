package com.newssignal.user;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.newssignal.collector.SettingsService;
import com.newssignal.common.Db;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * 오늘의 핫이슈 AI 요약 (대시보드 카드용). /api/hotissues
 *  - 오늘 분석된 주요 그룹을 Gemini로 헤드라인+불릿 요약, 메모리+설정(DB) 캐시(기본 45분).
 *  - 기능 플래그 `hotissue.enabled`(기본 Y). N이면 비활성(LLM 미호출, 카드 숨김) → 롤백 대비.
 *  - LLM 실패/키 없음이면 규칙기반 폴백으로 항상 무언가는 반환.
 */
@WebServlet("/api/hotissues")
public class HotIssueServlet extends HttpServlet {

    private static volatile String cachedPayload;
    private static volatile long cachedTs;
    private static final Object LOCK = new Object();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        if (!"Y".equalsIgnoreCase(SettingsService.get("hotissue.enabled", "Y"))) {
            write(resp, "{\"enabled\":false}");
            return;
        }
        long ttl = SettingsService.getInt("hotissue.ttl.min", 45) * 60000L;
        long now = System.currentTimeMillis();
        if (cachedPayload != null && now - cachedTs < ttl) { write(resp, cachedPayload); return; }
        synchronized (LOCK) {
            if (cachedPayload != null && System.currentTimeMillis() - cachedTs < ttl) { write(resp, cachedPayload); return; }
            // 재기동 후 첫 호출: 설정에 저장된 캐시가 신선하면 재사용(LLM 절약)
            try {
                long savedTs = Long.parseLong(SettingsService.get("hotissue.ts", "0"));
                String saved = SettingsService.get("hotissue.payload", "");
                if (!saved.isEmpty() && System.currentTimeMillis() - savedTs < ttl) {
                    cachedPayload = saved; cachedTs = savedTs; write(resp, saved); return;
                }
            } catch (Exception ignore) {}
            String payload = generate();
            cachedPayload = payload; cachedTs = System.currentTimeMillis();
            try { SettingsService.set("hotissue.payload", payload); SettingsService.set("hotissue.ts", String.valueOf(cachedTs)); } catch (Exception ignore) {}
            write(resp, payload);
        }
    }

    /* ── 생성 ──────────────────────────────────────────────── */

    private static class Grp { String title, summary, type; int score, dup; }

    private String generate() {
        List<Grp> groups = topGroupsToday();
        JsonObject out = new JsonObject();
        out.addProperty("enabled", true);
        out.addProperty("generatedAt", new SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date()));
        if (groups.isEmpty()) {
            out.addProperty("headline", "오늘 분석된 주요 이슈가 아직 없습니다.");
            out.add("bullets", new JsonArray());
            return out.toString();
        }
        JsonObject llm = null;
        String key = System.getenv("GEMINI_API_KEY");
        if (key != null && !key.trim().isEmpty() && !"dummy_key".equals(key)) {
            try { llm = callGemini(buildPrompt(groups), key); }
            catch (Exception e) { System.err.println("[HotIssue] LLM 실패, 폴백: " + e.getMessage()); }
        }
        if (llm != null && llm.has("headline") && llm.has("bullets")) {
            out.addProperty("aiSynth", true);
            out.addProperty("headline", llm.get("headline").getAsString());
            out.add("bullets", llm.getAsJsonArray("bullets"));
        } else {
            // 규칙기반 폴백 (프론트가 지수+섹터로 합성 헤드라인을 덮어씀)
            out.addProperty("aiSynth", false);
            out.addProperty("headline", fallbackHeadline(groups));
            JsonArray bs = new JsonArray();
            for (int i = 0; i < groups.size() && i < 4; i++) {
                Grp g = groups.get(i);
                JsonObject b = new JsonObject();
                b.addProperty("type", "BAD".equals(g.type) ? "BAD" : "GOOD");
                b.addProperty("text", g.title);
                bs.add(b);
            }
            out.add("bullets", bs);
        }
        return out.toString();
    }

    private List<Grp> topGroupsToday() {
        List<Grp> list = new ArrayList<Grp>();
        String sql = "SELECT group_title, group_summary, good_bad_type, impact_score, duplicate_count "
                   + "FROM news_similarity_group WHERE analyzed_yn='Y' AND DATE(last_collected_at)=CURDATE() "
                   + "ORDER BY ABS(impact_score) DESC, duplicate_count DESC LIMIT 12";
        try (Connection c = Db.conn(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Grp g = new Grp();
                g.title = rs.getString(1);
                g.summary = rs.getString(2);
                g.type = rs.getString(3);
                g.score = rs.getInt(4);
                g.dup = rs.getInt(5);
                if (g.title != null) list.add(g);
            }
        } catch (Exception e) { System.err.println("[HotIssue] query: " + e.getMessage()); }
        return list;
    }

    private String buildPrompt(List<Grp> groups) {
        StringBuilder sb = new StringBuilder();
        sb.append("너는 한국 증시 애널리스트다. 아래는 오늘 수집·분석된 주요 뉴스(제목 | 호재악재 | 영향도 | 요약)다.\n");
        sb.append("이를 바탕으로 오늘 한국 증시의 핵심을 요약하라. 과장 없이 사실 기반으로.\n");
        sb.append("출력은 JSON만: {\"headline\":\"한 줄 요약(45자 이내, 시장 분위기+주도 테마)\",");
        sb.append("\"bullets\":[{\"type\":\"GOOD 또는 BAD\",\"text\":\"핵심 이슈 한 줄(45자 이내, 구체적 종목/테마 포함)\"}]}.");
        sb.append(" bullets는 3~4개, 임팩트 큰 순.\n\n뉴스:\n");
        for (Grp g : groups) {
            String t = g.summary != null && !g.summary.trim().isEmpty() ? g.summary : g.title;
            if (t.length() > 120) t = t.substring(0, 120);
            sb.append("- ").append(g.title).append(" | ").append(g.type).append(" ").append(g.score)
              .append(" | ").append(t).append("\n");
        }
        return sb.toString();
    }

    private String fallbackHeadline(List<Grp> groups) {
        // 가장 많이 보도된(dup 최대) 이슈의 제목 = 그날의 메인 스토리
        Grp top = groups.get(0);
        for (Grp g : groups) if (g.dup > top.dup) top = g;
        String s = top.title;
        return s.length() > 50 ? s.substring(0, 50) + "…" : s;
    }

    /** 429(rate limit)면 가볍게 1회 재시도(최대 ~4초). 실패 시 폴백(프론트 합성 헤드라인)이 처리. */
    private JsonObject callGemini(String prompt, String apiKey) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) Thread.sleep(4000);
            try { return callGeminiOnce(prompt, apiKey); }
            catch (IOException e) {
                last = e;
                if (e.getMessage() != null && e.getMessage().contains("429")) continue;
                throw e;
            }
        }
        throw last;
    }

    private JsonObject callGeminiOnce(String prompt, String apiKey) throws Exception {
        String model = SettingsService.get("gemini.model", "gemini-2.5-flash");
        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        JsonObject part = new JsonObject(); part.addProperty("text", prompt);
        JsonArray parts = new JsonArray(); parts.add(part);
        JsonObject content = new JsonObject(); content.add("parts", parts);
        JsonArray contents = new JsonArray(); contents.add(content);
        // gemini-2.5-flash는 thinking 토큰을 소비하므로 상한을 넉넉히(낮으면 출력이 잘려 JSON 파싱 실패 → 폴백).
        JsonObject genCfg = new JsonObject(); genCfg.addProperty("responseMimeType", "application/json"); genCfg.addProperty("maxOutputTokens", 8192);
        JsonObject body = new JsonObject(); body.add("contents", contents); body.add("generationConfig", genCfg);

        try (OutputStream os = conn.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        if (conn.getResponseCode() != 200) throw new IOException("Gemini HTTP " + conn.getResponseCode());
        StringBuilder r = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line; while ((line = br.readLine()) != null) r.append(line);
        }
        JsonObject root = JsonParser.parseString(r.toString()).getAsJsonObject();
        String text = root.getAsJsonArray("candidates").get(0).getAsJsonObject()
                .getAsJsonObject("content").getAsJsonArray("parts").get(0).getAsJsonObject()
                .get("text").getAsString();
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static void write(HttpServletResponse resp, String json) throws IOException {
        try (PrintWriter w = resp.getWriter()) { w.print(json); }
    }
}
