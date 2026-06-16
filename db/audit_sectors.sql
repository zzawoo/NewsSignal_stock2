-- 의심 섹터들의 실제 매핑 제목 샘플 (거짓 양성 확인용)
SELECT '== 조선 ==' AS s;
SELECT LEFT(g.group_title,46) AS title FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id JOIN news_similarity_group g ON g.id=m.group_id WHERE s.sector_name='조선' ORDER BY RAND() LIMIT 6;
SELECT '== 지수 ==' AS s;
SELECT LEFT(g.group_title,46) AS title FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id JOIN news_similarity_group g ON g.id=m.group_id WHERE s.sector_name='지수' ORDER BY RAND() LIMIT 6;
SELECT '== 사료 ==' AS s;
SELECT LEFT(g.group_title,46) AS title FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id JOIN news_similarity_group g ON g.id=m.group_id WHERE s.sector_name='사료' ORDER BY RAND() LIMIT 6;
SELECT '== 건설 ==' AS s;
SELECT LEFT(g.group_title,46) AS title FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id JOIN news_similarity_group g ON g.id=m.group_id WHERE s.sector_name='건설' ORDER BY RAND() LIMIT 6;
SELECT '== 통신 ==' AS s;
SELECT LEFT(g.group_title,46) AS title FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id JOIN news_similarity_group g ON g.id=m.group_id WHERE s.sector_name='통신' ORDER BY RAND() LIMIT 6;
SELECT '== 기계 ==' AS s;
SELECT LEFT(g.group_title,46) AS title FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id JOIN news_similarity_group g ON g.id=m.group_id WHERE s.sector_name='기계' ORDER BY RAND() LIMIT 6;
SELECT '== 게임 ==' AS s;
SELECT LEFT(g.group_title,46) AS title FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id JOIN news_similarity_group g ON g.id=m.group_id WHERE s.sector_name='게임' ORDER BY RAND() LIMIT 6;
SELECT '== 원전 ==' AS s;
SELECT LEFT(g.group_title,46) AS title FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id JOIN news_similarity_group g ON g.id=m.group_id WHERE s.sector_name='원전' ORDER BY RAND() LIMIT 6;
