<%@ include file="/common/header.jsp" %>
<main class="wrap" style="max-width:820px; margin:24px auto;">
  <section class="card" style="padding:22px; margin-bottom:18px;">
    <div style="display:flex; justify-content:space-between; align-items:center; gap:12px; flex-wrap:wrap;">
      <h2 style="margin:0;">관심 알림 추가</h2>
      <span id="who" style="font-size:13px; color:#a0b4d0;"></span>
    </div>
    <p style="margin:8px 0 16px; font-size:13px; color:#8aa0c0; line-height:1.6;">
      종목·키워드·알림기준을 자유롭게 조합하세요 — <b>종목만</b>, <b>키워드만</b>, <b>종목+키워드</b>, <b>+알림기준</b> 모두 가능합니다.
    </p>

    <input id="cName" type="text" placeholder="이름 (선택 — 비우면 자동 생성)"
           style="width:100%; box-sizing:border-box; margin-bottom:16px; padding:10px 13px; border-radius:8px; border:1px solid #3d4e6e; background:#1a2540; color:#e0e6f0;">

    <div style="font-size:13px; color:#9fc0ff; font-weight:700; margin-bottom:6px;">📈 종목 <span style="color:#7090b0; font-weight:400;">(선택, 여러 개 가능)</span></div>
    <div style="position:relative; margin-bottom:8px;">
      <div style="display:flex; gap:8px;">
        <input id="cStock" type="text" autocomplete="off" placeholder="종목명 또는 코드 검색 (예: 삼성전자, 005930)"
               style="flex:1; padding:10px 13px; border-radius:8px; border:1px solid #3d4e6e; background:#1a2540; color:#e0e6f0;">
        <button id="cStockAdd" class="btn">담기</button>
      </div>
      <div id="cStockSuggest" style="display:none; position:absolute; left:0; right:74px; top:46px; background:#16203a; border:1px solid #2a3a55; border-radius:8px; max-height:240px; overflow-y:auto; z-index:50;"></div>
    </div>
    <div id="cStockChips" style="display:flex; flex-wrap:wrap; gap:8px; margin-bottom:18px;"></div>

    <div style="font-size:13px; color:#9fc0ff; font-weight:700; margin-bottom:6px;">🔑 키워드 <span style="color:#7090b0; font-weight:400;">(선택, 여러 개 가능)</span></div>
    <div style="display:flex; gap:8px; margin-bottom:8px;">
      <input id="cKw" type="text" placeholder="키워드 입력 (예: 반도체, 금리)"
             style="flex:1; padding:10px 13px; border-radius:8px; border:1px solid #3d4e6e; background:#1a2540; color:#e0e6f0;">
      <button id="cKwAdd" class="btn">담기</button>
    </div>
    <div id="cKwChips" style="display:flex; flex-wrap:wrap; gap:8px; margin-bottom:18px;"></div>

    <div style="display:flex; gap:18px; flex-wrap:wrap; align-items:center; background:#16203a; border:1px solid #2a3a55; border-radius:8px; padding:12px; margin-bottom:16px;">
      <span style="font-size:13px; color:#9fc0ff; font-weight:700;">🔔 알림기준</span>
      <label style="font-size:13px;color:#c8d8e8;">호재/악재 |점수| ≥
        <select id="cImpact" style="margin-left:4px; padding:5px 8px; border-radius:6px; background:#1a2540; color:#e0e6f0; border:1px solid #3d4e6e;">
          <option value="2">2</option><option value="3">3</option><option value="4" selected>4</option><option value="5">5</option>
        </select>
      </label>
      <label style="font-size:13px;color:#c8d8e8;">급변 ±
        <input id="cPct" type="number" step="0.5" min="0.5" max="30" value="5" style="width:64px; margin-left:4px; padding:5px 8px; border-radius:6px; background:#1a2540; color:#e0e6f0; border:1px solid #3d4e6e;"> %
      </label>
    </div>

    <button id="cCreate" class="btn primary" style="width:100%;">＋ 관심 알림 추가</button>
    <div id="cMsg" style="margin-top:8px; font-size:13px; color:#ff5470; min-height:16px;"></div>
  </section>

  <section class="card" id="kakaoCard" style="padding:14px 20px; margin-bottom:18px;">
    <div style="display:flex; align-items:center; gap:12px; flex-wrap:wrap;">
      <span style="font-size:14px; font-weight:700; color:#fee500;">💬 카카오톡 알림</span>
      <span id="kakaoStatus" style="font-size:13px; color:#a0b4d0;">상태 확인 중…</span>
      <span style="flex:1;"></span>
      <span id="kakaoActions"></span>
    </div>
    <div id="kakaoMsg" style="margin-top:8px; font-size:12px; min-height:14px; color:#19c37d;"></div>
  </section>

  <h3 style="margin:18px 4px 12px; color:#dce6f5;">내 관심 알림 목록</h3>
  <div id="setList"></div>
</main>

<script src="<%=request.getContextPath()%>/resources/js/watchlist.js?v=<%=System.currentTimeMillis()%>"></script>
<%@ include file="/common/footer.jsp" %>
