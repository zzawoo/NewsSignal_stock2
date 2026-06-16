-- 금/은 잔여 거짓 양성 정리(골드만삭스/골드베르크/아담실버/실버타운 등) + related_sectors 재생성.
-- 코드(extractSectorsFromText)도 "골드"/"실버" 제거하도록 수정 완료.
SELECT '[before] 금/은 매핑 수' AS step;
SELECT s.sector_name, COUNT(*) AS cnt FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id
WHERE s.sector_name IN ('금','은') GROUP BY s.sector_name;

DELETE m FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id JOIN news_similarity_group g ON g.id=m.group_id
WHERE s.sector_name='금'
  AND g.group_title NOT REGEXP '금값|금 값|금 가격|금 시세|금 선물|금 현물|국제 금|귀금속|금괴|골드뱅킹';

DELETE m FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id JOIN news_similarity_group g ON g.id=m.group_id
WHERE s.sector_name='은'
  AND g.group_title NOT REGEXP '은값|은 값|은 가격|은 시세|은 선물|은 현물|국제 은';

-- related_sectors(주요 이슈 태그 출처)를 정리된 news_sector_map에서 재생성
UPDATE news_similarity_group g
LEFT JOIN (SELECT m.group_id, GROUP_CONCAT(s.sector_name) AS secs
           FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id GROUP BY m.group_id) x
  ON x.group_id=g.id
SET g.related_sectors = COALESCE(x.secs,'');

SELECT '[after] 금/은 매핑 수' AS step;
SELECT s.sector_name, COUNT(*) AS cnt FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id
WHERE s.sector_name IN ('금','은') GROUP BY s.sector_name;
