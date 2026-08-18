package com.newssignal.analyzer;

import java.security.MessageDigest;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    /* ── TF-IDF 코사인 (의미 기반 그룹화 고도화) ──
       features()(단어+바이그램) 위에 IDF 가중 → 변별력 있는 용어가 유사도를 지배.
       Jaccard의 단순 공통비율보다 흔한 용어 영향을 낮춰 '같은 종목 다른 사건' 과병합을 줄임. */
    private static final double SMOOTH = 1.0;

    /** 코퍼스(제목 집합)로 IDF 산출. */
    public Map<String, Double> computeIdf(Collection<String> corpus) {
        Map<String, Integer> df = new HashMap<>();
        int n = 0;
        for (String t : corpus) {
            n++;
            for (String f : features(t)) df.merge(f, 1, Integer::sum);
        }
        double N = Math.max(1, n);
        Map<String, Double> idf = new HashMap<>();
        for (Map.Entry<String, Integer> e : df.entrySet())
            idf.put(e.getKey(), Math.log((N + SMOOTH) / (e.getValue() + SMOOTH)) + 1.0);
        return idf;
    }

    /** 제목의 TF-IDF 희소 벡터(이진 tf — 제목은 짧아 용어 중복 드묾). idf=null이면 모든 가중치 1. */
    public Map<String, Double> tfidfVector(String title, Map<String, Double> idf) {
        Map<String, Double> v = new HashMap<>();
        double dflt = Math.log(2.0) + 1.0; // 코퍼스에 없던 용어 = 변별력 높게
        for (String f : features(title)) {
            double w = (idf == null) ? 1.0 : idf.getOrDefault(f, dflt);
            if (w > 0) v.put(f, w);
        }
        return v;
    }

    public double cosine(Map<String, Double> a, Map<String, Double> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        Map<String, Double> small = (a.size() <= b.size()) ? a : b;
        Map<String, Double> large = (small == a) ? b : a;
        double dot = 0;
        for (Map.Entry<String, Double> e : small.entrySet()) {
            Double w = large.get(e.getKey());
            if (w != null) dot += e.getValue() * w;
        }
        double na = norm(a), nb = norm(b);
        return (na == 0 || nb == 0) ? 0 : dot / (na * nb);
    }

    private double norm(Map<String, Double> v) {
        double s = 0; for (double w : v.values()) s += w * w; return Math.sqrt(s);
    }

    /** TF-IDF 벡터 기반 같은 이슈 판정. isSameIssue와 동일한 결합 규칙(섹터 공유 시 느슨, 아니면 엄격). */
    public boolean isSameIssueVec(Map<String, Double> aVec, List<String> aSectors,
                                  Map<String, Double> bVec, List<String> bSectors,
                                  double simWithSector, double simTitleOnly) {
        double sim = cosine(aVec, bVec);
        boolean shared = aSectors != null && bSectors != null
                && aSectors.stream().anyMatch(bSectors::contains);
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
