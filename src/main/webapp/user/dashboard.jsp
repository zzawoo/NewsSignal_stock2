<%@ page pageEncoding="UTF-8" %>
<%@ include file="/common/header.jsp" %>
<%-- 사용자 대시보드 (계획서 8장). 데이터는 /api/dashboard 에서 비동기 로드 --%>

<div class="deck">
  <button id="collectBtn" class="btn primary">수집·분석 실행</button>
  <span class="hint">실 운영에서는 네이버 검색 API를 서버(Servlet)에서 호출합니다.</span>
</div>

<main class="wrap">
  <!-- 상단: 지수 & 환율 시그널 -->
  <section class="card" style="margin-bottom: 18px;">
    <h2>지수 & 환율 시그널 <span class="tag">국내외 지수 · 환율 · 유가 · 금 · 은</span></h2>
    <div id="macroSignals" class="macro-grid"></div>
  </section>

<div class="top-layout">
  <!-- 왼쪽: 주요 이슈 (3열 x 2행) -->
  <section class="card issues-card">
    <div class="card-header-flex">
      <h2>주요 이슈</h2>
      <div class="controls">
        <select id="issueFilter" class="form-select">
          <option value="ALL">전체</option>
          <option value="GOOD">호재</option>
          <option value="BAD">악재</option>
          <option value="NEUTRAL">중립·혼합</option>
        </select>
        <select id="issueSort" class="form-select">
          <option value="IMPACT">영향도 강한 순</option>
          <option value="DUP">유사건 많은 순</option>
        </select>
      </div>
    </div>
    <div class="issues-wrapper">
      <div id="issues" class="issues"></div>
    </div>
  </section>

  <!-- 오른쪽: 연관 섹터 시그널 -->
  <section class="card sector-card">
    <div class="card-header-flex">
      <h2>주요 연관 섹터</h2>
    </div>
    <div id="sectorSignals" class="sector"></div>
  </section>
</div>

  <!-- 선택한 섹터의 종목 목록 카드 (기본 숨김) -->
  <section class="card" id="stockListCard" style="display: none;">
    <div class="card-header-flex">
      <h2><span id="selectedSectorName">선택된 섹터</span></h2>
    </div>
    <div id="stockListContainer" class="stocks-list-wrapper"></div>
  </section>

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

<!-- 종목 상세 모달 -->
<div id="stockDetailModal" class="modal-overlay" style="display: none;">
  <div class="modal-box" style="max-width: 800px; padding: 0;">
    <div class="modal-header" style="padding: 16px 20px;">
      <h3 id="stockModalTitle">종목명 (종목코드)</h3>
      <button id="stockModalCloseBtn" class="modal-close">&times;</button>
    </div>
    <div id="stockModalBody" class="modal-body" style="padding: 0; max-height: 70vh; overflow-y: auto;">
      <div class="loading-spinner">데이터 불러오는 중...</div>
    </div>
  </div>
</div>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js"></script>
<script charset="UTF-8" src="<%=request.getContextPath()%>/resources/js/dashboard.js?v=<%=System.currentTimeMillis()%>"></script>
<%@ include file="/common/footer.jsp" %>
