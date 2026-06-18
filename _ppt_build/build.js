const pptxgen = require("pptxgenjs");
const pres = new pptxgen();
pres.layout = "LAYOUT_WIDE"; // 13.3 x 7.5
pres.author = "NewsSignal";
pres.title = "NewsSignal AI 프로젝트 분석";

const C = {
  bg: "0B1220", bg2: "0E1A2B", card: "172132", card2: "1E2A3D",
  text: "F1F5F9", muted: "97A6BC", dim: "6B7B92",
  accent: "38BDF8", green: "34D399", red: "F87171", amber: "FBBF24", purple: "A78BFA",
  line: "26354B", white: "FFFFFF"
};
const F = "Malgun Gothic";
const W = 13.3, H = 7.5, M = 0.6;
const sh = () => ({ type: "outer", color: "000000", blur: 9, offset: 3, angle: 135, opacity: 0.28 });

function bg(s) { s.background = { color: C.bg }; }
function header(slide, kicker, title) {
  slide.addText(kicker, { x: M, y: 0.42, w: 12, h: 0.3, fontFace: F, fontSize: 12.5, color: C.accent, bold: true, charSpacing: 2, margin: 0 });
  slide.addText(title, { x: M, y: 0.70, w: 12.1, h: 0.72, fontFace: F, fontSize: 29, color: C.text, bold: true, margin: 0 });
}
function card(slide, x, y, w, h, fill) {
  slide.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y, w, h, fill: { color: fill || C.card }, line: { color: C.line, width: 1 }, rectRadius: 0.09, shadow: sh() });
}
function pill(slide, x, y, text, color, w) {
  w = w || 0.95;
  slide.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y, w, h: 0.34, fill: { color }, line: { type: "none" }, rectRadius: 0.17 });
  slide.addText(text, { x, y, w, h: 0.34, fontFace: F, fontSize: 12.5, bold: true, color: "0B1220", align: "center", valign: "middle", margin: 0 });
}
function dot(slide, x, y, color) {
  slide.addShape(pres.shapes.OVAL, { x, y, w: 0.14, h: 0.14, fill: { color }, line: { type: "none" } });
}
function bullets(slide, items, x, y, w, h, size, color) {
  slide.addText(items.map(t => ({ text: t, options: { bullet: { code: "2022", indent: 14 }, breakLine: true, paraSpaceAfter: 7 } })),
    { x, y, w, h, fontFace: F, fontSize: size || 13, color: color || C.muted, valign: "top", margin: 0, lineSpacingMultiple: 1.02 });
}

/* ── S1 Title ── */
let s = pres.addSlide(); bg(s);
s.addShape(pres.shapes.OVAL, { x: 9.4, y: -2.2, w: 6.5, h: 6.5, fill: { color: "12243B" }, line: { type: "none" } });
s.addShape(pres.shapes.OVAL, { x: 11.2, y: 3.6, w: 4.5, h: 4.5, fill: { color: "101E33" }, line: { type: "none" } });
s.addText("프로젝트 코드 분석 · 발표자료", { x: M, y: 1.5, w: 10, h: 0.4, fontFace: F, fontSize: 15, color: C.accent, bold: true, charSpacing: 3, margin: 0 });
s.addText("NewsSignal AI", { x: M, y: 2.0, w: 11, h: 1.3, fontFace: F, fontSize: 60, color: C.text, bold: true, margin: 0 });
s.addText("네이버 뉴스 기반 주식 호재·악재 분석 대시보드", { x: M, y: 3.45, w: 11, h: 0.6, fontFace: F, fontSize: 21, color: C.muted, margin: 0 });
let tx = M;
["JDK 8", "Servlet · JSP", "MariaDB", "Tomcat WAR", "LLM · Groq"].forEach(t => { const wd = 0.5 + t.length * 0.13; s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: tx, y: 4.55, w: wd, h: 0.45, fill: { color: C.card2 }, line: { color: C.line, width: 1 }, rectRadius: 0.22 }); s.addText(t, { x: tx, y: 4.55, w: wd, h: 0.45, fontFace: F, fontSize: 12.5, color: C.text, align: "center", valign: "middle", margin: 0 }); tx += wd + 0.22; });
s.addText("도구·기법 선택 이유 · 단계별 구현 원리 · 문제와 수정 · 향후 개선", { x: M, y: 6.5, w: 12, h: 0.4, fontFace: F, fontSize: 13, color: C.dim, margin: 0 });

