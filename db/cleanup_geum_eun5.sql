-- 금/은 news_sector_map 최종 정리: REGEXP가 한글 멀티바이트에서 불안정 → LIKE(부분일치, 신뢰)로 처리.
-- 붙여쓴 시세 표현 없는 제목의 금/은 매핑 삭제 후 related_sectors 재생성.
SELECT '[before]' AS step;
SELECT s.sector_name, COUNT(*) AS cnt FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id
WHERE s.sector_name IN ('금','은') GROUP BY s.sector_name;

DELETE m FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id JOIN news_similarity_group g ON g.id=m.group_id
WHERE s.sector_name='금'
  AND g.group_title NOT LIKE '%금값%' AND g.group_title NOT LIKE '%귀금속%'
  AND g.group_title NOT LIKE '%금괴%' AND g.group_title NOT LIKE '%골드뱅킹%';

DELETE m FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id JOIN news_similarity_group g ON g.id=m.group_id
WHERE s.sector_name='은'
  AND g.group_title NOT LIKE '%은값%' AND g.group_title NOT LIKE '%은괴%';

UPDATE news_similarity_group g
LEFT JOIN (SELECT m.group_id, GROUP_CONCAT(s.sector_name) AS secs
           FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id GROUP BY m.group_id) x
  ON x.group_id=g.id
SET g.related_sectors = COALESCE(x.secs,'');

SELECT '[after]' AS step;
SELECT s.sector_name, COUNT(*) AS cnt FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id
WHERE s.sector_name IN ('금','은') GROUP BY s.sector_name;
