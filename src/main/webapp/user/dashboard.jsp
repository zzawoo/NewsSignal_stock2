<%@ include file="/common/header.jsp" %>
<%-- 사용자 대시보드 (계획서 8장). 데이터는 /api/dashboard 에서 비동기 로드 --%>

<div class="deck">
  <button id="collectBtn" class="btn primary">수집·분석 실행</button>
  <span class="hint">실 운영에서는 네이버 검색 API를 서버(Servlet)에서 호출합니다.</span>
</div>

<main class="wrap">
  <section class="card">
    <h2>주요 이슈 그룹 <span class="tag">중복 보도 많은 순</span></h2>
    <div id="issues" class="issues"><div class="empty">데이터를 불러오는 중…</div></div>
  </section>

  <div class="grid2">
    <section class="card">
      <h2>호재 뉴스</h2>
      <div id="goodNews" class="news"></div>
    </section>
    <section class="card">
      <h2>악재 뉴스</h2>
      <div id="badNews" class="news"></div>
    </section>
  </div>
</main>

<script src="<%=request.getContextPath()%>/resources/js/dashboard.js"></script>
<%@ include file="/common/footer.jsp" %>
