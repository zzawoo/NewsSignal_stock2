<%@ page pageEncoding="UTF-8" %>
<%@ include file="/common/header.jsp" %>
<%-- 사용자 대시보드 (계획서 8장). 데이터는 /api/dashboard 에서 비동기 로드 --%>

<div class="deck">
  <button id="collectBtn" class="btn primary">수집·분석 실행</button>
  <span class="hint">실 운영에서는 네이버 검색 API를 서버(Servlet)에서 호출합니다.</span>
</div>

<main class="wrap">
<div class="top-layout">
  <!-- 왼쪽: 주요 이슈 그룹 (3열 x 2행) -->
  <section class="card issues-card">
    <h2>주요 이슈 그룹 <span class="tag">호재·악재·중립 각 2건 · 영향가 강한 순</span></h2>
    <div class="issues-wrapper">
      <div id="issues" class="issues"></div>
    </div>
  </section>

  <!-- 오른쪽: 지수/환율 & 종목 분석 -->
  <div class="right-col">
    <section class="card" style="margin-bottom: 18px;">
      <h2>지수 & 환율 시그널 <span class="tag">국내외 지수 · 환율</span></h2>
      <div id="macroSignals" class="sector" style="max-height: 220px;"></div>
    </section>
    <section class="card">
      <h2>종목별 시그널 <span class="tag">상장 종목 분석</span></h2>
      <div id="stockSignals" class="sector" style="max-height: 220px;"></div>
    </section>
  </div>
</div>

  <div class="grid2" style="margin-top:18px;">
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

<!-- 뉴스 리스트 모달 (이슈 그룹 클릭 시 표시) -->
<div id="articlesModal" class="modal-overlay" style="display: none;">
  <div class="modal-box">
    <div class="modal-header">
      <h3 id="modalTitle">이슈 상세 뉴스</h3>
      <button id="modalCloseBtn" class="modal-close">&times;</button>
    </div>
    <div id="modalBody" class="modal-body">
      <div class="loading-spinner">뉴스 불러오는 중...</div>
    </div>
  </div>
</div>

<script charset="UTF-8" src="<%=request.getContextPath()%>/resources/js/dashboard.js?v=<%=System.currentTimeMillis()%>"></script>
<%@ include file="/common/footer.jsp" %>