/* ── S2 Agenda ── */
s = pres.addSlide(); bg(s);
header(s, "AGENDA", "목차");
const agenda = [
  ["01", "프로젝트 개요", "무엇을, 왜, 어떤 제약으로", C.accent],
  ["02", "시스템 아키텍처", "계층·패키지·외부 연동", C.accent],
  ["03", "핵심 파이프라인", "수집 → 그룹화 → 분석 → 표출", C.green],
  ["04", "단계별 구현 상세", "수집 · 분석/요약 · 표출 · 인프라", C.green],
  ["05", "도구 선택 이유", "왜 썼나 · 장단점", C.amber],
  ["06", "핵심 기법 개념", "무슨 개념이고 왜 썼나", C.amber],
  ["07", "초기 구현 & 변천", "처음 방식과 그만둔 것", C.red],
  ["08", "문제와 해결 (5)", "데이터·시세·구조·LLM·섹터", C.red],
  ["09", "현재 구현 방식", "지금은 이렇게 동작한다", C.purple],
  ["10", "향후 개선 제안", "다음에 무엇을 할까", C.purple],
];
agenda.forEach((a, i) => {
  const col = i % 2, row = Math.floor(i / 2);
  const x = M + col * 6.2, y = 1.65 + row * 0.85;
  card(s, x, y, 5.95, 0.72, C.card);
  s.addText(a[0], { x: x + 0.18, y, w: 0.85, h: 0.72, fontFace: F, fontSize: 23, color: a[3], bold: true, valign: "middle", margin: 0 });
  s.addText(a[1], { x: x + 1.1, y: y + 0.1, w: colWClamp(a[1]), h: 0.32, fontFace: F, fontSize: 15, color: C.text, bold: true, valign: "middle", margin: 0 });
  s.addText(a[2], { x: x + 1.1, y: y + 0.4, w: 4.7, h: 0.28, fontFace: F, fontSize: 11, color: C.muted, valign: "middle", margin: 0 });
});
function colWClamp() { return 4.7; }

/* ── S3 Overview ── */
s = pres.addSlide(); bg(s);
header(s, "01 · OVERVIEW", "프로젝트 개요");
card(s, M, 1.7, 6.05, 2.45, C.card);
s.addText("🎯  목적", { x: M + 0.3, y: 1.9, w: 5.5, h: 0.4, fontFace: F, fontSize: 16, color: C.accent, bold: true, margin: 0 });
bullets(s, ["네이버 뉴스를 수집·AI 분석해 종목/섹터별 호재·악재 신호를 시각화", "유사 뉴스를 그룹화해 대표 1건만 LLM 분석 → 비용 절감이 설계의 핵심", "KIS 실시간 시세·DART 재무와 연동"], M + 0.3, 2.35, 5.5, 1.7, 12.5, C.muted);
card(s, M + 6.25, 1.7, 5.95, 2.45, C.card);
s.addText("🔒  기술 제약 (고정)", { x: M + 6.55, y: 1.9, w: 5.4, h: 0.4, fontFace: F, fontSize: 16, color: C.amber, bold: true, margin: 0 });
bullets(s, ["JDK 8 · 순수 Servlet 3.0 + JSP + MariaDB + Tomcat WAR", "Spring Boot / React / FastAPI 도입 금지", "라이브러리도 JDK8 호환 버전 상한 유지"], M + 6.55, 2.35, 5.4, 1.7, 12.5, C.muted);
card(s, M, 4.35, 12.2, 2.5, C.card2);
s.addText("🔗  외부 연동", { x: M + 0.3, y: 4.55, w: 6, h: 0.4, fontFace: F, fontSize: 16, color: C.green, bold: true, margin: 0 });
[["네이버 뉴스 API", "기사 수집 (키워드 검색)", C.accent], ["KIS OpenAPI", "실시간 시세·차트", C.green], ["DART OpenAPI", "기업 재무·개황", C.amber], ["LLM", "Groq / Gemini / Ollama", C.purple]].forEach((e, i) => {
  const x = M + 0.3 + i * 2.95;
  card(s, x, 5.05, 2.75, 1.55, C.card);
  dot(s, x + 0.28, 5.32, e[2]);
  s.addText(e[0], { x: x + 0.5, y: 5.2, w: 2.1, h: 0.4, fontFace: F, fontSize: 13.5, color: C.text, bold: true, valign: "middle", margin: 0 });
  s.addText(e[1], { x: x + 0.28, y: 5.75, w: 2.3, h: 0.7, fontFace: F, fontSize: 11.5, color: C.muted, valign: "top", margin: 0 });
});

/* ── S4 Architecture ── */
s = pres.addSlide(); bg(s);
header(s, "02 · ARCHITECTURE", "시스템 아키텍처");
[["표출 (user)", "DashboardServlet · StockInfoServlet · JSP/JS 대시보드", C.accent], ["분석 (analyzer)", "SimilarityService · AnalyzeService · StockResolver · GroupSummaryService", C.green], ["수집 (collector)", "NaverNewsApiCollector · QuotaGuard · Scheduler · CollectJob · AnalyzeJob", C.amber], ["공통 (common) · 저장소", "Db(HikariCP) · CsrfFilter · KisApiClient   |   MariaDB (utf8mb4)", C.purple]].forEach((l, i) => {
  const y = 1.75 + i * 1.12;
  card(s, M, y, 12.2, 1.0, C.card);
  s.addShape(pres.shapes.OVAL, { x: M + 0.25, y: y + 0.33, w: 0.34, h: 0.34, fill: { color: l[2] }, line: { type: "none" } });
  s.addText(l[0], { x: M + 0.8, y: y + 0.13, w: 3.5, h: 0.74, fontFace: F, fontSize: 16, color: C.text, bold: true, valign: "middle", margin: 0 });
  s.addText(l[1], { x: M + 4.2, y: y + 0.13, w: 7.8, h: 0.74, fontFace: F, fontSize: 12.5, color: C.muted, valign: "middle", margin: 0 });
});
s.addText("같은 Tomcat·DB를 형제 프로젝트와 공유 → 컨텍스트를 /newssignal2 로 분리", { x: M, y: 6.7, w: 12, h: 0.35, fontFace: F, fontSize: 11.5, color: C.dim, italic: true, margin: 0 });

