package com.newssignal.collector;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 뉴스 수집 스케줄러 (계획서 4.3).
 * Tomcat 메모리 누수/종료 차단 함정을 모두 방지:
 *  - ScheduledThreadPoolExecutor 직접 사용
 *  - setRemoveOnCancelPolicy(true): 취소된 task 큐 잔류 방지(기본 false)
 *  - daemon 스레드: non-daemon 스레드의 Tomcat 종료 차단 회피
 *  - scheduleWithFixedDelay: 수집이 주기보다 길어져도 task가 밀려 쌓이지 않음
 *  - run() 최상단 try-catch: task 예외로 스케줄 자체가 멈추지 않도록
 *  - shutdown(): shutdown → awaitTermination → shutdownNow 순
 */
public class NewsCollectScheduler {

    private final ScheduledThreadPoolExecutor exec;
    private final Runnable job;
    private volatile boolean running = false;

    public NewsCollectScheduler(Runnable job) {
        this(job, "news-collect");
    }

    public NewsCollectScheduler(Runnable job, final String namePrefix) {
        this.job = job;
        ThreadFactory daemonFactory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, namePrefix + "-" + seq.getAndIncrement());
                t.setDaemon(true);           // ★ Tomcat 종료 차단 방지
                return t;
            }
        };
        this.exec = new ScheduledThreadPoolExecutor(1, daemonFactory);
        this.exec.setRemoveOnCancelPolicy(true);  // ★ 누수 방지
    }

    /** 주기 수집 시작 */
    public synchronized void start(long intervalSec) {
        if (running) return;
        running = true;
        exec.scheduleWithFixedDelay(() -> {
            try {
                job.run();
            } catch (Throwable t) {                // ★ 예외 흡수, 스케줄 유지
                System.err.println("[Scheduler] job error: " + t.getMessage());
            }
        }, 0, Math.max(12, intervalSec), TimeUnit.SECONDS);
    }

    public synchronized boolean isRunning() { return running; }

    /** 안전 종료 (계획서 4.3) */
    public synchronized void shutdown() {
        running = false;
        exec.shutdown();
        try {
            if (!exec.awaitTermination(10, TimeUnit.SECONDS)) {
                exec.shutdownNow();
            }
        } catch (InterruptedException e) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
