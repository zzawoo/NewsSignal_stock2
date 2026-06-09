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

    private NewsCollectScheduler scheduler;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        try {
            // 자동 수집이 켜져 있을 때만 가동 (기본은 수동 버튼 → OFF)
            boolean auto = "Y".equalsIgnoreCase(SettingsService.get("collect.auto.enabled", "N"));
            if (auto) {
                int keywords = SettingsService.getKeywords().size();
                int limit  = SettingsService.getInt("daily.quota.limit", 25000);
                double safe = SettingsService.getDouble("daily.quota.safe.ratio", 0.8);
                QuotaGuard guard = new QuotaGuard(limit, safe);
                int interval = guard.dynamicIntervalSec(keywords);

                scheduler = new NewsCollectScheduler(new CollectJob());
                scheduler.start(interval);
                sce.getServletContext().log("[NewsSignal] scheduler started, interval=" + interval + "s");
            } else {
                sce.getServletContext().log("[NewsSignal] auto-collect OFF (manual button mode)");
            }
        } catch (Exception e) {
            sce.getServletContext().log("[NewsSignal] init failed: " + e.getMessage());
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        // 단계 1: 스케줄러 종료
        try {
            if (scheduler != null) scheduler.shutdown();
        } catch (Exception e) {
            sce.getServletContext().log("[NewsSignal] scheduler shutdown error: " + e.getMessage());
        }
        // 단계 2: DB 풀 종료 (스케줄러 실패와 무관하게 별도 try)
        try {
            Db.shutdown();
        } catch (Exception e) {
            sce.getServletContext().log("[NewsSignal] db shutdown error: " + e.getMessage());
        }
    }
}
