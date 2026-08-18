package com.newssignal.collector;

import com.newssignal.common.Db;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

/**
 * 서버 생명주기 리스너 (계획서 4.3).
 * - contextInitialized: 자동수집 설정이 ON이면 스케줄러 가동.
 * - contextDestroyed: 스케줄러 안전 종료 + DB 풀 종료.
 *   정리 로직을 단계별로 분리하고 예외를 삼키지 않고 로깅한다.
 */
@WebListener
public class NewsCollectContextListener implements ServletContextListener {

    private NewsCollectScheduler scheduler;       // 수집 스케줄 (선택)
    private NewsCollectScheduler analyzeScheduler; // 분석 스케줄 (수집과 분리)
    private NewsCollectScheduler alertScheduler;   // 관심목록 알림 스캐너

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        // 1) 수집 스케줄러: 자동 수집이 켜져 있을 때만 가동 (기본은 수동 버튼 → OFF)
        try {
            boolean auto = "Y".equalsIgnoreCase(SettingsService.get("collect.auto.enabled", "N"));
            if (auto) {
                int keywords = SettingsService.getKeywords().size();
                int limit  = SettingsService.getInt("daily.quota.limit", 25000);
                double safe = SettingsService.getDouble("daily.quota.safe.ratio", 0.8);
                QuotaGuard guard = new QuotaGuard(limit, safe);
                // CollectJob은 1회 실행당 collect.max.per.run(기본 100) 키워드만 rotate 처리하므로,
                // 키워드 전체 기준의 dynamicIntervalSec는 과도하게 길다. collect.interval.sec로 오버라이드.
                int interval = SettingsService.getInt("collect.interval.sec", guard.dynamicIntervalSec(keywords));

                scheduler = new NewsCollectScheduler(new CollectJob(), "news-collect");
                scheduler.start(interval);
                sce.getServletContext().log("[NewsSignal] collect scheduler started, interval=" + interval + "s");
            } else {
                sce.getServletContext().log("[NewsSignal] auto-collect OFF (manual button mode)");
            }
        } catch (Exception e) {
            sce.getServletContext().log("[NewsSignal] collect init failed: " + e.getMessage());
        }

        // 2) 분석 스케줄러: 수집과 분리되어 항상 가동(기본 ON). DB에 적재된 미분석
        //    그룹을 독립 주기로 소화하므로, 수집이 수동 버튼이어도 적재분은 자동 분석된다.
        try {
            boolean analyzeAuto = "Y".equalsIgnoreCase(SettingsService.get("analyze.auto.enabled", "Y"));
            if (analyzeAuto) {
                int analyzeInterval = SettingsService.getInt("analyze.interval.sec", 60);
                analyzeScheduler = new NewsCollectScheduler(new AnalyzeJob(), "news-analyze");
                analyzeScheduler.start(analyzeInterval);
                sce.getServletContext().log("[NewsSignal] analyze scheduler started, interval=" + analyzeInterval + "s");
            } else {
                sce.getServletContext().log("[NewsSignal] auto-analyze OFF");
            }
        } catch (Exception e) {
            sce.getServletContext().log("[NewsSignal] analyze init failed: " + e.getMessage());
        }

        // 3) 알림 스캐너: 관심 키워드/종목 매칭 + 급변 감지 (인앱 알림). 사용자 없으면 즉시 스킵.
        try {
            int alertInterval = SettingsService.getInt("alert.interval.sec", 120);
            alertScheduler = new NewsCollectScheduler(new AlertScannerJob(), "alert-scan");
            alertScheduler.start(alertInterval);
            sce.getServletContext().log("[NewsSignal] alert scanner started, interval=" + alertInterval + "s");
        } catch (Exception e) {
            sce.getServletContext().log("[NewsSignal] alert init failed: " + e.getMessage());
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        // 단계 1: 수집 스케줄러 종료
        try {
            if (scheduler != null) scheduler.shutdown();
        } catch (Exception e) {
            sce.getServletContext().log("[NewsSignal] scheduler shutdown error: " + e.getMessage());
        }
        // 단계 2: 분석 스케줄러 종료
        try {
            if (analyzeScheduler != null) analyzeScheduler.shutdown();
        } catch (Exception e) {
            sce.getServletContext().log("[NewsSignal] analyze scheduler shutdown error: " + e.getMessage());
        }
        // 단계 2-1: 알림 스케줄러 종료
        try {
            if (alertScheduler != null) alertScheduler.shutdown();
        } catch (Exception e) {
            sce.getServletContext().log("[NewsSignal] alert scheduler shutdown error: " + e.getMessage());
        }
        // 단계 3: DB 풀 종료 (스케줄러 실패와 무관하게 별도 try)
        try {
            Db.shutdown();
        } catch (Exception e) {
            sce.getServletContext().log("[NewsSignal] db shutdown error: " + e.getMessage());
        }
    }
}
