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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
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
                
                // 생성형 AI가 작성한 뉴스 제외 필터링 (제목 및 요약문 기반 강력 필터)
                String lowerTitle = n.title.toLowerCase();
                String lowerDesc = n.description.toLowerCase();
                boolean isFiltered = false;
                
                String[] aiKeywords = {
                    "ai가 작성", "생성형 ai가 작성", "로봇 기자", "로봇기자", "ai 요약", 
                    "인공지능이 작성", "생성형 ai가 함께", "ai 기자", "인공지능 기자", 
                    "데이터랩", "metavx"
                };
                
                String[] excludeKeywords = {
                    "종교", "스포츠", "연예인", "연예",
                    "축구", "야구", "농구", "올림픽", "월드컵", 
                    "배우", "아이돌", "가수", "기독교", "불교", "천주교",
                    "서울데이터랩", "서울 데이터랩", "데이터랩"
                };

                for (String kw : aiKeywords) {
                    if (lowerTitle.contains(kw) || lowerDesc.contains(kw)) {
                        isFiltered = true;
                        break;
                    }
                }
                
                if (!isFiltered) {
                    for (String kw : excludeKeywords) {
                        if (lowerTitle.contains(kw) || lowerDesc.contains(kw)) {
                            isFiltered = true;
                            break;
                        }
                    }
                }

                if (isFiltered) {
                    continue; // AI 작성 또는 제외 키워드가 포함된 기사는 수집에서 제외
                }
                
                LocalDateTime parsedDate = LocalDateTime.now();
                try {
                    String pd = getStr(it, "pubDate");
                    if (!pd.isEmpty()) {
                        parsedDate = ZonedDateTime.parse(pd, DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDateTime();
                    }
                } catch (Exception e) {
                    // parsing failed, use now
                }
                
                if (parsedDate.isBefore(LocalDateTime.now().minusDays(3))) {
                    continue; // 3일 이전 기사 제외
                }
                
                n.originalLink= getStr(it, "originallink");
                
                String press = null;
                if (n.originalLink != null && n.originalLink.startsWith("http")) {
                    try {
                        java.net.URL parsedUrl = new java.net.URL(n.originalLink);
                        press = parsedUrl.getHost();
                        if (press != null && press.startsWith("www.")) {
                            press = press.substring(4);
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }
                n.press       = press;
                n.naverLink   = getStr(it, "link");
                n.pubDate     = parsedDate;
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
        
        if ("코스피".equals(keyword)) {
            out.add(createMock("코스피, 기관·외인 매수세에 2600선 탈환… 반도체 대형주 주도", 
                "기관과 외국인의 동반 매수세에 힘입어 코스피 지수가 2600선을 회복했다.", "연합인포맥스", now.minusMinutes(3)));
        } else if ("코스닥".equals(keyword)) {
            out.add(createMock("코스닥, 2% 급등하며 850선 안착… 바이오·2차전지 반등", 
                "개인과 외인의 순매수로 코스닥 지수가 2%대 상승 마감했다.", "머니투데이", now.minusMinutes(4)));
        } else if ("나스닥".equals(keyword)) {
            out.add(createMock("나스닥, 빅테크 강세에 사상 최고치 경신… 엔비디아 급등 영향", 
                "뉴욕증시에서 나스닥 지수가 인공지능 수요 기대감에 최고점을 다시 썼다.", "이데일리", now.minusMinutes(6)));
        } else if ("달러 환율".equals(keyword)) {
            out.add(createMock("원/달러 환율, 미 금리 인하 기대감에 1350원선 하락 안정세", 
                "연준의 완화적 스탠스에 원/달러 환율이 하락하며 외환 시장이 안정세를 찾았다.", "한국경제", now.minusMinutes(7)));
        } else if ("삼성전자".equals(keyword)) {
            out.add(createMock("삼성전자, 5세대 HBM 'HBM3E' 엔비디아 공급 승인 완료", 
                "삼성전자의 HBM3E 8단 제품이 최종 품질 테스트를 통과해 본격 양산에 돌입한다.", "서울경제", now.minusMinutes(5)));
        } else if ("SK하이닉스".equals(keyword)) {
            out.add(createMock("SK하이닉스, 차세대 D램 투자 확대… 점유율 1위 굳히기", 
                "SK하이닉스가 HBM 선도 지위를 공고히 하기 위해 추가 라인 증설을 결정했다.", "매일경제", now.minusMinutes(10)));
        } else if ("LG에너지솔루션".equals(keyword)) {
            out.add(createMock("LG에너지솔루션, 미국 대규모 ESS 공급 계약… 3조원 규모", 
                "ESS 시장 성장에 맞춰 북미 현지 업체와 대규모 장기 계약을 체결했다.", "머니투데이", now.minusMinutes(12)));
        } else if ("현대차".equals(keyword)) {
            out.add(createMock("현대차, 2분기 사상 최대 영업이익 달성… 친환경차 믹스 효과", 
                "제네시스와 하이브리드 판매 호조로 현대차가 분기 최대 실적을 갈아치웠다.", "조선비즈", now.minusMinutes(15)));
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