/* ── S5 Pipeline ── */
s = pres.addSlide(); bg(s);
header(s, "03 · PIPELINE", "핵심 파이프라인");
const steps = [["1", "수집", "네이버 API · 키워드 기반\nDB 적재 (제목/요약/링크)", C.accent], ["2", "그룹화", "content_hash 중복제거\nJaccard 유사도 그룹화", C.accent], ["3", "AI 분석", "대표 1건만 LLM 호출\n호재/악재·영향도·섹터·종목", C.green], ["4", "매핑·전파", "StockResolver 종목 매칭\n그룹 전체에 결과 전파", C.green], ["5", "표출", "DashboardServlet 집계\n최근 3일 대시보드", C.amber]];
const sw = 2.28, sgap = 0.13;
steps.forEach((st, i) => {
  const x = M + i * (sw + sgap);
  card(s, x, 2.1, sw, 2.9, C.card);
  s.addShape(pres.shapes.OVAL, { x: x + sw / 2 - 0.33, y: 2.35, w: 0.66, h: 0.66, fill: { color: st[3] }, line: { type: "none" } });
  s.addText(st[0], { x: x + sw / 2 - 0.33, y: 2.35, w: 0.66, h: 0.66, fontFace: F, fontSize: 24, bold: true, color: "0B1220", align: "center", valign: "middle", margin: 0 });
  s.addText(st[1], { x: x + 0.1, y: 3.15, w: sw - 0.2, h: 0.45, fontFace: F, fontSize: 17, bold: true, color: C.text, align: "center", margin: 0 });
  s.addText(st[2], { x: x + 0.15, y: 3.65, w: sw - 0.3, h: 1.25, fontFace: F, fontSize: 11.5, color: C.muted, align: "center", valign: "top", margin: 0 });
});
card(s, M, 5.35, 12.2, 1.35, C.card2);
s.addText([{ text: "💡 설계상 절대 원칙   ", options: { bold: true, color: C.amber, fontSize: 14 } }, { text: "유사 그룹화 → 대표 1건만 분석 → 그룹 전파.  뉴스 건건이 분석하면 LLM 비용이 폭증한다.", options: { color: C.muted, fontSize: 13 } }], { x: M + 0.35, y: 5.55, w: 11.5, h: 0.95, fontFace: F, valign: "middle", margin: 0 });

/* ── 단계별 구현 helper + 4 slides ── */
function stageSlide(kicker, title, sysList, stepList, toolsText) {
  let s = pres.addSlide(); bg(s);
  header(s, kicker, title);
  card(s, M, 1.7, 5.3, 4.4, C.card);
  s.addText("관련 시스템 · 클래스", { x: M + 0.3, y: 1.9, w: 4.8, h: 0.4, fontFace: F, fontSize: 15, color: C.accent, bold: true, margin: 0 });
  bullets(s, sysList, M + 0.3, 2.42, 4.75, 3.55, 11.3, C.muted);
  card(s, M + 5.5, 1.7, 6.7, 4.4, C.card2);
  s.addText("동작 원리", { x: M + 5.8, y: 1.9, w: 6.2, h: 0.4, fontFace: F, fontSize: 15, color: C.green, bold: true, margin: 0 });
  s.addText(stepList.map((t, i) => ({ text: (i + 1) + ".  " + t, options: { breakLine: true, paraSpaceAfter: 9 } })),
    { x: M + 5.8, y: 2.42, w: 6.25, h: 3.55, fontFace: F, fontSize: 11.6, color: C.text, valign: "top", margin: 0, lineSpacingMultiple: 1.02 });
  card(s, M, 6.2, 12.2, 0.82, C.card);
  s.addText([{ text: "기법 · 도구   ", options: { bold: true, color: C.amber, fontSize: 12.5 } }, { text: toolsText, options: { color: C.muted, fontSize: 11.5 } }], { x: M + 0.3, y: 6.2, w: 11.6, h: 0.82, fontFace: F, valign: "middle", margin: 0 });
}
stageSlide("04 · 단계별 구현 ①", "수집 (Collect)",
  ["NaverNewsApiCollector — 네이버 검색 API → DTO", "CollectJob — 수집 1회 실행(적재까지만)", "QuotaGuard — 일일 25,000 한도·동적 주기", "NewsCollectScheduler — 데몬 주기 실행기", "ArticleService — 저장·중복제거·그룹화", "SimilarityService — content_hash·Jaccard"],
  ["키워드 선정: 증시·경제·정책·과학 + 상장 종목 전체 (1회 100개씩 회전)", "한도 체크(QuotaGuard) → 네이버 API 호출(키워드당 30건)", "content_hash로 완전중복 제거 (중복은 그룹 카운트만 +1)", "최근 24h 활성 그룹과 Jaccard 매칭 → 합류·대표교체 또는 신규 그룹", "그룹·섹터 매핑 + 요약 갱신, 다음 회전 인덱스 저장"],
  "content_hash 중복제거 · Jaccard 유사 그룹화 · 데몬 스케줄러(누수 방지) · 동적 주기   |   현재: 자동 10분");
