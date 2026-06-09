# NewsSignal AI — 프로젝트 메모리

네이버 뉴스 기반 주식 호재·악재 분석 대시보드. 이 파일은 Claude Code가 매 세션
시작 시 읽는다. 상세 설계는 README.md, 산출 배경은 docs/개발계획서_v2.0.docx 참고.
동작 시연 참고용 프로토타입: docs/prototype_dashboard.html (브라우저로 열면 전체
파이프라인이 목 데이터로 동작 — 운영 코드가 따라야 할 동작 기준).

## 현재 상태 (인수인계)

- **골격(skeleton) 단계.** 핵심 알고리즘과 구조는 완성, 일부는 TODO.
- 이전 작업 환경(Maven Central 차단)에서 **전체 `mvn package` 빌드 검증은 못 함.**
  → **이 세션의 첫 작업은 `mvn clean package`가 통과하는지 확인하고 고치는 것.**
- 검증 완료된 것: `SimilarityService`(JDK8 단독 컴파일 + 그룹화 동작 확인).
  HBM 3건/전기차 2건/방산 2건이 정확히 그룹화됨.
- 프로토타입(`docs/prototype_dashboard.html`)에서 전체 파이프라인 시연 검증됨.

## 빌드 / 실행 명령

```bash
mvn clean package                 # → target/newssignal.war
mysql -u root -p < db/schema.sql  # DB 스키마 적재 (utf8mb4)
# target/newssignal.war 를 Tomcat 8.5 webapps/ 에 배포
# 접속: http://localhost:8080/newssignal/user/dashboard.jsp
```

빌드 전 환경변수 필요 (코드/설정파일에 넣지 말 것):
`NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET`, `DB_URL`, `DB_USER`, `DB_PASS`

## 환경 제약 (절대 변경 금지)

- JDK **8** 고정. pom.xml `source/target = 1.8`. JDK8 호환 라이브러리만 사용.
- **순수 Servlet 3.0 + JSP 2.3 + MariaDB 10.1 + Tomcat 8.5 WAR** 구조 유지.
- **Spring Boot / React / FastAPI / PostgreSQL 도입 금지.**
- 라이브러리 버전 함부로 올리지 말 것 (HikariCP는 4.0.x, mariadb-client 2.7.x가 JDK8 상한).

## 코드 규칙 (계획서 9장 보안)

- 모든 DB 접근은 **PreparedStatement** (문자열 SQL 조립 금지).
- JSP 출력은 `c:out` 또는 escape (XSS 방지). JS는 textContent/escape 사용.
- 상태변경(POST) 요청은 **CSRF 토큰 검증**(`CsrfFilter.validate`).
- 네이버 API는 **서버사이드에서만** 호출(CORS). 브라우저 직접 호출 금지.
- **API Key를 로그/예외 메시지/프론트에 노출 금지.**
- 기사 본문 전문 저장 금지. 제목/요약/링크/발행일/분석결과만 저장.
- 에러 응답에 스택트레이스 노출 금지(공통 error.jsp).
- DB 문자셋 utf8mb4. 인덱스 걸리는 제목 컬럼은 VARCHAR(191) 유지.

## 폴더 구조

```
src/main/java/com/newssignal/
  common/    Db, CsrfFilter, SecurityHeaderFilter
  collector/ NewsCollector(I), NaverNewsApiCollector, QuotaGuard,
             NewsCollectScheduler, NewsCollectContextListener,
             CollectJob, SettingsService, NewsArticleDTO
  analyzer/  SimilarityService
  user/      DashboardServlet, CollectRunServlet
  admin/     (2차)
src/main/webapp/ user|admin|common|resources, WEB-INF/web.xml
db/schema.sql
```

## 설계상 절대 깨면 안 되는 것

- **유사 그룹화 → LLM 분석** 순서. 그룹 **대표 1건만** 분석하고 그룹에 전파한다
  (LLM 비용 절감의 핵심). 뉴스 건건이 분석하지 말 것.
- 수집 전 **QuotaGuard로 일일 한도(25,000) 체크**. 초과 시 skip.
- 수집 주기는 키워드 수 기반 동적 산정(`QuotaGuard.dynamicIntervalSec`, 하한 12초).
  "10초"는 화면 갱신 주기 개념이지 키워드별 API 호출 주기가 아님.
- 스케줄러는 daemon 스레드 + `setRemoveOnCancelPolicy(true)` +
  종료 시 `shutdown→awaitTermination→shutdownNow` (메모리 누수/종료 차단 방지).
- 수집 방식 기본값은 **수동 버튼**(`collect.auto.enabled=N`).
- Jsoup 보조 수집기는 약관 리스크로 **기본 비활성**. 함부로 켜지 말 것.

## 작업 순서 (TODO)

- [ ] `mvn clean package` 전체 빌드 통과 확인 및 수정 (최우선)
- [ ] DB 스키마 적재 + Tomcat 배포로 대시보드 화면 동작 확인
- [ ] `CollectJob` 저장 파이프라인 구현: 수집 → content_hash 중복제거 →
      `SimilarityService`로 그룹화 → 그룹/매핑 저장 (DAO 작성)
- [ ] AI 감성 분석 서비스: 그룹 대표 → LLM(JSON 강제) → 호재/악재/영향도/섹터 →
      그룹 전파. `analyze.daily.limit`로 비용 상한.
- [ ] `DashboardServlet`이 실제 집계 데이터를 반환하도록 연결
- [ ] 관리자 화면: 수집 설정/키워드/섹터·종목 매핑 CRUD
- [ ] 섹터-종목 마스터 초기 데이터 확장 (계획서 7장, schema.sql에 일부 존재)
- [ ] 유사도 임계값 실데이터 튜닝, 이후 TF-IDF Cosine (2차)
