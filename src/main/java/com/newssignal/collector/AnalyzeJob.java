package com.newssignal.collector;

import com.newssignal.analyzer.AnalyzeService;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 분석 1회 실행 단위 (수집과 분리된 독립 스케줄).
 * DB에 적재된 미분석 유사도 그룹(analyzed_yn='N')을 LLM으로 분석/요약한다.
 * 수집(CollectJob)과 분리되어 있어, 수집은 LLM 속도/쿼터에 막히지 않고
 * 분석은 자기 주기·RPD 예산 안에서 백로그를 천천히 소화한다.
 */
public class AnalyzeJob implements Runnable {

    public static final AtomicBoolean isRunning = new AtomicBoolean(false);

    @Override
    public void run() {
        // 동시 실행 방지 (이전 실행이 아직 진행 중이면 스킵)
        if (!isRunning.compareAndSet(false, true)) {
            System.out.println("[AnalyzeJob] Already running. Skipping this run.");
            return;
        }
        try {
            new AnalyzeService().analyzeUnanalyzedGroups();
        } catch (Exception e) {
            System.err.println("[AnalyzeJob] analysis failed: " + e.getMessage());
        } finally {
            isRunning.set(false);
        }
    }
}