stageSlide("04 · 단계별 구현 ②", "분석 · 요약 (Analyze)",
  ["AnalyzeJob — 분석 1회 실행(중복 가드)", "AnalyzeService — 대상 조회·배치 호출·저장", "callGroqBatch / Ollama / Gemini — 제공자별 LLM", "parseBatchResults — 견고 파싱 + 점수 clamp", "StockResolver — 종목명·코드 매칭(2,700+)", "GroupSummaryService — 규칙기반 요약(보조)"],
  ["대상: 미분석 + duplicate_count≥2 + 최근 2일, 노출도·최신 순 상위 200", "제공자 선택(groq/ollama/gemini) → 그룹 N개를 JSON 배치로 호출", "JSON 강제 프롬프트(한국어·허용 섹터·스키마) → 파싱·점수 clamp", "저장: 호재/악재·영향도·요약 + 그룹 전파(analyzed_yn='Y')", "섹터·종목·거시 매핑(이름매칭+화이트리스트) / 429 한도 처리"],
  "대표 1건+전파 · 배치 호출 · 노출도 컷 · 제공자 추상화 · JSON 강제   |   현재: '분석·요약 실행' 버튼(Groq)");
stageSlide("04 · 단계별 구현 ③", "표출 (Display)",
  ["DashboardServlet — /api/dashboard 집계", "StockInfoServlet — /api/stock/info 시세·재무", "KisApiClient — 시세 캐시·throttle·토큰영속화", "dashboard.jsp / dashboard.js — 화면·비동기", "CollectRunServlet / StatusServlet — 버튼·폴링"],
  ["집계 쿼리(PreparedStatement): 주요 이슈·섹터·종목·거시 신호", "공통 필터: analyzed_yn='Y' AND 최근 3일", "섹터/종목 숫자 = Σ duplicate_count (버즈량 가중)", "실시간 시세: KIS 12초 캐시 + 실패 시 마지막 정상값(0 깜빡임 방지)", "버튼: CSRF POST → AnalyzeJob → 상태 폴링 → 화면 갱신"],
  "가중 집계 · 3일 윈도우 · 캐시/throttle · CSRF/XSS 방어   |   프론트: vanilla JS, 15초 갱신");
stageSlide("04 · 단계별 구현 ④", "인프라 (Infra)",
  ["Tomcat 8.5 WAR — 컨텍스트 /newssignal2", "MariaDB 10.1 + HikariCP 풀", "Groq(기본) / Ollama(선택) / Gemini(선택)", "env/ 번들 — JDK·Tomcat·Maven·MariaDB", "Git / GitHub — 코드·문서·SQL"],
  ["start.ps1: 키 설정 → MariaDB 자동기동 → (Ollama) → 빌드", "WAR을 webapps/newssignal2.war 로 핫 배포 → catalina run", "MariaDB 먼저 안 뜨면 HikariCP 실패=404 → 자동기동으로 방지", "형제 /newssignal과 Tomcat·DB·KIS계정 공유 → 비활성 권장", "키는 환경변수, start.ps1·env/는 .gitignore"],
  "원클릭 기동 · 토큰 디스크 영속화 · 컨텍스트 분리 · 비밀키 외부화");

/* ── S10 Tools (table) ── */
s = pres.addSlide(); bg(s);
header(s, "05 · WHY THESE TOOLS", "도구 선택 이유 · 장단점");
const th = t => ({ text: t, options: { fill: { color: "243349" }, color: C.accent, bold: true, fontFace: F, fontSize: 12, align: "left", valign: "middle" } });
const td = (t, c, b) => ({ text: t, options: { color: c || C.muted, bold: !!b, fontFace: F, fontSize: 10.5, align: "left", valign: "middle" } });
const trows = [
  [th("도구"), th("왜 사용"), th("장점"), th("단점")],
  ["Java 8 · Servlet/JSP", "제약(고정)·가벼운 웹", "의존성 최소·안정", "보일러플레이트·최신문법 X"],
  ["Tomcat · WAR", "서블릿 표준·핫배포", "가볍고 익숙", "형제앱과 포트/컨텍스트 충돌"],
  ["MariaDB", "무료·한국어·집계", "SQL 친숙·트랜잭션", "한글 REGEXP 불안정"],
  ["HikariCP", "빠른 커넥션 풀", "표준·누수 감지", "DB 미기동 시 404"],
  ["네이버 뉴스 API", "합법 수집·키 보호", "안정·일 25,000회", "본문 전문 X (요약만)"],
  ["KIS OpenAPI", "실시간 시세", "정확·실시간", "토큰/초당 한도 빡빡"],
  ["Groq (LLM)", "무료·빠름·OpenAI 호환", "1건 2초·한국어 OK", "Cloudflare UA 차단·RPM"],
];
const tableData = trows.map((r, ri) => ri === 0 ? r : r.map((c, ci) => td(c, ci === 0 ? C.text : C.muted, ci === 0)));
s.addTable(tableData, { x: M, y: 1.7, w: 12.2, colW: [2.7, 3.0, 3.25, 3.25], rowH: 0.585, border: { pt: 0.5, color: C.line }, fill: { color: C.card }, valign: "middle", margin: [3, 6, 3, 6] });
s.addText("그 외: Gson(JSON) · Maven(빌드) · DART(재무) · Git/GitHub(형상) · start.ps1(원클릭 기동)", { x: M, y: 6.6, w: 12, h: 0.35, fontFace: F, fontSize: 11, color: C.dim, italic: true, margin: 0 });

