-- 로그인 + 관심 목록 + 알림 (Phase 1·2)
USE newssignal;

CREATE TABLE IF NOT EXISTS users (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(50) NOT NULL UNIQUE,            -- 로그인 아이디(ID)
  email VARCHAR(191) UNIQUE,                       -- 선택(향후 메일 알림용, NULL 허용)
  password_hash VARCHAR(255) NOT NULL,
  display_name VARCHAR(100),
  alert_impact_min INT NOT NULL DEFAULT 4,        -- |impact_score| >= 이 값이면 호재/악재 알림
  alert_price_pct DECIMAL(5,2) NOT NULL DEFAULT 5.00, -- 관심종목 ±이 %면 급변 알림
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 관심 세트: 키워드/종목 묶음 + 세트별 독립 알림기준
CREATE TABLE IF NOT EXISTS user_watch_set (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  enabled CHAR(1) NOT NULL DEFAULT 'Y',            -- 이 세트 알림 ON/OFF
  alert_impact_min INT NOT NULL DEFAULT 4,         -- 이 세트의 호재/악재 알림 임계
  alert_price_pct DECIMAL(5,2) NOT NULL DEFAULT 5.00, -- 이 세트의 급변 알림 임계(%)
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_watch_keyword (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  set_id BIGINT NOT NULL,
  keyword VARCHAR(100) NOT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_set_kw (set_id, keyword)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_watch_stock (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  set_id BIGINT NOT NULL,
  stock_code VARCHAR(10) NOT NULL,
  stock_name VARCHAR(100),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_set_stock (set_id, stock_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_notification (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  ntype VARCHAR(20) NOT NULL,            -- 'news' | 'price'
  title VARCHAR(255),
  body VARCHAR(500),
  link VARCHAR(255),
  ref_key VARCHAR(120),                  -- 중복알림 방지 키
  read_yn CHAR(1) NOT NULL DEFAULT 'N',
  pushed_yn CHAR(1) NOT NULL DEFAULT 'N', -- 카카오 등 외부 채널 발송 여부
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user_read (user_id, read_yn, id),
  UNIQUE KEY uk_user_ref (user_id, ref_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 카카오톡 '나에게 보내기' 연동 토큰 (사용자별)
CREATE TABLE IF NOT EXISTS user_kakao (
  user_id BIGINT PRIMARY KEY,
  access_token VARCHAR(512) NOT NULL,
  refresh_token VARCHAR(512) NOT NULL,
  access_expires_at DATETIME,
  linked_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
