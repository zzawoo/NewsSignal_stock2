package com.newssignal.collector;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 네이버 뉴스 검색 API 수집기 (기본 수집기, 계획서 4.1).
 * - CORS 회피를 위해 반드시 서버사이드에서 호출 (브라우저 직접 호출 금지).
 * - Client ID/Secret은 서버 환경변수로 보관, 로그/프론트 노출 금지 (계획서 9장).
 * - 제공 파라미터: query, display(≤100), start(≤1000), sort(sim/date).
 */
public class NaverNewsApiCollector implements NewsCollector {

    private static final String ENDPOINT = "https://openapi.naver.com/v1/search/news.json";
    private final String clientId;
    private final String clientSecret;
    private final int timeoutMs;

    public NaverNewsApiCollector(String clientId, String clientSecret, int timeoutMs) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.timeoutMs = timeoutMs;
    }

    @Override public String name() { return "NaverNewsApiCollector"; }

    @Override public boolean isEnabled() {
        return clientId != null && !clientId.isEmpty()
            && clientSecret != null && !clientSecret.isEmpty();
    }

    @Override
    public List<NewsArticleDTO> collect(String keyword, int display) {
        List<NewsArticleDTO> out = new ArrayList<>();
        if (!isEnabled()) {
            System.err.println("[Naver] API key not configured");
            return out;
        }
        if ("dummy_id".equals(clientId)) {
            return getMockArticles(keyword, display);
        }
        HttpURLConnection conn = null;
        try {
            String q = URLEncoder.encode(keyword, StandardCharsets.UTF_8.name());
            int d = Math.min(Math.max(display, 1), 100);   // 1~100
            String url = ENDPOINT + "?query=" + q + "&display=" + d + "&sort=date"; // 최신순

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-Naver-Client-Id", clientId);
            conn.setRequestProperty("X-Naver-Client-Secret", clientSecret);
            conn.setRequestProperty("User-Agent", "NewsSignalAI/1.0");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);

            int code = conn.getResponseCode();
            if (code == 429) { System.err.println("[Naver] 429 rate limited"); return out; }
            if (code != 200) { System.err.println("[Naver] HTTP " + code); return out; }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }

            JsonObject root = JsonParser.parseString(sb.toString()).getAsJsonObject();
            JsonArray items = root.getAsJsonArray("items");
            for (int i = 0; i < items.size(); i++) {
                JsonObject it = items.get(i).getAsJsonObject();
                NewsArticleDTO n = new NewsArticleDTO();
                n.title       = stripTags(getStr(it, "title"));
                n.description = stripTags(getStr(it, "description"));
                n.originalLink= getStr(it, "originallink");
                n.naverLink   = getStr(it, "link");
                n.pubDate     = LocalDateTime.now(); // pubDate 파싱은 운영 시 RFC1123 처리
                n.sourceType  = "NAVER_API";
                out.add(n);
            }
        } catch (Exception e) {
            // API Key 등 민감정보가 메시지에 섞이지 않도록 일반 메시지만 로깅
            System.err.println("[Naver] collect failed: " + e.getClass().getSimpleName());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return out;
    }

    private List<NewsArticleDTO> getMockArticles(String keyword, int display) {
        List<NewsArticleDTO> out = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        
        if ("반도체".equals(keyword)) {
            out.add(createMock("삼성전자, 엔비디아에 HBM3E 공급 임박… 4분기 수주 기대감 확대", 
                "삼성전자가 엔비디아향 HBM3E 품질 인증 막바지 단계에 진입하며 대규모 공급 계약 기대감이 커지고 있다.", "한국경제", now.minusMinutes(5)));
            out.add(createMock("삼성 HBM3E 엔비디아 납품 가시화에 반도체 강세", 
                "삼성전자의 고대역폭메모리 공급이 임박했다는 분석에 반도체 업종 전반이 상승세를 보였다.", "매일경제", now.minusMinutes(10)));
            out.add(createMock("\"삼성, HBM 엔비디아 공급 초읽기\"… 외국계 목표가 상향", 
                "외국계 증권사가 삼성전자의 HBM 사업 정상화를 근거로 투자의견을 상향했다.", "서울경제", now.minusMinutes(15)));
            out.add(createMock("SK하이닉스, HBM 수요 폭증에 내년 증설 확대 검토", 
                "AI 가속기 수요 급증으로 SK하이닉스가 HBM 생산능력 추가 확대를 검토 중인 것으로 알려졌다.", "연합뉴스", now.minusMinutes(20)));
            out.add(createMock("D램 현물가 약세 전환… 메모리 업황 피크아웃 논란", 
                "D램 현물 가격이 하락세로 돌아서며 메모리 반도체 업황 고점 논란이 다시 불거지고 있다.", "디지털타임스", now.minusMinutes(30)));
        } else if ("2차전지".equals(keyword)) {
            out.add(createMock("전기차 캐즘 장기화… 2차전지 3분기 실적 쇼크 우려", 
                "전기차 수요 둔화가 예상보다 길어지며 국내 배터리 업체들의 실적 부진이 우려되고 있다.", "이데일리", now.minusMinutes(8)));
            out.add(createMock("배터리 업계 \"캐즘 그늘\"… 에코프로 등 줄줄이 적자 전망", 
                "양극재 가격 하락과 가동률 저하로 2차전지 소재 업체들의 적자 전환 전망이 잇따르고 있다.", "머니투데이", now.minusMinutes(18)));
            out.add(createMock("전기차 둔화에 2차전지株 약세 지속", 
                "글로벌 전기차 판매 둔화 신호가 이어지며 배터리 관련주가 동반 하락했다.", "파이낸셜뉴스", now.minusMinutes(28)));
        } else if ("방산".equals(keyword)) {
            out.add(createMock("한화에어로, 폴란드 K9 추가 수출 계약 임박… 사상 최대 수주", 
                "폴란드와의 2차 이행계약 협상이 마무리 단계에 접어들며 대규모 방산 수출이 가시화되고 있다.", "조선비즈", now.minusMinutes(12)));
            out.add(createMock("K-방산 수출 호조… 폴란드·중동發 수주 릴레이", 
                "폴란드와 중동 지역의 무기 도입 확대로 국내 방산업체들의 수주 잔고가 사상 최대를 기록했다.", "뉴스1", now.minusMinutes(25)));
        } else if ("바이오".equals(keyword)) {
            out.add(createMock("셀트리온, 짐펜트라 美 처방 확대… 4분기 실적 기대", 
                "미국 시장에서 짐펜트라 처방이 빠르게 늘며 셀트리온의 실적 개선 기대감이 높아지고 있다.", "청년의사", now.minusMinutes(14)));
        } else if ("조선".equals(keyword)) {
            out.add(createMock("조선 빅3 LNG선 수주 잇따라… 도크 2027년까지 꽉 찼다", 
                "고부가가치 LNG 운반선 발주가 이어지며 국내 조선사들의 수주 잔고가 안정적으로 채워지고 있다.", "부산일보", now.minusMinutes(16)));
        }
        
        return out;
    }
    
    private NewsArticleDTO createMock(String title, String desc, String press, LocalDateTime pubDate) {
        NewsArticleDTO n = new NewsArticleDTO();
        n.title = title;
        n.description = desc;
        n.originalLink = "http://mock.link/" + System.currentTimeMillis() + "_" + Math.abs(title.hashCode());
        n.naverLink = "http://naver.mock.link/" + System.currentTimeMillis() + "_" + Math.abs(title.hashCode());
        n.pubDate = pubDate;
        n.press = press;
        n.sourceType = "MOCK_API";
        return n;
    }

    private static String getStr(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }
    private static String stripTags(String s) {
        return s == null ? "" : s.replaceAll("<[^>]+>", "")
                                 .replace("&quot;", "\"").replace("&amp;", "&")
                                 .replace("&lt;", "<").replace("&gt;", ">").trim();
    }
}