/* ── S11 Techniques ── */
s = pres.addSlide(); bg(s);
header(s, "06 · TECHNIQUES", "핵심 기법 개념 · 이유");
const tech = [
  ["유사 그룹화 (Jaccard)", "제목 단어 겹침으로 같은 이슈 묶기 → 분석 대상 압축", C.accent],
  ["대표 1건 + 전파", "그룹당 1건만 LLM → 결과를 전체 전파 (비용 절감)", C.accent],
  ["producer / consumer", "수집=적재, 분석=큐 소비. DB의 analyzed_yn 이 곧 큐", C.green],
  ["배치 LLM 호출", "N개 그룹을 1호출로 묶어 RPD/RPM 절약", C.green],
  ["캐시 + 폴백", "시세 12초 캐시, 실패 시 마지막 정상값(0 깜빡임 방지)", C.amber],
  ["이중검사 락", "토큰을 한 스레드만 발급 → 동시발급(EGW00133) 방지", C.amber],
  ["토큰 영속화", "토큰을 파일로 저장 → 재기동마다 재발급 안 함", C.purple],
  ["문맥 키워드 매칭", "금/은은 시세 문맥일 때만 인정 → 거짓 양성 제거", C.purple],
];
tech.forEach((t, i) => {
  const col = i % 2, row = Math.floor(i / 2);
  const x = M + col * 6.2, y = 1.72 + row * 1.2;
  card(s, x, y, 5.95, 1.05, C.card);
  dot(s, x + 0.28, y + 0.27, t[2]);
  s.addText(t[0], { x: x + 0.55, y: y + 0.15, w: 5.2, h: 0.4, fontFace: F, fontSize: 14.5, bold: true, color: C.text, valign: "middle", margin: 0 });
  s.addText(t[1], { x: x + 0.3, y: y + 0.55, w: 5.4, h: 0.45, fontFace: F, fontSize: 11.5, color: C.muted, valign: "top", margin: 0 });
});

/* ── S12 Initial ── */
s = pres.addSlide(); bg(s);
header(s, "07 · HOW IT STARTED", "초기 구현 방식");
card(s, M, 1.75, 6.05, 4.95, C.card);
s.addText("🧱  골격(skeleton)에서 출발", { x: M + 0.3, y: 1.95, w: 5.5, h: 0.4, fontFace: F, fontSize: 16, color: C.accent, bold: true, margin: 0 });
bullets(s, ["핵심 알고리즘(유사도 그룹화)은 완성, 일부는 TODO", "수집과 분석이 한 실행에 결합 — CollectJob이 수집 직후 동기로 분석까지", "LLM = Gemini, 분석은 수동 버튼으로 트리거", "API 키 없을 때는 목업 분석(generateMockAnalysis) 으로 대체", "종목 매핑은 4종목뿐 · market 컬럼은 'KOSPI/KOSDAQ' 자리표시자"], M + 0.3, 2.45, 5.5, 4.0, 12.5, C.muted);
card(s, M + 6.25, 1.75, 5.95, 4.95, C.card2);
s.addText("⚠️  그래서 생긴 한계", { x: M + 6.55, y: 1.95, w: 5.4, h: 0.4, fontFace: F, fontSize: 16, color: C.red, bold: true, margin: 0 });
bullets(s, ["수집이 LLM 속도·무료 한도에 묶여 느려짐 (최대 5분 대기)", "데이터 품질 이슈 — 쓰레기 '지수' 섹터, 자리표시자 market, 4종목 매핑", "KIS 시세가 갱신마다 0 ↔ 정상으로 깜빡임", "무료 LLM 한도 초과로 백로그가 안 빠짐", "섹터 자동매칭의 대량 거짓 양성(금·은 등)"], M + 6.55, 2.45, 5.4, 4.0, 12.5, C.muted);

/* ── S13 Dropped ── */
s = pres.addSlide(); bg(s);
header(s, "07 · WHAT WE DROPPED", "사용하다 그만둔 것 · 이유");
const drop = [
  ["Gemini 무료", "RPD 20회/일 → 백로그 못 빠짐", "→ 보조 옵션으로 강등", C.amber],
  ["Ollama 로컬 (CPU)", "품질 모델 1건 72초 → 너무 느림", "→ GPU 머신용 옵션 유지", C.red],
  ["qwen2.5 / exaone 2.4b", "중국어 드리프트 / 스키마 실패", "→ EXAONE 7.8B 채택", C.red],
  ["동기 결합 (수집·분석)", "수집이 LLM에 묶여 지연·실패", "→ producer/consumer 분리", C.green],
  ["단일글자 섹터 매칭", "은행·현금·골드만삭스 오매칭", "→ 문맥매칭 · LLM 판단", C.green],
  ["목업 분석 / OpenAI", "데이터 오염 / 유료 부담", "→ Groq 실연결", C.green],
];
drop.forEach((d, i) => {
  const col = i % 2, row = Math.floor(i / 2);
  const x = M + col * 6.2, y = 1.72 + row * 1.62;
  card(s, x, y, 5.95, 1.4, C.card);
  s.addText(d[0], { x: x + 0.3, y: y + 0.16, w: 5.4, h: 0.4, fontFace: F, fontSize: 14.5, bold: true, color: C.text, margin: 0 });
  s.addText("버린 이유: " + d[1], { x: x + 0.3, y: y + 0.58, w: 5.4, h: 0.4, fontFace: F, fontSize: 11.5, color: C.muted, margin: 0 });
  s.addText(d[2], { x: x + 0.3, y: y + 0.97, w: 5.4, h: 0.35, fontFace: F, fontSize: 11.5, color: d[3], bold: true, margin: 0 });
});

