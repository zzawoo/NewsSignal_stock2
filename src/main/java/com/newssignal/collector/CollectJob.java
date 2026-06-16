package com.newssignal.collector;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 수집 1회 실행 단위 (수동 버튼·자동 스케줄러 공용).
 * 파이프라인 진입점: 쿼터 체크 → 키워드별 수집 → 중복제거/유사도/분석은
 * 후속 서비스(미구현 골격)로 위임. 본 골격은 수집·쿼터까지 동작.
 */
public class CollectJob implements Runnable {

    public static final AtomicBoolean isRunning = new AtomicBoolean(false);

    @Override
    public void run() {
        if (!isRunning.compareAndSet(false, true)) {
            System.out.println("[CollectJob] Job is already running. Skipping this run.");
            return;
        }

        try {
        int limit  = SettingsService.getInt("daily.quota.limit", 25000);
        double safe = SettingsService.getDouble("daily.quota.safe.ratio", 0.8);
        QuotaGuard guard = new QuotaGuard(limit, safe);

        List<String> keywords = SettingsService.getKeywords();
        int totalKeywords = keywords.size();
        if (totalKeywords == 0) return;

        int lastIndex = SettingsService.getInt("collect.last.index", 0);
        int maxPerRun = SettingsService.getInt("collect.max.per.run", 100);
        
        int startIndex = lastIndex % totalKeywords;
        int endIndex = Math.min(startIndex + maxPerRun, totalKeywords);
        List<String> batch = keywords.subList(startIndex, endIndex);

        if (!guard.canCall(batch.size())) {
            System.out.println("[CollectJob] daily quota safe-limit reached, skip. used="
                    + guard.todayCount());
            return;
        }

        // 수집기 구성: API 기본
        NewsCollector api = new NaverNewsApiCollector(
                System.getenv("NAVER_CLIENT_ID"),
                System.getenv("NAVER_CLIENT_SECRET"),
                SettingsService.getInt("collect.timeout.ms", 5000));

        int totalFetched = 0;
        ArticleService articleService = new ArticleService();
        for (String kw : batch) {
            if (!guard.canCall(1)) break;
            List<NewsArticleDTO> items = api.collect(kw, 30);
            guard.record(1);
            
            for (NewsArticleDTO item : items) {
                if (item.sectorKeywords == null) {
                    item.sectorKeywords = new java.util.ArrayList<>();
                }
                if (ArticleService.isAllowedSector(kw)) {
                    item.sectorKeywords.add(kw);
                } else {
                    List<String> extracted = ArticleService.extractSectorsFromText(item.title + " " + item.description + " " + kw);
                    for (String ex : extracted) {
                        if (!item.sectorKeywords.contains(ex)) {
                            item.sectorKeywords.add(ex);
                        }
                    }
                }
            }
            
            articleService.saveAll(items, kw);
            totalFetched += items.size();
        }
        
        // 다음 시작 인덱스 저장
        int nextIndex = (endIndex == totalKeywords) ? 0 : endIndex;
        SettingsService.set("collect.last.index", String.valueOf(nextIndex));

        // ※ AI 분석은 더 이상 수집과 동기로 호출하지 않는다.
        //    수집은 DB 적재까지만 책임지고, 분석/요약은 독립 스케줄러(AnalyzeJob)가
        //    DB에 쌓인 미분석 그룹(analyzed_yn='N')을 RPD 예산 내에서 따로 소화한다.
        //    (수집이 LLM 속도/쿼터에 막히지 않도록 producer/consumer 분리)

        System.out.println("[CollectJob] fetched=" + totalFetched
                + " keywords=" + keywords.size() + " quotaUsed=" + guard.todayCount());
        } finally {
            isRunning.set(false);
        }
    }
}
