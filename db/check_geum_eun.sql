-- 태그(related_sectors)에 '금' 또는 '은'이 토큰으로 남은 3일치 분석 그룹 (FIND_IN_SET = 정확)
SELECT g.id, LEFT(g.group_title,42) AS title, g.related_sectors
FROM news_similarity_group g
WHERE g.analyzed_yn='Y' AND g.last_collected_at >= DATE_SUB(NOW(), INTERVAL 3 DAY)
  AND (FIND_IN_SET('금', g.related_sectors) > 0 OR FIND_IN_SET('은', g.related_sectors) > 0)
LIMIT 10;

SELECT '--- 금/은 토큰 보유 그룹 수 ---' AS x;
SELECT
  SUM(FIND_IN_SET('금', related_sectors) > 0) AS has_geum,
  SUM(FIND_IN_SET('은', related_sectors) > 0) AS has_eun
FROM news_similarity_group
WHERE analyzed_yn='Y' AND last_collected_at >= DATE_SUB(NOW(), INTERVAL 3 DAY);