/* ── problem→solution helper + 5 slides ── */
function problemSlide(kicker, title, problem, cause, solutions, resultText) {
  let s = pres.addSlide(); bg(s);
  header(s, kicker, title);
  card(s, M, 1.75, 5.4, 4.95, C.card);
  pill(s, M + 0.3, 1.98, "문제", C.red, 0.9);
  s.addText(problem, { x: M + 0.3, y: 2.45, w: 4.85, h: 1.5, fontFace: F, fontSize: 13, color: C.text, valign: "top", margin: 0, lineSpacingMultiple: 1.05 });
  pill(s, M + 0.3, 4.05, "원인", C.amber, 0.9);
  s.addText(cause, { x: M + 0.3, y: 4.5, w: 4.85, h: 1.95, fontFace: F, fontSize: 13, color: C.muted, valign: "top", margin: 0, lineSpacingMultiple: 1.05 });
  card(s, M + 5.6, 1.75, 6.6, 4.95, C.card2);
  pill(s, M + 5.9, 1.98, "해결", C.green, 0.9);
  bullets(s, solutions, M + 5.9, 2.5, 6.0, 3.4, 13, C.text);
  card(s, M + 5.6, 5.75, 6.6, 0.9, C.card);
  s.addText([{ text: "결과   ", options: { bold: true, color: C.green, fontSize: 13 } }, { text: resultText, options: { color: C.muted, fontSize: 12.5 } }], { x: M + 5.9, y: 5.75, w: 6.0, h: 0.9, fontFace: F, valign: "middle", margin: 0 });
}
problemSlide("08 · 문제와 해결 ①", "데이터 품질",
  "대시보드 출력이 비거나 엉뚱함:\n쓰레기 '지수' 섹터, market='KOSPI/KOSDAQ' 자리표시자, news_stock_map이 단 4종목.",
  "• 목업 분석이 4종목만 하드코딩\n• PopulateStocks가 HTTP 302로 0행\n• 섹터-종목 마스터 매핑 공백",
  ["PopulateStocks를 KIND HTTPS로 재작성 → KOSPI 834 / KOSDAQ 1773 등재", "StockResolver 신설 — 이름·코드 매칭(2,700+ 종목)으로 매핑 2,265건으로 확대", "map_sector_stocks.sql 로 모든 업종/테마에 종목 연결", "쓰레기 섹터 화이트리스트 가드(지수·거시경제 제외)"],
  "4종목 → 2,265종목 매핑, 섹터 패널 정상화");
problemSlide("08 · 문제와 해결 ②", "KIS 시세 깜빡임",
  "관련 종목의 거래량·거래대금·시가총액이 갱신 때마다 0 ↔ 정상값을 반복.",
  "• KIS rate limit(EGW00201) + 토큰 동시발급(EGW00133)\n• 호출 실패 시 네이버 폴백이 vol/시총을 0으로 하드코딩",
  ["종목별 시세 캐시(12초) + 실패 시 마지막 정상값 반환", "글로벌 throttle + 토큰 발급 이중검사 락(동시발급 방지)", "토큰을 디스크에 영속화 → 재기동마다 재발급 안 함", "프론트는 0/빈값이면 갱신 스킵(직전 값 유지)"],
  "깜빡임 제거 · EGW 에러 대폭 감소");
problemSlide("08 · 문제와 해결 ③", "수집/분석 결합 → 분리",
  "수집이 분석에 묶여 LLM이 느리면 수집까지 지연·실패. 백로그가 쌓여도 안 빠짐.",
  "• CollectJob이 수집 후 동기로 분석 호출(최대 5분 대기)\n• 분석 처리량이 수집 유입량을 못 따라감",
  ["producer/consumer 분리 — CollectJob(적재만) + AnalyzeJob(분석)", "DB의 analyzed_yn='N' 이 곧 작업 큐 역할", "노출도 컷(duplicate_count) + 최근 2일 + 배치 호출로 처리량↑", "수집은 자동, 분석은 독립 실행"],
  "수집이 LLM에 안 막힘 · 중요 뉴스 우선 처리");
