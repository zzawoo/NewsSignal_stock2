# NewsSignal AI

네이버 뉴스 기반 주식 호재·악재 분석 대시보드. JSP/Servlet/MariaDB/Tomcat 구조.
뉴스를 **수집 → 유사 그룹화 → AI(LLM) 호재·악재 분석 → 대시보드 표출** 하는 수집·분석 분리형 파이프라인이며,
종목 시세·차트(KIS)·기업 정보(DART)·증시 지표(네이버)를 실시간 연동한다. (2026-06 기준 운영 가능)

---

## 1. 기술 스택 (JDK8 고정)

| 구분 | 버전 |
|------|------|
| JDK | 8 (`maven.compiler.source/target = 1.8`) |
| WAS | Apache Tomcat 8.5.x (WAR) |
| Servlet / JSP | 3.0 / 2.3 |
| DB | MariaDB 10.1.x (utf8mb4) |
| JDBC / Pool | mariadb-java-client 2.7.x / HikariCP 4.0.x |
| JSON / HTML | Gson 2.10.1 / Jsoup 1.17.2 |
| 프론트 차트 | lightweight-charts (로컬 번들) |
| LLM | Groq(기본) · Gemini · Ollama 전환 가능 |

> 저장소는 `env/`(untracked)에 JDK8·Maven·Tomcat·MariaDB 전체 툴체인을 번들한다.

---

## 2. 시스템 구성

```
[외부 API]  네이버 뉴스 · Groq LLM · KIS · DART · 네이버 금융
                 │
[서버 Tomcat]  ① 수집(Collector·content_hash 중복제거)
               → ② 유사 그룹화(SimilarityService·Jaccard·대표 1건)
               → ③ AI 분석(AnalyzeService·LLM·호재/악재·영향도·섹터 → 그룹 전파)
               → ④ 표출(DashboardServlet·StockInfoServlet·MacroInfoServlet)
                 │   공통: Db(HikariCP)·DartApiClient(24h)·KisApiClient(5s/60s)·CsrfFilter·SecurityHeaderFilter
[DB MariaDB]   news_similarity_group · news_sector_map · news_stock_map ·
               sector_master · sector_stock_map · stock_master · collect_settings
                 │
[클라이언트]   대시보드(JSP+JS, lightweight-charts) — 주요 이슈 · 연관 섹터 · 증시 지표 · 종목 상세 모달
```

### 디렉터리

```
src/main/java/com/newssignal/
  ├ common/    Db, CsrfFilter, SecurityHeaderFilter, DartApiClient, KisApiClient, MacroDataClient
  ├ collector/ NaverNewsApiCollector, QuotaGuard, SettingsService, NewsCollectScheduler,
  │            NewsCollectContextListener, CollectJob, AnalyzeJob, ArticleService,
  │            PopulateStocks, BackfillStocks, BackfillSectors
  ├ analyzer/  SimilarityService, AnalyzeService, StockResolver, GroupSummaryService
  └ user/      DashboardServlet, StockInfoServlet, MacroInfoServlet,
               CollectRunServlet, CollectStatusServlet, GroupArticlesServlet
src/main/webapp/
  ├ user/dashboard.jsp   admin/settings.jsp   common/{header,footer,error}.jsp
  └ resources/js/dashboard.js  resources/js/lightweight-charts.standalone.production.js
db/ schema.sql, map_sector_stocks.sql, collect_keywords_scope.sql 등
```

---

## 3. 빌드 & 실행

```bash
# 1) 환경변수 (코드/설정파일에 두지 말 것 — 계획서 9장)
export NAVER_CLIENT_ID=... NAVER_CLIENT_SECRET=...
export DB_URL='jdbc:mariadb://localhost:3306/newssignal?useUnicode=true&characterEncoding=utf8'
export DB_USER=root DB_PASS=
export KIS_APP_KEY=... KIS_APP_SECRET=... DART_API_KEY=... GROQ_API_KEY=...

# 2) DB 스키마 + 마스터 데이터
mysql --default-character-set=utf8mb4 -u root newssignal < db/schema.sql
# 종목 마스터 적재(KIND): java -cp <classpath> com.newssignal.collector.PopulateStocks
# 섹터-종목 매핑: mysql ... < db/map_sector_stocks.sql

# 3) 빌드 → target/newssignal.war
mvn clean package

# 4) WAR 배포 + Tomcat 기동 (Windows는 start_utf8.ps1 사용 권장)
#    접속: http://localhost:8080/newssignal2/user/dashboard.jsp
```

