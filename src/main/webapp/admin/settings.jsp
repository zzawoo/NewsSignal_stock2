<%@ page pageEncoding="UTF-8" %>
<%@ include file="/common/header.jsp" %>
<%-- 관리자: 수집 설정 (2차 구현 골격). collect_settings 값을 편집 --%>
<main class="wrap">
  <section class="card">
    <h2>수집 설정 <span class="tag">관리자</span></h2>
    <p class="hint" style="padding:16px 18px">
      이 화면은 2차 단계에서 구현됩니다. 수집 주기·키워드·쿼터·Jsoup ON/OFF·
      섹터/종목 매핑 CRUD가 여기에 들어갑니다.
    </p>
    <ul style="padding:0 18px 18px;line-height:1.9;color:#9aa7c2">
      <li>collect.auto.enabled — 자동 수집 ON/OFF (기본 N, 수동 버튼)</li>
      <li>collect.keywords — 수집 키워드</li>
      <li>daily.quota.limit / safe.ratio — API 한도·감속 임계</li>
      <li>collect.jsoup.enabled — 보조 수집기(약관 검토 후 활성)</li>
    </ul>
  </section>
</main>
<%@ include file="/common/footer.jsp" %>