s = pres.addSlide(); bg(s);
header(s, "08 · 문제와 해결 ④", "LLM 분석 엔진의 여정");
card(s, M, 1.7, 6.0, 5.0, C.card);
[["Gemini 무료", "RPD 20회/일 — 백로그를 못 빠뜨림", C.amber], ["Ollama 로컬", "무료·무제한이나 GPU 없어 CPU만", C.red], ["└ qwen2.5:7b", "한국어인데 중국어로 드리프트 ✗", C.dim], ["└ exaone 2.4b", "빠르나 스키마 못 따라감(빈 출력) ✗", C.dim], ["└ exaone 7.8b", "품질OK, 그러나 1건 72초(200건≈4h)", C.dim], ["Groq 클라우드", "무료·빠름 · 1건 2.4초 · 한국어 양호 ✓", C.green]].forEach((j, i) => {
  const jy = 2.0 + i * 0.77;
  dot(s, M + 0.32, jy + 0.08, j[2]);
  s.addText(j[0], { x: M + 0.55, y: jy - 0.05, w: 2.3, h: 0.4, fontFace: F, fontSize: 13.5, bold: i < 2 || i === 5, color: C.text, valign: "middle", margin: 0 });
  s.addText(j[1], { x: M + 2.85, y: jy - 0.05, w: 3.1, h: 0.5, fontFace: F, fontSize: 11, color: C.muted, valign: "middle", margin: 0 });
});
card(s, M + 6.2, 1.7, 6.0, 5.0, C.card2);
s.addText("1건 분석 처리 시간 (초, 낮을수록 좋음)", { x: M + 6.5, y: 1.92, w: 5.4, h: 0.4, fontFace: F, fontSize: 13.5, color: C.accent, bold: true, margin: 0 });
s.addChart(pres.charts.BAR, [{ name: "초", labels: ["Ollama (로컬 CPU)", "Groq (클라우드)"], values: [72, 2.4] }], { x: M + 6.4, y: 2.4, w: 5.6, h: 3.0, barDir: "col", chartColors: [C.red, C.green], chartArea: { fill: { color: C.card2 } }, plotArea: { fill: { color: C.card2 } }, catAxisLabelColor: C.muted, valAxisLabelColor: C.muted, catAxisLabelFontFace: F, valAxisLabelFontFace: F, catAxisLabelFontSize: 11, valAxisLabelFontSize: 10, valGridLine: { color: C.line, size: 0.5 }, catGridLine: { style: "none" }, showValue: true, dataLabelColor: C.text, dataLabelFontFace: F, dataLabelFontSize: 13, dataLabelPosition: "outEnd", showLegend: false, showTitle: false, valAxisHidden: true, barGapWidthPct: 60 });
s.addText("≈ 30배 빠름 — 함정: Groq는 Cloudflare 뒤 → Java 기본 UA 차단(403). User-Agent 헤더로 해결.", { x: M + 6.5, y: 5.55, w: 5.5, h: 0.95, fontFace: F, fontSize: 11.5, color: C.amber, valign: "top", margin: 0, lineSpacingMultiple: 1.05 });

s = pres.addSlide(); bg(s);
header(s, "08 · 문제와 해결 ⑤", "섹터 거짓 양성(금·은 등) 정리");
card(s, M, 1.7, 5.5, 5.0, C.card);
pill(s, M + 0.3, 1.93, "문제", C.red, 0.9);
s.addText("섹터 매핑 1·2위가 '은' 9,446 / '금' 3,822 으로 비정상 폭증.", { x: M + 0.3, y: 2.4, w: 4.95, h: 0.85, fontFace: F, fontSize: 13, color: C.text, valign: "top", margin: 0 });
pill(s, M + 0.3, 3.25, "원인", C.amber, 0.9);
s.addText("단일·짧은 글자를 부분일치로 매칭 →\n은행·은퇴·금융·현금·골드만삭스·실버타운까지 다 잡힘.", { x: M + 0.3, y: 3.72, w: 4.95, h: 1.0, fontFace: F, fontSize: 13, color: C.muted, valign: "top", margin: 0 });
pill(s, M + 0.3, 4.85, "해결", C.green, 0.9);
s.addText("금·은은 본문 추출에서 제외하고 LLM 의미판단으로만 인정.\n조선·사료·통신·기계는 문맥 표현이 있을 때만 매칭. related_sectors 재생성.", { x: M + 0.3, y: 5.32, w: 4.95, h: 1.25, fontFace: F, fontSize: 12.5, color: C.text, valign: "top", margin: 0, lineSpacingMultiple: 1.05 });
card(s, M + 5.7, 1.7, 6.5, 5.0, C.card2);
s.addText("정리 전 → 후 (섹터 매핑 건수)", { x: M + 6.0, y: 1.92, w: 5.8, h: 0.4, fontFace: F, fontSize: 13.5, color: C.accent, bold: true, margin: 0 });
s.addChart(pres.charts.BAR, [{ name: "정리 전", labels: ["은", "금", "유가", "조선", "통신"], values: [9446, 3822, 1348, 465, 465] }, { name: "정리 후", labels: ["은", "금", "유가", "조선", "통신"], values: [182, 165, 133, 119, 65] }], { x: M + 5.95, y: 2.45, w: 6.0, h: 3.7, barDir: "col", chartColors: [C.red, C.green], chartArea: { fill: { color: C.card2 } }, plotArea: { fill: { color: C.card2 } }, catAxisLabelColor: C.muted, valAxisLabelColor: C.muted, catAxisLabelFontFace: F, valAxisLabelFontFace: F, catAxisLabelFontSize: 12, valAxisLabelFontSize: 9, valGridLine: { color: C.line, size: 0.5 }, catGridLine: { style: "none" }, showLegend: true, legendPos: "t", legendColor: C.muted, legendFontFace: F, legendFontSize: 11, showTitle: false, barGapWidthPct: 40 });

