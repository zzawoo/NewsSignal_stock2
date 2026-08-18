# NewsSignal AI — 프로젝트 메모리

네이버 뉴스 기반 주식 호재·악재 분석 대시보드. 이 파일은 Codex가 매 세션
시작 시 읽는다. 상세 설계는 README.md, 산출 배경은 docs/개발계획서_v2.0.docx 참고.

## 현재 상태 (2026-06-17)

**운영 가능 단계.** 수집 → 그룹화 → AI 분석 → 표출 파이프라인 전 구간 구현·동작 중.
`mvn clean package` 통과, Tomcat 배포 후 대시보드 정상 동작 확인.

구현 완료:
- **수집·분석 분리 파이프라인**(스케줄러 2개) — content_hash 완전중복 제거 →
  Jaccard 유사 그룹화 → 그룹 대표 1건만 LLM 분석 → 그룹 전파.
- **LLM 분석**: 현재 **Groq**(`llama-3.1-8b-instant`), `analyze.provider`로 gemini/ollama 전환.
  JSON 강제 출력, 호재/악재·영향도(−5~+5)·확신도·섹터(20종 화이트리스트).
- **대시보드**: 주요 이슈(기간 필터 전체/당일/전일 — 전체=당일+전일 날짜 균형, 표시 확대),
  연관 섹터 시그널, 오늘의 증시 지표.
- **증시 지표 카드**: 네이버 실시간(15초 폴링) + **미니 그래프(스파크라인)**(일별 종가, 5분 캐시).
- **종목 상세 모달**: DART 기업/재무(**24h 캐시**) + KIS 캔들차트(가격 **5s**·차트 **60s** 캐시),
  이동평균·볼린저·RSI·시간대(1·5·10분/일/주/월), 동일 업종 비교, 소속 섹터, 홈페이지 링크.
  차트 라이브러리는 **lightweight-charts 로컬 번들**(CSP `script-src 'self'`라 CDN 차단됨).
- **섹터-종목 매핑**: `stock_master.industry`(KIND corpList) 기반 `db/map_sector_stocks.sql` +
  큐레이션. 반도체/엔터 등 KRX 업종분류 잡음 정제.
- **KIS 토큰**: 디스크 영속화 + 이중검사 락 + 만료(EGW00123) 자가치유 + throttle.

잔여/향후: 관리자 CRUD 화면, 유사도 임베딩/TF-IDF, 로그인·관심종목·실시간 알림, 신호 백테스트.

## 빌드 / 실행

저장소는 `env/`(untracked)에 JDK8·Maven·Tomcat·MariaDB 전체 툴체인을 번들한다. 시스템 PATH에 java/mvn 없음 — 번들 사용.

```bash
# 빌드
JAVA_HOME=env/jdk/jdk8u412-b08  env/maven/apache-maven-3.9.6/bin/mvn clean package   # → target/newssignal.war

# 실행: start_utf8.ps1 (MariaDB 자동기동 → 빌드 → WAR 배포 → Tomcat 기동)
# 접속: http://localhost:8080/newssignal2/user/dashboard.jsp
```

- **실행 중 Tomcat의 catalina.base는 sibling 경로**(`C:\project\NewsSignal_stock\env\tomcat\apache-tomcat-8.5.99`),
  컨텍스트 **/newssignal2** (sibling `/newssignal`과 포트/URL 충돌 방지). sibling은 비활성 유지.
- **핫 배포**: WAR를 `...\webapps\newssignal2.war`로 복사 + 전개 디렉토리 `webapps\newssignal2`
  삭제(재전개 강제). catalina-only 재기동은 전개본을 안 바꾸는 함정 있음.
- **재기동 시 MariaDB가 먼저 떠 있어야** 함(없으면 HikariCP 풀 초기화 실패 → 컨텍스트 404).
- **DB**: 로컬 MariaDB(`newssignal`, root, 비번 없음), utf8mb4. 한글 SQL은 **UTF-8 파일 +
  `--default-character-set=utf8mb4`** 로 주입(PowerShell 파이프는 한글 깨짐 → `cmd /c "mysql ... < file"`).

환경변수 (코드/설정파일에 넣지 말 것):
`NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET`, `DB_URL`, `DB_USER`, `DB_PASS`,
`KIS_APP_KEY`, `KIS_APP_SECRET`, `DART_API_KEY`, `GROQ_API_KEY`
(선택: `GEMINI_API_KEY`, `OPENAI_API_KEY`)

## 환경 제약 (절대 변경 금지)

- JDK **8** 고정. pom.xml `source/target = 1.8`. JDK8 호환 라이브러리만 사용.
- **순수 Servlet 3.0 + JSP 2.3 + MariaDB 10.1 + Tomcat 8.5 WAR** 구조 유지.
- **Spring Boot / React / FastAPI / PostgreSQL 도입 금지.**
- 라이브러리 버전 함부로 올리지 말 것 (HikariCP는 4.0.x, mariadb-client 2.7.x가 JDK8 상한).

