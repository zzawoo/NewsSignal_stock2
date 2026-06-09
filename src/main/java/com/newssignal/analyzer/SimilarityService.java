package com.newssignal.analyzer;

import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 뉴스 유사도 서비스 (계획서 5.1).
 * - 정규화: HTML/특수문자/불용어 제거.
 * - 단어 토큰 + 글자 바이그램 혼합 Jaccard (한국어 표기 차이 흡수).
 *   삼성전자/삼성, HBM3E/HBM 처럼 표기가 달라도 매칭되도록 보강.
 * - 결합 판정: (제목유사 >= 0.12 AND 섹터 공유) OR (제목유사 >= 0.30).
 *   임계값은 collect_settings로 빼서 실데이터 튜닝 가능(계획서 5.1 주석).
 */
public class SimilarityService {

    private static final Set<String> STOP = new HashSet<>();
    static {
        for (String w : new String[]{
            "기대","기대감","확대","전망","우려","강세","약세","부각","지속",
            "임박","관련","이번","오늘","종목","업종","분기","대비","상향","하향"})
            STOP.add(w);
    }

    private final double simWithSector;
    private final double simTitleOnly;

    public SimilarityService(double simWithSector, double simTitleOnly) {
        this.simWithSector = simWithSector;
        this.simTitleOnly = simTitleOnly;
    }
    public SimilarityService() { this(0.12, 0.30); }

    public String normalize(String t) {
        if (t == null) return "";
        return t.replaceAll("<[^>]+>", "")
                .replaceAll("[^\\uac00-\\ud7a3a-zA-Z0-9 ]", " ")
                .replaceAll("\\s+", " ").trim().toLowerCase();
    }

    /** 단어 토큰 + 바이그램 특징 집합 */
    public Set<String> features(String t) {
        String n = normalize(t);
        Set<String> set = new HashSet<>();
        for (String w : n.split(" "))
            if (w.length() > 1 && !STOP.contains(w)) set.add(w);
        String compact = n.replace(" ", "");
        for (int i = 0; i < compact.length() - 1; i++)
            set.add(compact.substring(i, i + 2));
        return set;
    }

    public double jaccard(String a, String b) {
        Set<String> A = features(a), B = features(b);
        if (A.isEmpty() && B.isEmpty()) return 0;
        int inter = 0;
        for (String x : A) if (B.contains(x)) inter++;
        Set<String> uni = new HashSet<>(A); uni.addAll(B);
        return uni.isEmpty() ? 0 : (double) inter / uni.size();
    }

    /** 두 뉴스가 같은 이슈 그룹인지 (제목 유사 + 섹터 공유 결합) */
    public boolean isSameIssue(String titleA, List<String> sectorsA,
                               String titleB, List<String> sectorsB) {
        double sim = jaccard(titleA, titleB);
        boolean shared = sectorsA != null && sectorsB != null
                && sectorsA.stream().anyMatch(sectorsB::contains);
        return (sim >= simWithSector && shared) || sim >= simTitleOnly;
    }

    /** 완전중복 차단용 content_hash (정규화 제목 + 언론사) */
    public String contentHash(String title, String press) {
        String base = normalize(title) + "|" + (press == null ? "" : press);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(base.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString(); // 24 chars, 191 한도 내
        } catch (Exception e) {
            return Integer.toHexString(base.hashCode());
        }
    }
}