/* ── S19 Current ── */
s = pres.addSlide(); bg(s);
header(s, "09 · CURRENT", "현재 구현 방식");
[["🗞️", "수집", "자동 10분 스케줄", "증시·경제·정책·과학 + 상장 종목 키워드.\nQuotaGuard 일일 한도(25k) 가드.", C.accent], ["🧠", "분석·요약", "‘분석·요약 실행’ 버튼", "적재된 뉴스를 Groq 배치 분석 →\n호재/악재·영향도·섹터·종목 매핑.", C.green], ["📊", "표출", "대시보드 (최근 3일)", "주요 이슈 · 연관 섹터(버즈량) ·\n종목 신호 · 실시간 시세 바.", C.amber], ["⚙️", "인프라", "원클릭 기동·영속화", "start.ps1이 MariaDB·Ollama 자동기동.\n토큰 디스크 캐시 · GitHub 연동.", C.purple]].forEach((c, i) => {
  const col = i % 2, row = Math.floor(i / 2);
  const x = M + col * 6.2, y = 1.8 + row * 2.55;
  card(s, x, y, 5.95, 2.35, C.card);
  s.addText(c[0], { x: x + 0.28, y: y + 0.25, w: 0.8, h: 0.8, fontSize: 30, align: "center", valign: "middle", margin: 0 });
  s.addText(c[1], { x: x + 1.2, y: y + 0.25, w: 3.0, h: 0.45, fontFace: F, fontSize: 17, bold: true, color: C.text, valign: "middle", margin: 0 });
  s.addText(c[2], { x: x + 1.2, y: y + 0.72, w: 4.5, h: 0.35, fontFace: F, fontSize: 12.5, bold: true, color: c[4], valign: "middle", margin: 0 });
  s.addText(c[3], { x: x + 0.3, y: y + 1.2, w: 5.35, h: 1.0, fontFace: F, fontSize: 12.5, color: C.muted, valign: "top", margin: 0, lineSpacingMultiple: 1.05 });
});

/* ── S20 Future ── */
s = pres.addSlide(); bg(s);
header(s, "10 · ROADMAP", "향후 개선 제안");
[["데이터 정확도", C.accent, ["섹터 오매칭 잔여(조선·통신) 추가 정리", "related_sectors 일관성 자동 보정", "가중 버즈량 vs 고유 건수 지표 선택 옵션"]], ["분석 품질·성능", C.green, ["임베딩 기반 유사도(TF-IDF/벡터)로 그룹화 고도화", "프롬프트·few-shot 정교화로 판정 일관성↑", "분석 결과 캐싱·증분 처리"]], ["운영·보안", C.amber, ["형제 /newssignal 분리 → 설정 리셋 차단", "API 키 외부화(.env/Secret) · CI 빌드", "스케줄/에러 모니터링·알림"]], ["기능 확장", C.purple, ["종목별 호재·악재 타임라인", "급변 신호 실시간 알림(관심종목)", "신호 기반 간이 백테스트"]]].forEach((f, i) => {
  const col = i % 2, row = Math.floor(i / 2);
  const x = M + col * 6.2, y = 1.8 + row * 2.55;
  card(s, x, y, 5.95, 2.35, C.card);
  s.addShape(pres.shapes.OVAL, { x: x + 0.3, y: y + 0.28, w: 0.22, h: 0.22, fill: { color: f[1] }, line: { type: "none" } });
  s.addText(f[0], { x: x + 0.65, y: y + 0.18, w: 5.0, h: 0.42, fontFace: F, fontSize: 16.5, bold: true, color: C.text, valign: "middle", margin: 0 });
  bullets(s, f[2], x + 0.35, y + 0.72, 5.3, 1.5, 12.5, C.muted);
});

/* ── S21 Closing ── */
s = pres.addSlide(); bg(s);
s.addShape(pres.shapes.OVAL, { x: -2.2, y: 3.6, w: 6.5, h: 6.5, fill: { color: "10203A" }, line: { type: "none" } });
s.addText("SUMMARY", { x: M, y: 1.4, w: 10, h: 0.4, fontFace: F, fontSize: 14, color: C.accent, bold: true, charSpacing: 3, margin: 0 });
s.addText("골격에서 출발해, 5번의 문제를 거쳐\n안정적인 무료 파이프라인으로", { x: M, y: 1.9, w: 12, h: 1.7, fontFace: F, fontSize: 33, bold: true, color: C.text, margin: 0, lineSpacingMultiple: 1.05 });
[["데이터 품질", "4종목 → 2,265종목, 섹터 정밀화", C.accent], ["시세·구조", "캐시·토큰 영속화 · 수집/분석 분리", C.green], ["LLM 엔진", "Gemini→Ollama→Groq (≈30배 가속)", C.amber]].forEach((sm, i) => {
  const x = M + i * 4.05;
  card(s, x, 4.2, 3.85, 1.5, C.card);
  dot(s, x + 0.3, 4.5, sm[2]);
  s.addText(sm[0], { x: x + 0.52, y: 4.38, w: 3.1, h: 0.4, fontFace: F, fontSize: 14.5, bold: true, color: C.text, valign: "middle", margin: 0 });
  s.addText(sm[1], { x: x + 0.3, y: 4.88, w: 3.35, h: 0.7, fontFace: F, fontSize: 11.5, color: C.muted, valign: "top", margin: 0 });
});
s.addText("\"수집은 자동, 분석은 버튼, 신호는 명확하게.\"", { x: M, y: 6.1, w: 12, h: 0.5, fontFace: F, fontSize: 17, italic: true, color: C.accent, margin: 0 });

pres.writeFile({ fileName: "C:/project/NewsSignal_stock2/NewsSignal_AI_프로젝트분석.pptx" }).then(f => console.log("WROTE: " + f));
