<%@ page pageEncoding="UTF-8" %>
<%@ include file="/common/header.jsp" %>
<%-- 사용자 대시보드 (계획서 8장). 데이터는 /api/dashboard 에서 비동기 로드 --%>

<div class="deck">
  <button id="collectBtn" class="btn primary">분석·요약 실행</button>
  <span class="hint">실 운영에서는 네이버 검색 API를 서버(Servlet)에서 호출합니다.</span>
  <div class="deck-search" style="margin-left:auto; position:relative; flex:0 1 380px; min-width:200px;">
    <input id="globalStockSearch" type="text" autocomplete="off"
           placeholder="🔍 종목명 · 코드 검색 (예: 삼성전자, 005930)"
           style="width:100%; box-sizing:border-box; padding:9px 14px; border-radius:8px; border:1px solid #3d4e6e; background:#1a2540; color:#e0e6f0; font-size:13.5px; outline:none;">
    <div id="globalStockSuggest"
         style="display:none; position:absolute; left:0; right:0; top:44px; background:#16203a; border:1px solid #2a3a55; border-radius:8px; max-height:340px; overflow-y:auto; box-shadow:0 8px 24px rgba(0,0,0,.5); z-index:2000;"></div>
  </div>
</div>

<main class="wrap">
  <!-- 상단: 오늘의 증시 지표 (+ 장중 시황: 투자자 수급·관전포인트 인라인 통합) -->
  <section class="card" style="margin-bottom: 18px;">
    <h2>오늘의 증시 지표 <span class="tag">국내외 지수 · 환율 · 유가 · 금 · 은</span></h2>
    <div id="macroSignals" class="macro-grid"></div>
    <div id="marketBriefInline" style="display:none; padding:14px 20px 16px; border-top:1px solid var(--line);"></div>
  </section>

  <!-- 오늘의 핫이슈 AI 요약 (hotissue.enabled=N 이면 자동 숨김) -->
  <section class="card" id="hotIssueCard" style="margin-bottom: 18px; padding: 16px 20px 20px; display:none;"></section>

<div class="top-layout">
  <!-- 왼쪽: 주요 이슈 (3열 x 2행) -->
  <section class="card issues-card">
    <div class="card-header-flex">
      <h2>주요 이슈</h2>
      <div class="controls">
        <select id="issuePeriod" class="form-select">
          <option value="all">전체</option>
          <option value="today">당일</option>
          <option value="yesterday">전일</option>
        </select>
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

  <!-- 시총 히트맵 + 오늘의 종목 (2단 나란히) -->
  <div class="market-row">
    <!-- 시총 히트맵 (트리맵) -->
    <section class="card" id="heatmapCard" style="padding:16px 20px 20px;">
      <div style="display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:8px; margin-bottom:12px;">
        <h2 style="margin:0;">🗺️ 시총 히트맵 <span style="font-size:12px; color:#7d92b0; font-weight:400;">시총 비례 · 클릭 = 상세</span></h2>
        <div id="heatmapTabs" style="display:flex; gap:6px;">
          <button class="hm-tab" data-mk="kospi">코스피</button>
          <button class="hm-tab" data-mk="kosdaq">코스닥</button>
        </div>
      </div>
      <div id="heatmapBox" style="width:100%; height:404px; position:relative;"></div>
    </section>

    <!-- 오늘의 종목 랭킹 -->
    <section class="card" id="moversCard" style="padding:16px 20px 20px;">
      <div style="display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:8px; margin-bottom:12px;">
        <h2 style="margin:0;">📊 오늘의 종목</h2>
        <div id="moversTabs" style="display:flex; gap:6px;">
          <button class="mv-tab" data-metric="value">거래대금</button>
          <button class="mv-tab" data-metric="volume">거래량</button>
          <button class="mv-tab" data-metric="gainers">급등</button>
          <button class="mv-tab" data-metric="losers">급락</button>
        </div>
      </div>
      <div id="moversBox" style="max-height:404px; overflow-y:auto;"></div>
    </section>
  </div>

  <!-- 실적발표 피드 -->
  <section class="card" id="earningsCard" style="margin-bottom:18px; padding:16px 20px 20px; display:none;"></section>

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
  <div class="modal-box" style="max-width: 1160px; width: 96vw; padding: 0;">
    <div class="modal-header" style="padding: 16px 20px;">
      <h3 id="stockModalTitle">종목명 (종목코드)</h3>
      <button id="stockModalCloseBtn" class="modal-close">&times;</button>
    </div>
    <div id="stockModalBody" class="modal-body" style="padding: 0; max-height: 70vh; overflow-y: auto;">
      <div class="loading-spinner">데이터 불러오는 중...</div>
    </div>
  </div>
</div>
<script src="<%=request.getContextPath()%>/resources/js/lightweight-charts.standalone.production.js"></script>
<script charset="UTF-8" src="<%=request.getContextPath()%>/resources/js/dashboard.js?v=<%=System.currentTimeMillis()%>"></script>
<%@ include file="/common/footer.jsp" %>
