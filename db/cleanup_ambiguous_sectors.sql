-- ============================================================
--  금/은/유가 섹터 거짓 양성 매핑 정리
--  원인: extractSectorsFromText가 단일/짧은 글자("금","은","유가")를
--        contains로 매칭해 은행/금융/유가증권 등 무관 기사까지 매핑.
--  코드는 ArticleService에서 문맥 매칭으로 수정함. 이 스크립트는 기존 오염분 정리.
--  방침: 대표 제목(group_title)에 실제 상품/시세 문맥이 없는 매핑만 삭제(진짜 시세 뉴스는 보존).
--  적용: mysql ... newssignal < db/cleanup_ambiguous_sectors.sql   (UTF-8)
-- ============================================================

SELECT '[before] 금/은/유가 매핑 수' AS step;
SELECT s.sector_name, COUNT(*) AS cnt
FROM news_sector_map m JOIN sector_master s ON s.id = m.sector_id
WHERE s.sector_name IN ('금','은','유가')
GROUP BY s.sector_name;

-- 은: 상품 문맥 없는 제목의 매핑 삭제
DELETE m FROM news_sector_map m
JOIN sector_master s ON s.id = m.sector_id
JOIN news_similarity_group g ON g.id = m.group_id
WHERE s.sector_name = '은'
  AND g.group_title NOT REGEXP '은값|은 값|은 가격|은 시세|은 선물|은 현물|실버';

-- 금: 상품 문맥 없는 제목의 매핑 삭제
DELETE m FROM news_sector_map m
JOIN sector_master s ON s.id = m.sector_id
JOIN news_similarity_group g ON g.id = m.group_id
WHERE s.sector_name = '금'
  AND g.group_title NOT REGEXP '금값|금 값|금 가격|금 시세|금 선물|금 현물|귀금속|골드|금괴';

-- 유가: 원유 시세 문맥 없는 제목의 매핑 삭제(유가증권/유가족 등)
DELETE m FROM news_sector_map m
JOIN sector_master s ON s.id = m.sector_id
JOIN news_similarity_group g ON g.id = m.group_id
WHERE s.sector_name = '유가'
  AND g.group_title NOT REGEXP '국제유가|국제 유가|유가 상승|유가 하락|유가 급등|유가 급락|유가 반등|원유|WTI|브렌트|두바이유';

SELECT '[after] 금/은/유가 매핑 수' AS step;
SELECT s.sector_name, COUNT(*) AS cnt
FROM news_sector_map m JOIN sector_master s ON s.id = m.sector_id
WHERE s.sector_name IN ('금','은','유가')
GROUP BY s.sector_name;
