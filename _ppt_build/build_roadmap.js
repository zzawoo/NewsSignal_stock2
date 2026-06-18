const pptxgen = require("pptxgenjs");
const p = new pptxgen();
p.defineLayout({ name: "W16x9", width: 10, height: 5.625 });
p.layout = "W16x9";

const NAVY = "003763";   // 기대효과 헤더 (템플릿 slide6와 동일)
const BLUE = "0070C0";   // 번호·포인트 강조
const BLACK = "000000";  // 향후 발전 방향 헤더
const TITLE = "1A1A1A";
const GRAY = "555555";
const KOR = "Malgun Gothic";

const s = p.addSlide();
s.background = { color: "FFFFFF" };

// 제목
s.addText("8. 향후계획 — 기대 효과 및 향후 발전 방향", {
  x: 0.45, y: 0.26, w: 9.1, h: 0.5, fontFace: KOR, fontSize: 25, bold: true, color: TITLE, align: "left",
});
s.addText("현재 구현(수집·분석 분리 · 종목 상세 모달 · 섹터 매핑 정제 · 기간 필터 · KIS 자가치유) 기준 재정리", {
  x: 0.47, y: 0.74, w: 9.1, h: 0.28, fontFace: KOR, fontSize: 10.5, color: GRAY, align: "left",
});

const LX = 0.45, RX = 5.25, CW = 4.3;

// 섹션 헤더
s.addText("기대효과", { x: LX, y: 1.08, w: CW, h: 0.4, fontFace: KOR, fontSize: 14, bold: true, color: "FFFFFF", fill: { color: NAVY }, align: "center", valign: "middle" });
s.addText("향후 발전 방향", { x: RX, y: 1.08, w: CW, h: 0.4, fontFace: KOR, fontSize: 14, bold: true, color: "FFFFFF", fill: { color: BLACK }, align: "center", valign: "middle" });

// 좌측: 기대효과 — 업무·조직 효과 + 개인 역량·스킬 성장
function subLabel(text, y) {
  s.addText(text, { x: LX, y, w: CW, h: 0.2, fontFace: KOR, fontSize: 11.5, bold: true, color: NAVY, align: "left", valign: "middle", margin: 0 });
}
function effItem(n, title, desc, y) {
  s.addText(String(n), { x: LX + 0.02, y: y + 0.01, w: 0.26, h: 0.26, shape: p.ShapeType.ellipse, fill: { color: BLUE }, color: "FFFFFF", fontFace: KOR, fontSize: 10.5, bold: true, align: "center", valign: "middle" });
  s.addText(title, { x: LX + 0.36, y: y - 0.03, w: CW - 0.36, h: 0.24, fontFace: KOR, fontSize: 11, bold: true, color: BLUE, align: "left", valign: "middle", margin: 0 });
  s.addText(desc, { x: LX + 0.36, y: y + 0.20, w: CW - 0.36, h: 0.24, fontFace: KOR, fontSize: 8.5, color: GRAY, align: "left", valign: "top", margin: 0 });
}
subLabel("업무·조직 효과", 1.50);
effItem(1, "업무 효율 향상", "뉴스·시황 확인 시간 절감, 호재·악재 자동 분류·요약", 1.73);
effItem(2, "투자 의사결정 지원", "호재·악재·영향도 점수와 섹터 시그널로 판단 근거 제공", 2.18);
effItem(3, "사내 프로젝트 활용", "투자 스터디·시장 모니터링·리서치·종목 발굴에 활용", 2.63);
subLabel("개인 역량·스킬 성장", 3.12);
effItem(4, "풀스택 개발 역량", "Servlet·JSP·JS·MariaDB 백엔드~프론트 직접 구현", 3.35);
effItem(5, "실시간 API 연동 실무", "KIS·DART·네이버 인증·토큰·캐싱·호출한도 대응", 3.80);
effItem(6, "AI·LLM 활용 능력", "프롬프트 설계·JSON 강제 출력·배치 분석·provider 전환", 4.25);
effItem(7, "데이터 파이프라인·문제해결", "수집–중복제거–유사그룹화–분석–표출 설계 + 실전 디버깅", 4.70);

// 우측: 향후 발전 방향 4 (제목 pill + 세부)
const dirs = [
  ["분석 품질 고도화", ["· 유사도: 제목 Jaccard → 임베딩·TF-IDF (의미 기반 그룹화)", "· 호재·악재: few-shot 채점 루브릭으로 점수 일관성 향상", "· 모델: 경량+상위 2단계 하이브리드, 저신뢰 건 재분석"]],
  ["데이터 정확도", ["· 섹터·종목 매핑 큐레이션 마스터 + 관리자 CRUD", "· 누락 지표(목표가·배당) 외부 소스 연동", "· 섹터 태그 단일 소스 정규화"]],
  ["알림·자동화", ["· 분석 자동 스케줄(한도 가드·재시도) + 신선도 표시", "· 관심 종목 급변·영향도 임계 시 메일·푸시 알림", "· 일·주 요약 리포트 자동 발송"]],
  ["개인화·운영·확장", ["· 로그인 + 관심 키워드/종목 관리·개인화 대시보드", "· API 키 외부화·CI/CD·스케줄러 모니터링", "· 종목별 호재/악재 타임라인·신호 기반 백테스트"]],
];
let dy = 1.62;
const dstep = 0.95;
dirs.forEach((d) => {
  s.addText(d[0], { x: RX + 0.02, y: dy, w: 2.3, h: 0.32, fontFace: KOR, fontSize: 11.5, bold: true, color: NAVY, align: "center", valign: "middle", fill: { color: "FFFFFF" }, line: { color: NAVY, width: 1 }, rectRadius: 0.16, shape: p.ShapeType.roundRect });
  s.addText(d[1].join("\n"), { x: RX + 0.05, y: dy + 0.37, w: CW - 0.05, h: 0.5, fontFace: KOR, fontSize: 9, color: GRAY, align: "left", valign: "top", lineSpacingMultiple: 1.05, margin: 0 });
  dy += dstep;
});

p.writeFile({ fileName: "C:/project/NewsSignal_stock2/NewsSignal_향후계획_v2.pptx" }).then((f) => console.log("WROTE " + f));
