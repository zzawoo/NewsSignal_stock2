-- 금/은 최종 정리: 붙여쓴 시세 표현(금값/귀금속/금괴/골드뱅킹, 은값/은괴)만 보존, 나머지 삭제.
-- ("은 선물"이 "싶은 선물"에 매칭되는 등 띄어쓰기 표현은 거짓양성 → 제외). + related_sectors 재생성.
SELECT '[before]' AS step;
SELECT s.sector_name, COUNT(*) AS cnt FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id
WHERE s.sector_name IN ('금','은') GROUP BY s.sector_name;

DELETE m FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id JOIN news_similarity_group g ON g.id=m.group_id
WHERE s.sector_name='금' AND g.group_title NOT REGEXP '금값|귀금속|금괴|골드뱅킹';

DELETE m FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id JOIN news_similarity_group g ON g.id=m.group_id
WHERE s.sector_name='은' AND g.group_title NOT REGEXP '은값|은괴';

UPDATE news_similarity_group g
LEFT JOIN (SELECT m.group_id, GROUP_CONCAT(s.sector_name) AS secs
           FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id GROUP BY m.group_id) x
  ON x.group_id=g.id
SET g.related_sectors = COALESCE(x.secs,'');

SELECT '[after]' AS step;
SELECT s.sector_name, COUNT(*) AS cnt FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id
WHERE s.sector_name IN ('금','은') GROUP BY s.sector_name;
