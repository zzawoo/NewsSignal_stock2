-- ============================================================
--  2차 섹터 오매칭 정리: 지수/거시경제(비추출 라벨), 조선/사료/통신/기계(문맥 없음),
--  + LLM이 만든 쓰레기 거시 섹터(공백/콤마 포함 이름).
--  코드(ArticleService.extractSectorsFromText, AnalyzeService.getOrCreateMacroSector)는 수정 완료.
--  적용: mysql ... newssignal < db/cleanup_ambiguous_sectors2.sql  (UTF-8)
-- ============================================================

SELECT '[before]' AS step;
SELECT s.sector_name, COUNT(*) AS cnt FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id
WHERE s.sector_name IN ('지수','거시경제','조선','사료','통신','기계') GROUP BY s.sector_name ORDER BY cnt DESC;

-- 1) 비추출 라벨(지수/거시경제) 매핑 전부 삭제 + 해당 sector_master 행 삭제
DELETE m FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id WHERE s.sector_name IN ('지수','거시경제');
DELETE FROM sector_master WHERE sector_name IN ('지수','거시경제');

-- 2) 조선: 조선업/해양 문맥 없는 제목의 매핑 삭제
DELETE m FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id JOIN news_similarity_group g ON g.id=m.group_id
WHERE s.sector_name='조선'
  AND g.group_title NOT REGEXP '조선업|조선소|조선해양|조선사|조선3사|케이조선|대우조선|현대미포|삼성중공업|한화오션|HD현대중공업|선박 수주|수주잔량|LNG운반선|LNG선|컨테이너선|유조선|상선|해운';

-- 3) 사료: 축산·사료 문맥 없는 제목의 매핑 삭제
DELETE m FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id JOIN news_similarity_group g ON g.id=m.group_id
WHERE s.sector_name='사료'
  AND g.group_title NOT REGEXP '배합사료|사료값|사료 가격|사료업체|농협사료|축산|양돈|양계|가축|펫푸드|반려동물|비료|농업';

-- 4) 통신: 텔레콤 문맥 없는 제목의 매핑 삭제
DELETE m FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id JOIN news_similarity_group g ON g.id=m.group_id
WHERE s.sector_name='통신'
  AND g.group_title NOT REGEXP '이동통신|통신3사|통신주|5G|6G|알뜰폰|통신요금|통신비|통신망|통신장비|SK텔레콤|LG유플러스|유플러스|이통사';

-- 5) 기계: 기계산업 문맥 없는 제목의 매핑 삭제
DELETE m FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id JOIN news_similarity_group g ON g.id=m.group_id
WHERE s.sector_name='기계'
  AND g.group_title NOT REGEXP '기계산업|공작기계|산업기계|건설기계|기계장비|기계설비|정밀기계|농기계|기계 수주';

-- 6) LLM 쓰레기 거시 섹터(이름에 공백/콤마 포함) 매핑 + sector_master 삭제
DELETE m FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id
WHERE s.sector_name LIKE '% %' OR s.sector_name LIKE '%,%';
DELETE FROM sector_master WHERE sector_name LIKE '% %' OR sector_name LIKE '%,%';

SELECT '[after]' AS step;
SELECT s.sector_name, COUNT(*) AS cnt FROM news_sector_map m JOIN sector_master s ON s.id=m.sector_id
WHERE s.sector_name IN ('지수','거시경제','조선','사료','통신','기계') GROUP BY s.sector_name ORDER BY cnt DESC;
SELECT '[남은 sector_master 수]' AS step, COUNT(*) AS sectors FROM sector_master;
