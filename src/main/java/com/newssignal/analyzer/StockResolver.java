package com.newssignal.analyzer;

import com.newssignal.common.Db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 기사 텍스트에서 stock_master(약 2,700개 상장 종목)에 등재된 종목명을 직접 매칭하여
 * 종목코드를 해석한다. LLM이 종목코드를 누락/오기하거나, 목업 분석이 소수 종목만
 * 하드코딩하는 한계를 보완하기 위한 보조 추출기.
 *
 * 오탐(false positive) 억제를 위해 종목명 길이가 {@link #MIN_NAME_LEN}자 이상인
 * 경우에만 substring 매칭한다. (2자 종목명: DL, CJ, 동원 등은 일반 단어와 충돌 위험)
 */
public final class StockResolver {

    /** 이 길이 미만의 종목명은 오탐 위험이 커서 매칭 대상에서 제외한다. */
    private static final int MIN_NAME_LEN = 3;

    /**
     * 영어 일반 단어와 충돌하는 알파벳 티커. 단독 단어로 등장해도 오탐이므로 매칭 제외.
     * 예: "NEW" → 'NEW 스타필드', 'WENEW'.
     */
    private static final Set<String> ASCII_BLOCKLIST = new HashSet<>(Arrays.asList("NEW"));

    /** 한글 등 비(非)알파벳 종목명: substring 매칭. name -> code. */
    private static volatile Map<String, String> NAME_TO_CODE;
    /** 순수 알파벳/숫자 종목명(예: STX, ISC): 단어경계(\b) 정규식 매칭. */
    private static volatile Map<Pattern, String> ASCII_PATTERNS;

    private StockResolver() {}

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 0x7F) return false;
        }
        return true;
    }

    /**
     * stock_master를 1회 로드하여 캐시한다. 종목 마스터는 자주 바뀌지 않으므로
     * 프로세스 생명주기 동안 캐시해도 무방하다.
     */
    private static Map<String, String> cache() {
        Map<String, String> local = NAME_TO_CODE;
        if (local != null) return local;
        synchronized (StockResolver.class) {
            if (NAME_TO_CODE != null) return NAME_TO_CODE;
            // 긴 이름 우선 매칭을 위해 이름 길이 내림차순으로 적재
            String sql = "SELECT stock_code, stock_name FROM stock_master "
                       + "WHERE stock_name IS NOT NULL AND CHAR_LENGTH(stock_name) >= " + MIN_NAME_LEN + " "
                       + "ORDER BY CHAR_LENGTH(stock_name) DESC";
            Map<String, String> map = new LinkedHashMap<>();
            Map<Pattern, String> ascii = new LinkedHashMap<>();
            try (Connection conn = Db.conn();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("stock_name");
                    String code = rs.getString("stock_code");
                    if (name == null) continue;
                    name = name.trim();
                    if (name.length() < MIN_NAME_LEN) continue;
                    if (isAscii(name)) {
                        // 알파벳/숫자 티커: 영어 단어 충돌 방지 위해 단어경계 매칭 + 블록리스트
                        if (ASCII_BLOCKLIST.contains(name)) continue;
                        ascii.put(Pattern.compile("\\b" + Pattern.quote(name) + "\\b"), code);
                    } else {
                        map.putIfAbsent(name, code);
                    }
                }
            } catch (Exception e) {
                System.err.println("[StockResolver] Failed to load stock_master: " + e.getMessage());
            }
            ASCII_PATTERNS = ascii;
            NAME_TO_CODE = map;
            System.out.println("[StockResolver] Loaded " + map.size() + " name matchers + "
                    + ascii.size() + " ASCII-ticker matchers (>= " + MIN_NAME_LEN + " chars).");
            return map;
        }
    }

    /** 캐시 무효화(테스트/리로드용). */
    public static synchronized void invalidate() {
        NAME_TO_CODE = null;
        ASCII_PATTERNS = null;
    }

    /**
     * 텍스트에 등장하는 stock_master 종목명을 찾아 종목코드 목록을 반환한다.
     * 한 종목명이 다른 종목명의 부분문자열인 경우(예: "삼성전자" ⊃ "삼성")는
     * 긴 이름 우선 적재 + 길이 하한으로 상당 부분 걸러진다.
     */
    public static List<String> codesFor(String text) {
        if (text == null || text.isEmpty()) return new ArrayList<>();
        Map<String, String> map = cache();
        Set<String> codes = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (text.contains(e.getKey())) {
                codes.add(e.getValue());
            }
        }
        for (Map.Entry<Pattern, String> e : ASCII_PATTERNS.entrySet()) {
            if (e.getKey().matcher(text).find()) {
                codes.add(e.getValue());
            }
        }
        return new ArrayList<>(codes);
    }
}
