-- =====================================================================
-- 섹터 ↔ 종목 자동 매핑 (sector_stock_map)
-- stock_master.industry (KRX 표준 업종분류, 전 종목 채워짐) 기반.
-- 멱등(INSERT IGNORE): 여러 번 실행해도 중복 없이 누적만 됨.
-- 업종분류로 식별 불가한 테마(방산/원전/HBM/AI/게임/화장품)는
-- 큐레이션 시드(schema.sql)를 그대로 유지하고 여기서 건드리지 않음.
-- 한 종목이 여러 섹터에 속할 수 있음(예: 전자부품→반도체).
-- =====================================================================
USE newssignal;

-- 반도체 (KRX '반도체 제조업'만 사용)
--  · '전자부품 제조업'은 디스플레이(LG디스플레이)·PCB(코리아써키트)·커넥터·카메라모듈·
--    에너지소재(롯데에너지머티리얼즈) 등 비반도체 잡음이 커 제외.
--  · '반도체 제조업'으로 오분류된 태양광(HD현대에너지솔루션/신성이엔지)·디스플레이(일진디스플)는 NOT IN 제외.
--  · 반도체 장비·소재(원익IPS/한미반도체/이오테크닉스 등)는 schema.sql 큐레이션 시드로 보강됨.
INSERT IGNORE INTO sector_stock_map (sector_id, stock_code)
SELECT (SELECT id FROM sector_master WHERE sector_name='반도체'), stock_code
FROM stock_master
WHERE industry = '반도체 제조업'
  AND stock_name NOT IN ('HD현대에너지솔루션','신성이엔지','일진디스플');

-- 2차전지
INSERT IGNORE INTO sector_stock_map (sector_id, stock_code)
SELECT (SELECT id FROM sector_master WHERE sector_name='2차전지'), stock_code
FROM stock_master WHERE industry REGEXP '전지';

-- 자동차
INSERT IGNORE INTO sector_stock_map (sector_id, stock_code)
SELECT (SELECT id FROM sector_master WHERE sector_name='자동차'), stock_code
FROM stock_master WHERE industry REGEXP '자동차';

-- 바이오 (제약/의료/의약, 모호한 연구개발업 제외)
INSERT IGNORE INTO sector_stock_map (sector_id, stock_code)
SELECT (SELECT id FROM sector_master WHERE sector_name='바이오'), stock_code
FROM stock_master WHERE industry REGEXP '의약|의료용|의료 관련|생물';

-- 철강 (철강/비철금속/금속가공/금속주조/귀금속)
INSERT IGNORE INTO sector_stock_map (sector_id, stock_code)
SELECT (SELECT id FROM sector_master WHERE sector_name='철강'), stock_code
FROM stock_master WHERE industry REGEXP '철강|비철금속|금속 가공|구조용 금속|금속 주조|귀금속';

-- 화학 (화학/플라스틱/고무/화학섬유)
INSERT IGNORE INTO sector_stock_map (sector_id, stock_code)
SELECT (SELECT id FROM sector_master WHERE sector_name='화학'), stock_code
FROM stock_master WHERE industry REGEXP '화학|플라스틱|고무|화학섬유';

-- 건설 (건설/건축/토목/시멘트/비금속광물)
INSERT IGNORE INTO sector_stock_map (sector_id, stock_code)
SELECT (SELECT id FROM sector_master WHERE sector_name='건설'), stock_code
FROM stock_master WHERE industry REGEXP '건설|건축|토목|시멘트|비금속 광물';

-- 기계
INSERT IGNORE INTO sector_stock_map (sector_id, stock_code)
SELECT (SELECT id FROM sector_master WHERE sector_name='기계'), stock_code
FROM stock_master WHERE industry REGEXP '기계';

-- 통신 (전기통신업/통신·방송 장비/통신 공사)
INSERT IGNORE INTO sector_stock_map (sector_id, stock_code)
SELECT (SELECT id FROM sector_master WHERE sector_name='통신'), stock_code
FROM stock_master WHERE industry REGEXP '통신';

