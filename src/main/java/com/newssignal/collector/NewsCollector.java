package com.newssignal.collector;

import java.util.List;

/**
 * 뉴스 수집기 공통 인터페이스 (계획서 3.1).
 * 수집 방식 교체/추가에 대비해 추상화한다.
 */
public interface NewsCollector {

    /** 수집기 식별자 (로그용) */
    String name();

    /** 활성화 여부 (설정값 기반) */
    boolean isEnabled();

    /**
     * 키워드로 뉴스를 수집해 표준 DTO 목록을 반환.
     * @param keyword 검색 키워드
     * @param display 가져올 건수
     * @return 표준화된 뉴스 목록 (실패 시 빈 목록, 예외는 내부 처리/로깅)
     */
    List<NewsArticleDTO> collect(String keyword, int display);
}
