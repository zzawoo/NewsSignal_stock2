package com.newssignal.collector;

import java.util.List;

/**
 * 수집 1회 실행 단위 (수동 버튼·자동 스케줄러 공용).
 * 파이프라인 진입점: 쿼터 체크 → 키워드별 수집 → 중복제거/유사도/분석은
 * 후속 서비스(미구현 골격)로 위임. 본 골격은 수집·쿼터까지 동작.
 */
public class CollectJob implements Runnable {

    @Override
    public void run() {
        int limit  = SettingsService.getInt("daily.quota.limit", 25000);
        double safe = SettingsService.getDouble("daily.quota.safe.ratio", 0.8);
        QuotaGuard guard = new QuotaGuard(limit, safe);

        List<String> keywords = SettingsService.getKeywords();
        if (!guard.canCall(keywords.size())) {
            System.out.println("[CollectJob] daily quota safe-limit reached, skip. used="
                    + guard.todayCount());
            return;
        }

        // 수집기 구성: API 기본, Jsoup은 설정 ON일 때만 (계획서 4.2)
        NewsCollector api = new NaverNewsApiCollector(
                System.getenv("NAVER_CLIENT_ID"),
                System.getenv("NAVER_CLIENT_SECRET"),
                SettingsService.getInt("collect.timeout.ms", 5000));

        int totalFetched = 0;
        for (String kw : keywords) {
            if (!guard.canCall(1)) break;
            List<NewsArticleDTO> items = api.collect(kw, 30);
            guard.record(1);
            totalFetched += items.size();
            // TODO(2차): 중복제거 → 유사도 그룹화 → 대표 LLM 분석 → 섹터/종목 매핑 → 저장
            //   ArticleService.saveDedup(items);
            //   SimilarityService.regroup();  AnalyzeService.analyzeNewReps();
        }
        System.out.println("[CollectJob] fetched=" + totalFetched
                + " keywords=" + keywords.size() + " quotaUsed=" + guard.todayCount());
    }
}
