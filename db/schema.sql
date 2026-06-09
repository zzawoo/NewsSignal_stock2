-- =====================================================================
-- NewsSignal AI - MariaDB 10.1.x Schema
-- 문자셋: utf8mb4 (이모지 대응). 인덱스 걸리는 제목 컬럼은 VARCHAR(191) 제한
-- (계획서 6장: utf8mb4 + 767바이트 인덱스 키 한계 회피)
-- =====================================================================
CREATE DATABASE IF NOT EXISTS newssignal
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE newssignal;

-- ---------------------------------------------------------------------
-- 뉴스 원본
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS news_articles (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  title           VARCHAR(500) NOT NULL,
  description     VARCHAR(1000),
  original_link   VARCHAR(700),
  naver_link      VARCHAR(700),
  press           VARCHAR(100),
  pub_date        DATETIME,
  source_type     VARCHAR(30)  DEFAULT 'NAVER_API' COMMENT '수집 출처',
  content_hash    VARCHAR(191) COMMENT '제목 정규화 해시(완전중복 차단)',
  similarity_group_id BIGINT NULL,
  duplicate_yn    CHAR(1) DEFAULT 'N',
  collected_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_content_hash (content_hash),
  KEY idx_group (similarity_group_id),
  KEY idx_collected (collected_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='뉴스 원본';

-- ---------------------------------------------------------------------
-- 유사 뉴스 그룹 (대표 1건 분석 → 그룹 전파)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS news_similarity_group (
  id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
  group_title          VARCHAR(500) NOT NULL,
  normalized_title     VARCHAR(191) COMMENT '정규화 제목(인덱스용 191)',
  representative_news_id BIGINT,
  good_bad_type        VARCHAR(20) COMMENT 'GOOD/BAD/NEUTRAL/MIXED',
  impact_score         INT DEFAULT 0 COMMENT '-5 ~ +5',
  related_sectors      VARCHAR(1000),
  duplicate_count      INT DEFAULT 1,
  analyzed_yn          CHAR(1) DEFAULT 'N' COMMENT 'LLM 분석 완료 여부',
  first_collected_at   DATETIME,
  last_collected_at    DATETIME,
  created_at           DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at           DATETIME NULL,
  KEY idx_type (good_bad_type),
  KEY idx_dupcnt (duplicate_count),
  KEY idx_norm (normalized_title),
  KEY idx_last (last_collected_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='유사 뉴스 그룹';

-- ---------------------------------------------------------------------
-- 뉴스-그룹 매핑
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS news_similarity_map (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  group_id        BIGINT NOT NULL,
  news_id         BIGINT NOT NULL,
  similarity_score DECIMAL(5,2) DEFAULT 0.00,
  is_representative CHAR(1) DEFAULT 'N',
  created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_group (group_id),
  KEY idx_news (news_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='뉴스 유사도 매핑';

-- ---------------------------------------------------------------------
-- AI 분석 결과
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS news_ai_analysis (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  group_id        BIGINT NOT NULL,
  news_id         BIGINT NOT NULL COMMENT '분석한 대표 뉴스',
  summary_short   VARCHAR(300),
  summary_detail  VARCHAR(1000),
  good_bad_type   VARCHAR(20),
  impact_score    INT DEFAULT 0,
  impact_reason   VARCHAR(1000),
  risk_factor     VARCHAR(1000),
  sector_keywords VARCHAR(500) COMMENT '쉼표 구분',
  confidence_score INT DEFAULT 0,
  analyzed_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_group (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 분석 결과';

-- ---------------------------------------------------------------------
-- 섹터 마스터 / 종목 마스터 / 매핑 (계획서 7장: 초기 수작업 구축)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sector_master (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  sector_name VARCHAR(100) NOT NULL,
  sector_type VARCHAR(20) DEFAULT '테마' COMMENT '테마/업종',
  UNIQUE KEY uk_name (sector_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS stock_master (
  stock_code  VARCHAR(10) PRIMARY KEY,
  stock_name  VARCHAR(100) NOT NULL,
  market      VARCHAR(20) COMMENT 'KOSPI/KOSDAQ'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sector_stock_map (
  sector_id   BIGINT NOT NULL,
  stock_code  VARCHAR(10) NOT NULL,
  PRIMARY KEY (sector_id, stock_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS news_sector_map (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  group_id    BIGINT NOT NULL,
  sector_id   BIGINT NOT NULL,
  good_bad_type VARCHAR(20),
  KEY idx_group (group_id),
  KEY idx_sector (sector_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- 수집 설정 (계획서 6.3: 쿼터 항목 추가)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS collect_settings (
  setting_key   VARCHAR(100) PRIMARY KEY,
  setting_value VARCHAR(500) NOT NULL,
  description   VARCHAR(500),
  updated_at    DATETIME NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='수집 설정';

-- ---------------------------------------------------------------------
-- 로그
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS collect_log (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  collector   VARCHAR(50),
  keyword     VARCHAR(100),
  fetched_cnt INT DEFAULT 0,
  new_cnt     INT DEFAULT 0,
  dup_cnt     INT DEFAULT 0,
  status      VARCHAR(20),
  message     VARCHAR(500),
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 일일 쿼터 카운터
CREATE TABLE IF NOT EXISTS api_quota_daily (
  quota_date  DATE PRIMARY KEY,
  call_count  INT DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =====================================================================
-- 초기 데이터
-- =====================================================================
INSERT INTO collect_settings (setting_key, setting_value, description) VALUES
  ('collect.interval.seconds', '60',    '뉴스 수집 주기(초). 동적 산정 결과로 갱신'),
  ('collect.mode',             'API',   'API / JSOUP'),
  ('collect.jsoup.enabled',    'N',     'Jsoup 보조 수집 사용 여부(약관 검토 전 N)'),
  ('collect.auto.enabled',     'N',     '자동 스케줄러 ON/OFF (기본 수동 버튼)'),
  ('collect.keywords',         '반도체,2차전지,방산,바이오,조선', '수집 키워드(쉼표)'),
  ('collect.max.retry',        '3',     '수집 실패 재시도 횟수'),
  ('collect.timeout.ms',       '5000',  '수집 요청 타임아웃'),
  ('daily.quota.limit',        '25000', '네이버 API 일일 한도'),
  ('daily.quota.safe.ratio',   '0.8',   '감속 임계(80%)'),
  ('analyze.daily.limit',      '2000',  'LLM 일일 분석 상한(비용 통제)')
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value);

-- 섹터 마스터
INSERT INTO sector_master (sector_name, sector_type) VALUES
  ('반도체','업종'),('HBM','테마'),('AI','테마'),('2차전지','테마'),
  ('자동차','업종'),('바이오','업종'),('조선','업종'),('방산','테마'),
  ('금융','업종'),('원전','테마')
ON DUPLICATE KEY UPDATE sector_type = VALUES(sector_type);

-- 종목 마스터 (일부 예시)
INSERT INTO stock_master (stock_code, stock_name, market) VALUES
  ('005930','삼성전자','KOSPI'),('000660','SK하이닉스','KOSPI'),
  ('042700','한미반도체','KOSPI'),('373220','LG에너지솔루션','KOSPI'),
  ('247540','에코프로비엠','KOSDAQ'),('012450','한화에어로스페이스','KOSPI'),
  ('079550','LIG넥스원','KOSPI'),('207940','삼성바이오로직스','KOSPI'),
  ('068270','셀트리온','KOSPI'),('329180','HD현대중공업','KOSPI'),
  ('034020','두산에너빌리티','KOSPI'),('035420','네이버','KOSPI')
ON DUPLICATE KEY UPDATE stock_name = VALUES(stock_name);
