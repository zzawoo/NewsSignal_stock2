SELECT 'id', 'title', 'description', 'original_link', 'naver_link', 'press', 'pub_date', 'source_type', 'content_hash', 'similarity_group_id', 'duplicate_yn', 'collected_at' 
UNION ALL 
SELECT IFNULL(id, ''), IFNULL(REPLACE(REPLACE(title, '\r', ''), '\n', ' '), ''), IFNULL(REPLACE(REPLACE(description, '\r', ''), '\n', ' '), ''), IFNULL(original_link, ''), IFNULL(naver_link, ''), IFNULL(press, ''), IFNULL(pub_date, ''), IFNULL(source_type, ''), IFNULL(content_hash, ''), IFNULL(similarity_group_id, ''), IFNULL(duplicate_yn, ''), IFNULL(collected_at, '') 
INTO OUTFILE 'C:/project/NewsSignal_stock2/news_articles.csv' 
CHARACTER SET utf8mb4 
FIELDS TERMINATED BY ',' 
ENCLOSED BY '"' 
ESCAPED BY '"' 
LINES TERMINATED BY '\r\n' 
FROM news_articles;

SELECT 'id', 'group_title', 'group_summary', 'normalized_title', 'representative_news_id', 'good_bad_type', 'impact_score', 'related_sectors', 'duplicate_count', 'analyzed_yn', 'first_collected_at', 'last_collected_at', 'created_at', 'updated_at'
UNION ALL
SELECT IFNULL(id, ''), IFNULL(REPLACE(REPLACE(group_title, '\r', ''), '\n', ' '), ''), IFNULL(REPLACE(REPLACE(group_summary, '\r', ''), '\n', ' '), ''), IFNULL(normalized_title, ''), IFNULL(representative_news_id, ''), IFNULL(good_bad_type, ''), IFNULL(impact_score, ''), IFNULL(related_sectors, ''), IFNULL(duplicate_count, ''), IFNULL(analyzed_yn, ''), IFNULL(first_collected_at, ''), IFNULL(last_collected_at, ''), IFNULL(created_at, ''), IFNULL(updated_at, '')
INTO OUTFILE 'C:/project/NewsSignal_stock2/news_similarity_group.csv'
CHARACTER SET utf8mb4 
FIELDS TERMINATED BY ',' 
ENCLOSED BY '"' 
ESCAPED BY '"' 
LINES TERMINATED BY '\r\n' 
FROM news_similarity_group;

SELECT 'id', 'group_id', 'news_id', 'summary_short', 'summary_detail', 'good_bad_type', 'impact_score', 'impact_reason', 'risk_factor', 'sector_keywords', 'confidence_score', 'analyzed_at'
UNION ALL
SELECT IFNULL(id, ''), IFNULL(group_id, ''), IFNULL(news_id, ''), IFNULL(REPLACE(REPLACE(summary_short, '\r', ''), '\n', ' '), ''), IFNULL(REPLACE(REPLACE(summary_detail, '\r', ''), '\n', ' '), ''), IFNULL(good_bad_type, ''), IFNULL(impact_score, ''), IFNULL(REPLACE(REPLACE(impact_reason, '\r', ''), '\n', ' '), ''), IFNULL(REPLACE(REPLACE(risk_factor, '\r', ''), '\n', ' '), ''), IFNULL(sector_keywords, ''), IFNULL(confidence_score, ''), IFNULL(analyzed_at, '')
INTO OUTFILE 'C:/project/NewsSignal_stock2/news_ai_analysis.csv'
CHARACTER SET utf8mb4 
FIELDS TERMINATED BY ',' 
ENCLOSED BY '"' 
ESCAPED BY '"' 
LINES TERMINATED BY '\r\n' 
FROM news_ai_analysis;