-- IT (소프트웨어/컴퓨터 프로그래밍/정보 서비스)
INSERT IGNORE INTO sector_stock_map (sector_id, stock_code)
SELECT (SELECT id FROM sector_master WHERE sector_name='IT'), stock_code
FROM stock_master WHERE industry REGEXP '소프트웨어|컴퓨터 프로그래밍|정보 서비스';

-- 음식료 (식품/음료/작물/도축) — 동물용 사료는 별도 '사료' 섹터이므로 제외('조제식품'의 '식품'에 잘못 매칭됨)
INSERT IGNORE INTO sector_stock_map (sector_id, stock_code)
SELECT (SELECT id FROM sector_master WHERE sector_name='음식료'), stock_code
FROM stock_master WHERE industry REGEXP '식품|음료|작물 재배|도축'
  AND industry <> '동물용 사료 및 조제식품 제조업';

-- 엔터테인먼트 (영화/방송/음반/영상 콘텐츠)
--  · '통신 및 방송 장비 제조업'(안테나·통신장비), '영상 및 음향기기 제조업'(TV/스피커 등 하드웨어)은
--    콘텐츠가 아니라 제조업이므로 제외('방송'/'영상' 토큰에 잘못 매칭됨).
INSERT IGNORE INTO sector_stock_map (sector_id, stock_code)
SELECT (SELECT id FROM sector_master WHERE sector_name='엔터테인먼트'), stock_code
FROM stock_master WHERE industry REGEXP '영화|방송|오디오물|영상'
  AND industry NOT IN ('통신 및 방송 장비 제조업','영상 및 음향기기 제조업');

-- 조선 (선박 및 보트)
INSERT IGNORE INTO sector_stock_map (sector_id, stock_code)
SELECT (SELECT id FROM sector_master WHERE sector_name='조선'), stock_code
FROM stock_master WHERE industry REGEXP '선박|보트';

-- 금융 (금융/은행/보험/증권/연금)
INSERT IGNORE INTO sector_stock_map (sector_id, stock_code)
SELECT (SELECT id FROM sector_master WHERE sector_name='금융'), stock_code
FROM stock_master WHERE industry REGEXP '금융|은행|보험|증권|연금|저축기관';

-- 항공 (항공기 제조/항공 여객)
INSERT IGNORE INTO sector_stock_map (sector_id, stock_code)
SELECT (SELECT id FROM sector_master WHERE sector_name='항공'), stock_code
FROM stock_master WHERE industry REGEXP '항공';

-- 사료 (동물용 사료/작물)
INSERT IGNORE INTO sector_stock_map (sector_id, stock_code)
SELECT (SELECT id FROM sector_master WHERE sector_name='사료'), stock_code
FROM stock_master WHERE industry REGEXP '사료|작물 재배';

-- 게임 (업종분류가 '소프트웨어'로 묶여 식별 불가 → 종목코드 큐레이션)
INSERT IGNORE INTO sector_stock_map (sector_id, stock_code)
SELECT (SELECT id FROM sector_master WHERE sector_name='게임'), stock_code
FROM stock_master WHERE stock_code IN (
  '036570','251270','259960','263750','293490','225570','462870','078340','063080',
  '112040','101730','123420','069080','095660','042420','194480','192080','067000',
  '201490','052790'
);

-- 화장품 (업종분류가 '기타 화학제품'으로 묶여 식별 불가 → 종목코드 큐레이션)
INSERT IGNORE INTO sector_stock_map (sector_id, stock_code)
SELECT (SELECT id FROM sector_master WHERE sector_name='화장품'), stock_code
FROM stock_master WHERE stock_code IN (
  '090430','002790','051900','192820','044820','161890','237880','018250','018290',
  '027050','092730','214420','226320','078520','352480','439090','257720'
);
