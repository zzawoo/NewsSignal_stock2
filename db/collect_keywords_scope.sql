-- ============================================================
--  수집 범위 / 분석 설정 (collect_settings)
--  적용: mysql ... newssignal < db/collect_keywords_scope.sql
--  (UTF-8 파일로 적재할 것. 인라인 한글 인자는 깨질 수 있음)
-- ============================================================

-- 수집 범위 키워드: 증시 / 경제 / 정책 / 과학·기술 / 산업 (주가에 영향이 있는 분야).
-- 상장 종목명은 SettingsService.getKeywords() 가 stock_master 전체를 자동으로 덧붙인다.
INSERT INTO collect_settings (setting_key, setting_value, updated_at) VALUES
('collect.keywords',
 '증시,코스피,코스닥,주가,상장,공모주,IPO,나스닥,다우,S&P500,경제,금리,기준금리,환율,물가,인플레이션,경기,수출,수입,무역,관세,유가,원자재,부동산,한국은행,연준,달러,금,은,GDP,정책,규제,세제,예산,파업,선거,인공지능,AI,배터리,전기차,신약,로봇,우주항공,자율주행,데이터센터,특허,반도체,바이오,2차전지,자동차,조선,방산,금융,원전,사료,철강,건설,화학,엔터테인먼트,IT,게임,통신,기계,항공,화장품,음식료',
 NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

-- 수집/분석 분리 + 분석량 절감 관련 설정 (코드 기본값과 동일; 명시 기록용)
INSERT INTO collect_settings (setting_key, setting_value, updated_at) VALUES
('collect.auto.enabled',        'Y',     NOW()),  -- 자동 수집 ON
('collect.interval.sec',        '600',   NOW()),  -- 수집 주기 10분(초). 1회 collect.max.per.run 키워드 rotate
('analyze.auto.enabled',        'N',     NOW()),  -- 분석 자동 OFF → "분석·요약 실행" 버튼 클릭 시에만 분석/요약(적재된 뉴스 대상)
('analyze.interval.sec',        '60',    NOW()),  -- (analyze.auto.enabled=Y일 때만 사용되는) 분석 주기(초)
('analyze.batch.size',          '8',     NOW()),  -- LLM 1회 호출당 묶는 그룹 수 (무료 RPD=20/일이 작아 크게 묶음)
('analyze.min.duplicate.count', '2',     NOW()),  -- 노출도 컷: 중복 N건 이상 그룹만 분석
('analyze.max.per.run',         '200',   NOW()),  -- 1회 실행당 최대 분석 그룹 수
('analyze.recent.days',         '2',     NOW()),  -- 분석 대상 기간: 오늘+어제 (DB는 3일 보존)
('analyze.daily.limit',         '20000', NOW())   -- 일일 분석 상한(목업 백필이 한도를 막지 않도록 크게)
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

-- LLM 제공자: 로컬 Ollama (호출 한도 없음·무료). gemini로 되돌리려면 analyze.provider='gemini'.
INSERT INTO collect_settings (setting_key, setting_value, updated_at) VALUES
('analyze.provider', 'ollama',                 NOW()),  -- ollama | gemini | openai
('ollama.model',     'exaone3.5:7.8b',         NOW()),  -- LG EXAONE 3.5 7.8B: 한국어 품질 양호(2.4b는 스키마 못 따라감). CPU라 느림
('ollama.host',      'http://localhost:11434', NOW()),
('ollama.batch.size','1',                      NOW()),  -- CPU+7.8b 안정성 위해 1건씩
('ollama.num.predict','1024',                  NOW()),  -- 출력 토큰 상한(CPU 속도)
('ollama.timeout.ms','300000',                 NOW())   -- 로컬 추론은 느릴 수 있어 5분
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

-- Groq(클라우드, 무료·빠름) 설정. 키는 환경변수 GROQ_API_KEY (start.ps1).
INSERT INTO collect_settings (setting_key, setting_value, updated_at) VALUES
('groq.model',      'llama-3.3-70b-versatile', NOW()),  -- 한국어 양호. 한도 빡빡하면 llama-3.1-8b-instant
('groq.host',       'https://api.groq.com/openai/v1', NOW()),
('groq.batch.size', '3',                       NOW())   -- 무료 TPM 고려 작게
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();