- 컨텍스트는 **/newssignal2** (동일 Tomcat의 sibling `/newssignal`과 충돌 방지).
- 핫 배포는 WAR 복사 + 전개 디렉토리 삭제로 재전개 강제. 재기동 전 **MariaDB 먼저 기동**.

---

## 4. 수집 · 분석 동작

- **수집(자동, 기본)**: `collect.auto.enabled=Y`, `collect.interval.sec=600`(10분 주기).
  네이버 뉴스 검색 API → content_hash 중복제거 → Jaccard 그룹화 → DB 적재.
- **분석(현재 수동 버튼)**: `analyze.auto.enabled=N`. 대시보드 "분석·요약 실행" 버튼 →
  미분석 그룹 대표 1건만 LLM 호출 → 호재/악재·영향도·섹터 → 그룹 전파.
  (자동 분석을 켜면 `analyze.interval.sec`(기본 60초) 주기로 동작.)
- **LLM 제공자**: `analyze.provider` = groq(기본) | gemini | ollama.
- **표출**: `/api/dashboard`가 최근 3일 + `analyzed_yn='Y'` 데이터를 집계.

---

## 5. 실시간 연동 (DB 미경유 · 메모리 캐시)

| 경로 | 소스 | 캐시 |
|------|------|------|
| 증시 지표(지수·환율·유가·금·은) + 스파크라인 | 네이버 금융 | 프론트 15초 폴링 / 스파크라인 5분 |
| 종목 시세(현재가·거래량·PER 등) | KIS `inquire-price` | 5초 |
| 캔들 차트(분/일/주/월) | KIS chart | 60초 |
| 기업 개황·재무 | DART | 24시간 |

---

## 6. 계획서 핵심 리스크가 코드에 반영된 지점

| 리스크 | 반영 위치 |
|--------|-----------|
| API 일일 쿼터(25,000) 초과 | `QuotaGuard` (안전계수 0.8) |
| 스케줄러 메모리 누수 / Tomcat 종료 차단 | `NewsCollectScheduler`(daemon·removeOnCancel·shutdownNow), 리스너 단계 분리 |
| LLM 비용 | 유사 그룹 **대표 1건만 분석** + 전파, `analyze.daily.limit` |
| KIS 호출 한도(EGW00201) / 토큰(EGW00133·EGW00123) | KisApiClient 캐시+throttle+토큰 영속화·이중검사 락·만료 자가치유 |
| 외부 API 부하 | DART 24h / KIS 5s·60s / 매크로 5분 메모리 캐시 |
| CORS / API Key 노출 | 서버사이드 호출만, 환경변수 보관, 로그·프론트 미노출 |
| CDN 차단(CSP) | `script-src 'self'` → lightweight-charts 로컬 번들 |
| utf8 깨짐 / 인덱스 키 길이 | schema.sql utf8mb4, 제목 컬럼 VARCHAR(191) |
| 한국어 유사도 | `SimilarityService` 단어+바이그램 혼합 Jaccard |
| SQLi / XSS / CSRF | PreparedStatement / c:out·escape / CsrfFilter |

---

## 7. 향후 개선 (로드맵)

- 분석 품질: 유사도 임베딩/TF-IDF 전환, 호재·악재 채점 루브릭(few-shot) 표준화
- 데이터 정확도: 섹터-종목 매핑 큐레이션 보강(KRX 업종분류 ≠ 투자테마), 누락 지표 외부 연동
- 알림·자동화: 분석 자동 스케줄, 관심 종목 급변·호재/악재 실시간 알림
- 개인화·운영: 로그인+관심종목, API 키 외부화·CI/CD·모니터링, 신호 기반 백테스트
