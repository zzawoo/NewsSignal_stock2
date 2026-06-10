package com.newssignal.analyzer;

import com.newssignal.common.Db;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 유사도 그룹의 기사 본문들을 종합하여 2~3줄 요약문을 생성합니다.
 * 외부 AI API 없이 규칙 기반으로 핵심 문장을 추출합니다.
 */
public class GroupSummaryService {

    /**
     * 특정 그룹의 요약문을 생성해 DB에 저장합니다.
     */
    public void generateAndSave(long groupId) {
        String summary = generate(groupId);
        if (summary == null || summary.isEmpty()) return;
        try (Connection conn = Db.conn();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE news_similarity_group SET group_summary = ? WHERE id = ?")) {
            ps.setString(1, summary);
            ps.setLong(2, groupId);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[GroupSummaryService] DB 저장 실패: " + e.getMessage());
        }
    }

    /**
     * 그룹 내 상위 기사들의 description을 수집해 요약문을 반환합니다.
     */
    public String generate(long groupId) {
        List<String> descriptions = fetchDescriptions(groupId, 5);
        if (descriptions.isEmpty()) return null;
        return buildSummary(descriptions);
    }

    // ── 내부 유틸 ─────────────────────────────────────────────────────────────

    private List<String> fetchDescriptions(long groupId, int limit) {
        List<String> list = new ArrayList<>();
        String sql = "SELECT description FROM news_articles "
                   + "WHERE similarity_group_id = ? AND description IS NOT NULL AND description != '' "
                   + "ORDER BY duplicate_yn ASC, pub_date DESC LIMIT ?";
        try (Connection conn = Db.conn();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, groupId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String desc = rs.getString("description");
                    if (desc != null && !desc.trim().isEmpty()) {
                        list.add(desc.trim());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[GroupSummaryService] fetchDescriptions 실패: " + e.getMessage());
        }
        return list;
    }

    /**
     * 여러 기사의 description에서 핵심 문장을 추출해 2~3줄 요약 생성.
     * - 각 description을 마침표 기준으로 분리
     * - 중복 제거 후 상위 3문장 선택
     * - 총 160자를 넘지 않도록 잘라냄
     */
    private String buildSummary(List<String> descriptions) {
        // 1. 모든 description에서 문장 분리
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> sentences = new ArrayList<>();

        for (String desc : descriptions) {
            // 마침표·줄바꿈 기준으로 분리
            String[] parts = desc.split("[.。\\n]+");
            for (String p : parts) {
                String s = p.trim();
                if (s.length() < 15) continue; // 너무 짧은 단편 제외
                // 유사 중복 제거 (첫 20자 기준)
                String key = s.length() > 20 ? s.substring(0, 20) : s;
                if (seen.add(key)) {
                    sentences.add(s);
                }
                if (sentences.size() >= 4) break;
            }
            if (sentences.size() >= 4) break;
        }

        if (sentences.isEmpty()) {
            // 문장 분리 실패 시 첫 description의 앞부분만 사용
            String fallback = descriptions.get(0);
            return fallback.length() > 160 ? fallback.substring(0, 157) + "…" : fallback;
        }

        // 2. 상위 3문장을 선택해 이어붙임
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String s : sentences) {
            if (count >= 3) break;
            if (sb.length() > 0) sb.append(" ");
            // 한 문장이 너무 길면 잘라냄
            if (s.length() > 80) s = s.substring(0, 77) + "…";
            sb.append(s).append(".");
            count++;
        }

        String result = sb.toString().trim();
        // 최종 160자 상한
        if (result.length() > 160) result = result.substring(0, 157) + "…";
        return result;
    }
}
