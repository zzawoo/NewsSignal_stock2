package com.newssignal.collector;

import com.newssignal.common.Db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;

/**
 * 네이버 API 일일 쿼터 가드 (계획서 2장 핵심).
 * - 일일 한도 25,000회. 안전계수(기본 0.8) 초과 시 호출 차단.
 * - api_quota_daily 테이블에 일자별 호출수를 원자적으로 누적.
 */
public class QuotaGuard {

    private final int dailyLimit;
    private final double safeRatio;

    public QuotaGuard(int dailyLimit, double safeRatio) {
        this.dailyLimit = dailyLimit;
        this.safeRatio = safeRatio;
    }

    /** 오늘 호출 가능 여부 (안전계수 적용) */
    public boolean canCall(int wantCalls) {
        int used = todayCount();
        int safeMax = (int) (dailyLimit * safeRatio);
        return used + wantCalls <= safeMax;
    }

    /** 호출 후 카운트 누적 (UPSERT) */
    public void record(int calls) {
        String sql = "INSERT INTO api_quota_daily (quota_date, call_count) VALUES (?, ?) "
                   + "ON DUPLICATE KEY UPDATE call_count = call_count + VALUES(call_count)";
        try (Connection c = Db.conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, LocalDate.now());
            ps.setInt(2, calls);
            ps.executeUpdate();
        } catch (Exception e) {
            // 로깅만 (수집 중단시키지 않음)
            System.err.println("[QuotaGuard] record failed: " + e.getMessage());
        }
    }

    public int todayCount() {
        String sql = "SELECT call_count FROM api_quota_daily WHERE quota_date = ?";
        try (Connection c = Db.conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, LocalDate.now());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            System.err.println("[QuotaGuard] count failed: " + e.getMessage());
        }
        return 0;
    }

    /**
     * 동적 수집 주기 산정 (계획서 2.2).
     * intervalSec = 86400 / (가용호출수 / 키워드수), 하한 12초.
     */
    public int dynamicIntervalSec(int keywordCount) {
        if (keywordCount <= 0) keywordCount = 1;
        int available = (int) (dailyLimit * safeRatio);
        int perKeyword = Math.max(1, available / keywordCount);
        int interval = 86400 / perKeyword;
        return Math.max(12, interval);
    }
}
