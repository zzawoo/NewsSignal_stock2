package com.newssignal.collector;

import java.time.LocalDateTime;
import java.util.List;

/** 모든 수집기가 반환하는 표준 뉴스 DTO (계획서 3.1) */
public class NewsArticleDTO {
    public Long   id;
    public String title;
    public String description;
    public String originalLink;
    public String naverLink;
    public String press;
    public LocalDateTime pubDate;
    public String sourceType;     // NAVER_API / JSOUP
    public String contentHash;
    public List<String> sectorKeywords;

    public NewsArticleDTO() {}

    public NewsArticleDTO(String title, String description, String originalLink,
                          String naverLink, LocalDateTime pubDate, String sourceType) {
        this.title = title;
        this.description = description;
        this.originalLink = originalLink;
        this.naverLink = naverLink;
        this.pubDate = pubDate;
        this.sourceType = sourceType;
    }
}