## 코드 규칙 (계획서 9장 보안)

- 모든 DB 접근은 **PreparedStatement** (문자열 SQL 조립 금지).
- JSP 출력은 `c:out` 또는 escape (XSS 방지). JS는 textContent/escape 사용.
- 상태변경(POST) 요청은 **CSRF 토큰 검증**(`CsrfFilter`). 토큰은 `<meta name="csrf-token">`.
- **CSP `script-src 'self'`**(`SecurityHeaderFilter`) → 외부 스크립트 차단. 프론트 라이브러리는 로컬 번들.
- 외부 API(네이버/KIS/DART/LLM)는 **서버사이드에서만** 호출(+캐시 경유). 브라우저 직접 호출 금지.
- **API Key를 로그/예외 메시지/프론트에 노출 금지.**
- 기사 본문 전문 저장 금지. 제목/요약/링크/발행일/분석결과만 저장.
- 에러 응답에 스택트레이스 노출 금지(공통 error.jsp).
- DB 문자셋 utf8mb4. 인덱스 걸리는 제목 컬럼은 VARCHAR(191) 유지.

## 폴더 구조

```
src/main/java/com/newssignal/
  common/    Db(HikariCP), CsrfFilter, SecurityHeaderFilter,
             DartApiClient(24h캐시), KisApiClient(시세5s·차트60s캐시·토큰자가치유),
             MacroDataClient(네이버 지수·환율·상품·스파크라인)
  collector/ NewsCollector(I), NaverNewsApiCollector, QuotaGuard, SettingsService,
             NewsCollectScheduler, NewsCollectContextListener, NewsArticleDTO,
             CollectJob, AnalyzeJob, ArticleService,
             PopulateStocks(KIND 적재), BackfillStocks, BackfillSectors
  analyzer/  SimilarityService(Jaccard), AnalyzeService(LLM 배치),
             StockResolver(종목명→코드), GroupSummaryService
  user/      DashboardServlet, StockInfoServlet, MacroInfoServlet,
             CollectRunServlet, CollectStatusServlet, GroupArticlesServlet
  (root)     SyncSectors, UpdateSummaries (운영 유틸)
src/main/webapp/
  user/dashboard.jsp, admin/settings.jsp, common/{header,footer,error}.jsp
  resources/js/dashboard.js, resources/js/lightweight-charts.standalone.production.js
db/  schema.sql, map_sector_stocks.sql, collect_keywords_scope.sql, (정제·감사 sql 다수)
```

## 설계상 절대 깨면 안 되는 것

- **유사 그룹화 → LLM 분석** 순서. 그룹 **대표 1건만** 분석하고 그룹에 전파한다
  (LLM 비용 절감의 핵심). 뉴스 건건이 분석하지 말 것.
- 수집 전 **QuotaGuard로 일일 한도(25,000) 체크**. 초과 시 skip.
- 스케줄러는 daemon 스레드 + `setRemoveOnCancelPolicy(true)` +
  종료 시 `shutdown→awaitTermination→shutdownNow` (메모리 누수/종료 차단 방지).
- **외부 API 캐시 TTL 의미**(DB 미저장, 메모리 캐시): DART 24h, KIS 시세 5s / 차트 60s,
  매크로 스파크라인 5분. 함부로 줄이면 KIS 한도(EGW00201) 위험.
- LLM `provider`는 `collect_settings.analyze.provider`로 선택(groq/gemini/ollama).
  Groq는 Cloudflare 뒤 → `User-Agent: Mozilla/5.0` 필수.
- 현재 운영 설정: **수집 자동 10분**(`collect.auto.enabled=Y`, `collect.interval.sec=600`),
  **분석 수동 버튼**(`analyze.auto.enabled=N`). 대시보드 집계는 최근 3일 + `analyzed_yn='Y'`.
- ★ **sibling `/newssignal` 재배포가 공유 DB의 collect_settings를 리셋**함 → 비활성 유지.
- Jsoup 보조 수집기는 약관 리스크로 **기본 비활성**.

## 향후 작업 (TODO)

- [ ] 관리자 화면: 수집 설정·키워드·섹터/종목 매핑 CRUD
- [ ] 유사도 의미화: 제목 Jaccard → 임베딩/TF-IDF (의미 기반 그룹화)
- [ ] 호재/악재 채점 루브릭(few-shot) 표준화로 판정 일관성↑
- [ ] 로그인 + 관심 키워드/종목, 급변·호재악재 실시간 알림(메일/푸시)
- [ ] 섹터-종목 매핑 큐레이션 보강(KRX 업종분류 ≠ 투자테마), 누락 지표(목표가·배당) 외부 연동
- [ ] 운영: API 키 외부화·CI/CD, 스케줄러/에러 모니터링
- [ ] 종목별 호재/악재 타임라인, 신호 기반 백테스트
