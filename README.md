# NewsSignal AI — 운영 코드 골격

네이버 뉴스 기반 주식 호재·악재 분석 대시보드. JSP/Servlet/MariaDB/Tomcat 구조.
개발계획서 v2.0의 설계를 그대로 코드로 옮긴 **골격(skeleton)** 입니다.

> ⚠️ 이 골격은 핵심 파이프라인의 뼈대와 검증된 알고리즘을 담고 있습니다.
> 일부 서비스(DAO 저장, AI 분석 연동)는 TODO로 표시된 확장 지점입니다.

---

## 1. 기술 스택 (계획서 1.2 / JDK8 고정)

| 구분 | 버전 |
|------|------|
| JDK | 8 (`maven.compiler.source/target = 1.8`) |
| WAS | Apache Tomcat 8.5.x |
| Servlet / JSP | 3.0 / 2.3 |
| DB | MariaDB 10.1.x (utf8mb4) |
| JDBC | mariadb-java-client 2.7.x (JDK8 호환) |
| Pool | HikariCP 4.0.3 (JDK8 호환 마지막 라인) |
| JSON | Gson 2.10.1 |
| 보조수집 | Jsoup 1.17.2 (기본 비활성) |

---

## 2. 디렉터리 구조 (계획서 3.3)

```
src/main/java/com/newssignal/
  ├ common/    Db, CsrfFilter, SecurityHeaderFilter
  ├ collector/ NewsCollector(I), NaverNewsApiCollector, QuotaGuard,
  │            NewsCollectScheduler, NewsCollectContextListener,
  │            CollectJob, SettingsService, NewsArticleDTO
  ├ analyzer/  SimilarityService (정규화+바이그램 Jaccard)
  ├ user/      DashboardServlet, CollectRunServlet
  └ admin/     (2차: 설정/매핑 CRUD)
src/main/webapp/
  ├ user/dashboard.jsp   admin/settings.jsp
  ├ common/  header / footer / error.jsp
  └ resources/ css/app.css  js/dashboard.js
db/schema.sql
```

---

## 3. 빌드 & 배포

```bash
# 1) DB 준비
mysql -u root -p < db/schema.sql
#   계정 생성 후 권한 부여 (예시)
#   CREATE USER 'newssignal'@'localhost' IDENTIFIED BY '****';
#   GRANT ALL ON newssignal.* TO 'newssignal'@'localhost';

# 2) 환경변수 (API Key는 코드/설정파일에 두지 말 것 — 계획서 9장)
export NAVER_CLIENT_ID=your_id
export NAVER_CLIENT_SECRET=your_secret
export DB_URL='jdbc:mariadb://localhost:3306/newssignal?useUnicode=true&characterEncoding=utf8'
export DB_USER=newssignal
export DB_PASS=****

# 3) 빌드 → target/newssignal.war 를 Tomcat webapps 에 배포
mvn clean package

# 4) 접속
#   http://localhost:8080/newssignal/user/dashboard.jsp
```

> Maven Central 접근이 되는 환경에서 빌드하세요. 본 골격은 의존성 다운로드가
> 필요합니다. (개발 환경에서 한 번 `mvn package`로 검증 권장)

---

## 4. 수집 방식

- **수동(기본)**: 대시보드의 "수집·분석 실행" 버튼 → `POST /collect/run`
  (CSRF 토큰 검증 후 `CollectJob` 1회 실행)
- **자동(옵션)**: `collect_settings.collect.auto.enabled = Y` 로 설정하면
  서버 시작 시 `NewsCollectScheduler` 가 동적 주기로 가동
  (주기는 키워드 수 기반 자동 산정 — `QuotaGuard.dynamicIntervalSec`)

---

## 5. 계획서 핵심 리스크가 코드에 반영된 지점

| 리스크 | 반영 위치 |
|--------|-----------|
| API 일일 쿼터(25,000) 초과 | `QuotaGuard` — canCall/record, 안전계수 0.8 |
| 동적 수집 주기 | `QuotaGuard.dynamicIntervalSec` (하한 12초) |
| 스케줄러 메모리 누수 | `NewsCollectScheduler` — setRemoveOnCancelPolicy(true), daemon, shutdownNow |
| Tomcat 종료 차단 | daemon 스레드 + contextDestroyed 단계 분리 |
| CORS | 네이버 API는 `NaverNewsApiCollector` 서버사이드 호출만 |
| API Key 노출 | 환경변수 보관, 예외 메시지에 미포함 |
| LLM 비용 | 유사 그룹 대표 1건만 분석(설계), analyze.daily.limit |
| utf8 이모지 깨짐 | schema.sql 전체 utf8mb4 |
| 인덱스 키 길이 | 제목 인덱스 컬럼 VARCHAR(191) |
| 한국어 유사도 한계 | `SimilarityService` 단어+바이그램 혼합 (검증 완료) |
| SQLi / XSS / CSRF | PreparedStatement / c:out·escape / CsrfFilter |
| 투자 면책 | footer.jsp 고정 노출 |

---

## 6. 다음 단계 (TODO)

- `CollectJob` 의 저장/분석 파이프라인 연결 (DAO + AnalyzeService)
- AI 감성 분석 서비스: 그룹 대표 → LLM(JSON) → 전파
- 관리자 화면: 수집 설정·키워드·섹터/종목 매핑 CRUD
- 섹터-종목 마스터 초기 데이터 확장 (계획서 7장)
- 유사도 임계값 실데이터 튜닝, 2차 TF-IDF Cosine
