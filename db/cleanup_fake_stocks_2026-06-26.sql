-- 가짜/환각 종목 정리 (2026-06-26)
-- AnalyzeService.getOrCreateStock가 LLM 환각 6자리코드를 stock_master(market='KRX')로 INSERT해 누적된 쓰레기.
-- 예: 현대차=789012, 금=567890, 토요타=234567, 당진시, 석군참외농장, 한국철강협회, 인텔/롤스로이스(해외) 등.
-- 실제 상장종목은 모두 KOSPI/KOSDAQ/KONEX market으로 별도 존재. KRX market 항목은 전부 제거.

-- 1) 가짜 코드를 참조하는 news_stock_map 정리 (뉴스 자체는 보존, 잘못된 종목매핑만 제거)
DELETE FROM news_stock_map
WHERE stock_code IN (SELECT stock_code FROM stock_master WHERE market='KRX');

-- 2) 가짜 종목 제거
DELETE FROM stock_master WHERE market='KRX';
