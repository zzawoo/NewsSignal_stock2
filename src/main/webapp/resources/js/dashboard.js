/* 대시보드 클라이언트
 * - 수집 버튼: CSRF 토큰을 헤더에 담아 POST /collect/run
 * - 데이터: GET /api/dashboard
 * - XSS 방지: textContent/esc() 사용 */
(function () {
  var ctx = document.body.getAttribute('data-ctx') || '';
  var csrf = (document.querySelector('meta[name="csrf-token"]') || {}).content || '';

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  var LABEL = { GOOD: '호재', BAD: '악재', NEUTRAL: '중립', MIXED: '혼합' };
  var allIssuesData  = [];
  var allSectorsData = [];
  var lastMacroSignals = [];
  var currentSectorStocks = [];
  var CHUNK_SIZE = 15; // 한 번에 KIS API를 호출할 종목 수

  /* ───────────── 데이터 로드 ───────────── */
  function load() {
    Promise.all([
      fetch(ctx + '/api/dashboard').then(function (r) { return r.json(); }),
      fetch(ctx + '/api/macro/prices').then(function (r) { return r.json(); }).catch(function() { return {}; })
    ])
    .then(function (results) {
      var dashData = results[0];
      var macroData = results[1] && results[1].prices ? results[1].prices : {};
      render(dashData, macroData);
    })
    .catch(function () {
      document.getElementById('issues').innerHTML =
        '<div class="empty">데이터를 불러오지 못했습니다. 다시 새로고침 시도하세요.</div>';
    });
  }

  function render(data, macroData) {
    allIssuesData  = (data.topIssues || []).concat(data.moreIssues || []);
    allSectorsData = data.sectors || [];
    lastMacroSignals = data.macroSignals || [];
    renderFilteredIssues();
    renderMacro(lastMacroSignals, macroData || {});
    renderSectors(allSectorsData);
  }

  /* ───────────── 이슈 카드 ───────────── */
  function issueRow(it) {
    var sectors = (it.sectors || '').split(',').filter(Boolean)
      .map(function (s) { return '<span class="chip">' + esc(s.trim()) + '</span>'; }).join('');
    var sign = it.score > 0 ? '+' : '';
    var summaryText = it.summary || it.title;
    return '<div class="item" data-group-id="' + it.id + '" style="cursor:pointer;">'
      + '<span class="sig ' + esc((it.type || '').toLowerCase()) + '">' + (LABEL[it.type] || '-') + '</span>'
      + '<h3 class="item-title">' + esc(it.title) + '</h3>'
      + '<p class="item-summary">' + esc(summaryText) + '</p>'
      + '<div class="meta">영향도 ' + sign + esc(it.score) + ' · 유사 ' + esc(it.dup) + '건</div>'
      + '<div class="chips">' + sectors + '</div></div>';
  }

  /* ───────────── 거시경제 ───────────── */
  function renderMacro(list, macroData) {
    var el = document.getElementById('macroSignals');
    var required = ['코스피', '코스닥', '환율', '유가', '금', '은'];
    var macroKeys = {'코스피':'KOSPI', '코스닥':'KOSDAQ', '환율':'환율', '유가':'유가', '금':'금', '은':'은'};
    var map = {};
    (list || []).forEach(function (r) { map[r.name] = r; });
    var html = '';
    required.forEach(function (req) {
      var r = map[req];
      var mKey = macroKeys[req];
      var mData = (macroData && macroData[mKey]) || {};
      
      var priceStr = mData.price && mData.price !== '-' ? mData.price : '-';
      var signCode = mData.sign || '3';
      var priceColor = 'var(--neu)';
      var arrow = '';
      // 종목처럼 상승은 빨간색(var(--bad)가 index.css에선 붉은색), 하락은 파란/초록색(var(--good))
      if (signCode === '2' || signCode === '1') { priceColor = 'var(--bad)'; arrow = '▲'; }
      else if (signCode === '5' || signCode === '4') { priceColor = 'var(--good)'; arrow = '▼'; }
      
      var diffStr = mData.ratio && mData.ratio !== '0.00' ? (arrow + ' ' + mData.diff + ' (' + mData.ratio + '%)') : '';

      html += '<div class="m-item">';
      html += '  <div class="m-name">' + esc(req) + '</div>';
      html += '  <div class="m-price" style="font-size:18px; font-weight:700; margin:4px 0; color:' + priceColor + '">' + esc(priceStr) + ' <span style="font-size:12px; font-weight:normal;">' + esc(diffStr) + '</span></div>';
      html += '</div>';
    });
    el.innerHTML = html;
  }

  /* ───────────── 섹터 카드 ───────────── */
  function renderSectors(list) {
    var el = document.getElementById('sectorSignals');
    if (!list || !list.length) {
      el.innerHTML = '<div class="empty">섹터 분석 정보가 없습니다.</div>';
      return;
    }
    el.innerHTML = list.map(function (r, i) {
      var tot = r.total || 1;
      var avgColor = r.avg > 0 ? 'var(--good)' : r.avg < 0 ? 'var(--bad)' : 'var(--neu)';
      var sign = r.avg > 0 ? '+' : '';
      var stockCount = (r.stocks || []).length;
      var stockBadge = stockCount > 0
        ? '<span style="margin-left:8px; background:#3d4e6e; color:#a0b4d0; font-size:11px; padding:2px 8px; border-radius:10px; font-weight:600;">' + stockCount + '개 종목</span>'
        : '';
      return '<div class="srow" data-index="' + i + '">'
        + '<div class="line1">'
        + '  <span class="sname">' + esc(r.name) + '</span>'
        + '  <span class="stype">' + esc(r.type) + '</span>'
        + stockBadge
        + '  <span class="savg" style="color:' + avgColor + '">평균 ' + sign + esc(r.avg.toFixed(1)) + '</span>'
        + '</div>'
        + '<div class="gbbar">'
        + '  <div class="g" style="width:' + (r.good / tot * 100) + '%"></div>'
        + '  <div class="n" style="width:' + (r.neu  / tot * 100) + '%"></div>'
        + '  <div class="b" style="width:' + (r.bad  / tot * 100) + '%"></div>'
        + '</div>'
        + '<div class="gbcount">'
        + '  <span><span class="d" style="background:var(--good)"></span>호재 ' + r.good + '</span>'
        + '  <span><span class="d" style="background:var(--neu)"></span>중립 ' + r.neu  + '</span>'
        + '  <span><span class="d" style="background:var(--bad)"></span>악재 ' + r.bad  + '</span>'
        + '</div>'
        + '</div>';
    }).join('');
  }

  /* ───────────── 모달(뉴스 기사) ───────────── */
  var modal         = document.getElementById('articlesModal');
  var modalCloseBtn = document.getElementById('modalCloseBtn');
  var modalTitle    = document.getElementById('modalTitle');
  var modalBody     = document.getElementById('modalBody');

  function openArticlesModal(groupId, groupTitle) {
    modalTitle.textContent = groupTitle;
    modalBody.innerHTML = '<div class="loading-spinner">뉴스 불러오는 중...</div>';
    modal.style.display = 'flex';

    fetch(ctx + '/api/group/articles?groupId=' + groupId)
      .then(function (r) { if (!r.ok) throw new Error(); return r.json(); })
      .then(function (list) {
        if (!list || !list.length) {
          modalBody.innerHTML = '<div class="empty">상세 뉴스가 없습니다.</div>';
          return;
        }
        modalBody.innerHTML = list.map(function (n) {
          var repBadge = n.duplicate_yn === 'N' ? '<span class="rep-badge">대표</span>' : '';
          var link = n.naver_link || n.original_link || '#';
          return '<div class="modal-item" data-id="' + n.id + '" data-link="' + esc(link) + '">'
               + '  <h4>' + esc(n.title) + '</h4>'
               + '  <div class="mfoot">' + repBadge
               + '    <span class="press">' + esc(n.press) + '</span>'
               + '    <span class="pubdate">' + esc(n.pub_date || '') + '</span>'
               + '  </div></div>';
        }).join('');

        modalBody.querySelectorAll('.modal-item').forEach(function (item) {
          item.addEventListener('click', function () {
            var url = this.getAttribute('data-link');
            var id  = this.getAttribute('data-id');
            if (url && url !== '#') {
              window.open(url.indexOf('mock.link') !== -1 ? ctx + '/user/mock_article.jsp?id=' + id : url, '_blank');
            }
          });
        });
      })
      .catch(function () {
        modalBody.innerHTML = '<div class="empty">뉴스를 불러오지 못했습니다.</div>';
      });
  }

  if (modalCloseBtn) modalCloseBtn.addEventListener('click', function () { modal.style.display = 'none'; });
  if (modal) modal.addEventListener('click', function (e) { if (e.target === modal) modal.style.display = 'none'; });

  document.getElementById('issues') && document.getElementById('issues').addEventListener('click', function (e) {
    var item = e.target.closest('.item');
    if (item) {
      var groupId = item.getAttribute('data-group-id');
      if (groupId) openArticlesModal(groupId, item.querySelector('h3').textContent);
    }
  });

  /* ───────────── 종목 주가/재무 데이터 로드 ───────────── */
  function parseDartAmount(str) {
    if (!str) return null;
    var n = Number(String(str).replace(/,/g, ''));
    return isNaN(n) ? null : n;
  }

  function fetchStockListData(rowEl) {
    var code = rowEl.getAttribute('data-code');
    fetch(ctx + '/api/stock/info?type=list&code=' + encodeURIComponent(code))
      .then(function (res) { return res.json(); })
      .then(function (data) {
        /* 현재가·거래량·시가총액 */
        if (data.price) {
          var p = data.price;
          var sign = p.prdy_vrss_sign || '3';
          var isUp   = (sign === '1' || sign === '2');
          var isDown = (sign === '4' || sign === '5');
          var color  = isUp ? '#ff4d4d' : isDown ? '#19c37d' : '#e0e6f0';
          var prdy   = Number(p.prdy_vrss) || 0;
          var vrss   = isUp ? '+' + prdy.toLocaleString() : isDown ? prdy.toLocaleString() : '0';
          rowEl.querySelector('.st-price').innerHTML =
            '<span style="color:' + color + '; font-weight:600;">' + Number(p.stck_prpr).toLocaleString() + '</span>';
          rowEl.querySelector('.st-change').innerHTML =
            '<span style="color:' + color + ';">' + vrss + '<br><span style="font-size:11px;">(' + p.prdy_ctrt + '%)</span></span>';
          rowEl.querySelector('.st-vol').innerHTML = Number(p.acml_vol).toLocaleString();
          rowEl.querySelector('.st-amt').innerHTML = Math.floor(Number(p.acml_tr_pbmn) / 100000000).toLocaleString() + '억';
          rowEl.querySelector('.st-cap').innerHTML = Number(p.hts_avls).toLocaleString() + '억';
        }
        /* 매출액·영업이익 (DART) */
        if (data.finance && data.finance.list) {
          var salesCurr = null, salesPrev = null, opCurr = null, opPrev = null;
          data.finance.list.forEach(function (item) {
            if (item.account_nm === '매출액' || item.account_nm === '영업수익') {
              salesCurr = parseDartAmount(item.thstrm_amount);
              salesPrev = parseDartAmount(item.frmtrm_amount);
            } else if (item.account_nm === '영업이익' || item.account_nm === '영업이익(손실)') {
              opCurr = parseDartAmount(item.thstrm_amount);
              opPrev = parseDartAmount(item.frmtrm_amount);
            }
          });
          rowEl.querySelector('.st-sales').innerHTML =
            salesCurr !== null ? Math.floor(salesCurr / 100000000).toLocaleString() + '억' : '-';
          if (opCurr !== null) {
            var opStr = Math.floor(opCurr / 100000000).toLocaleString() + '억';
            if (opPrev !== null && opPrev !== 0) {
              var gr = ((opCurr - opPrev) / Math.abs(opPrev) * 100).toFixed(1);
              opStr += '<br><span style="font-size:11px; color:' + (gr > 0 ? '#ff4d4d' : '#19c37d') + ';">'
                     + (gr > 0 ? '+' : '') + gr + '%</span>';
            }
            rowEl.querySelector('.st-op').innerHTML = opStr;
          }
        }
      })
      .catch(function () { /* silent — 개별 종목 실패는 무시 */ });
  }

  /* ───────────── 종목 테이블 HTML 빌드 ───────────── */
  function buildStockTableHTML(stocks) {
    if (!stocks || stocks.length === 0) {
      return '<div class="empty" style="padding:20px;">관련 종목 정보가 없습니다.</div>';
    }
    var thL = 'position:sticky; top:0; z-index:10; padding:10px 12px; text-align:left;  white-space:nowrap; font-size:12px; font-weight:700; color:#e0e6f0; background:#2a3550; border-bottom:2px solid #3d4e6e;';
    var th  = 'position:sticky; top:0; z-index:10; padding:10px 12px; text-align:right; white-space:nowrap; font-size:12px; font-weight:700; color:#e0e6f0; background:#2a3550; border-bottom:2px solid #3d4e6e;';
    var tdL = 'padding:10px 12px; text-align:left;  white-space:nowrap; color:#e0e6f0;';
    var td  = 'padding:10px 12px; text-align:right; white-space:nowrap; color:#e0e6f0;';

    var html = '<div class="stock-table-wrapper" style="overflow-x:auto; max-height:550px; overflow-y:auto;">'
             + '<table id="sectorStockTable" style="width:100%; border-collapse:collapse; font-size:14px;">'
             + '<colgroup>'
             + '<col style="min-width:130px;"><col style="min-width:90px;"><col style="min-width:110px;">'
             + '<col style="min-width:90px;"><col style="min-width:100px;"><col style="min-width:100px;">'
             + '<col style="min-width:90px;"><col style="min-width:100px;">'
             + '</colgroup>'
             + '<thead><tr>'
             + '<th style="' + thL + '">종목명</th>'
             + '<th style="' + th  + '">현재가</th>'
             + '<th style="' + th  + '">전일대비</th>'
             + '<th style="' + th  + '">거래량</th>'
             + '<th style="' + th  + '">거래대금</th>'
             + '<th style="' + th  + '">시가총액</th>'
             + '<th style="' + th  + '">매출액</th>'
             + '<th style="' + th  + '">영업이익</th>'
             + '</tr></thead><tbody>';

    html += stocks.map(function (s) {
      return '<tr class="stock-row" data-code="' + esc(s.code) + '" style="cursor:pointer; border-bottom:1px solid #2a3550;">'
           + '<td style="' + tdL + ' font-weight:600;">' + esc(s.name)
           + '<br><span style="font-size:11px; color:#7090b0;">' + esc(s.code) + '</span></td>'
           + '<td class="st-price"  style="' + td + '">-</td>'
           + '<td class="st-change" style="' + td + '">-</td>'
           + '<td class="st-vol"    style="' + td + '">-</td>'
           + '<td class="st-amt"    style="' + td + '">-</td>'
           + '<td class="st-cap"    style="' + td + '">-</td>'
           + '<td class="st-sales"  style="' + td + '">-</td>'
           + '<td class="st-op"     style="' + td + '">-</td>'
           + '</tr>'
           + '<tr class="stock-details-row" style="display:none;">'
           + '<td colspan="8" style="padding:0; border:none;">'
           + '<div class="stock-details" style="padding:16px; background:#1a2540; border-bottom:1px solid #3d4e6e;"></div>'
           + '</td></tr>';
    }).join('');

    html += '</tbody></table></div>';
    return html;
  }

  /* ───────────── 스크롤 기반 데이터 로딩 ───────────── */
  var fetchQueue = [];
  var isFetching = false;
  
  function processFetchQueue() {
    if (isFetching || fetchQueue.length === 0) return;
    isFetching = true;
    var rowEl = fetchQueue.shift();
    fetchStockListData(rowEl);
    setTimeout(function() {
      isFetching = false;
      processFetchQueue();
    }, 250); // 0.25초 간격 (초당 4회 API 제한 맞춤)
  }

  function observeAllRows(container) {
    var wrapper = container.querySelector('.stock-table-wrapper');
    if (!wrapper) return;
    var observer = new IntersectionObserver(function(entries, obs) {
      entries.forEach(function(entry) {
        if (entry.isIntersecting) {
          var rowEl = entry.target;
          if (!rowEl.hasAttribute('data-loaded')) {
            rowEl.setAttribute('data-loaded', 'true');
            fetchQueue.push(rowEl);
            processFetchQueue();
          }
        }
      });
    }, { root: wrapper, rootMargin: '300px 0px', threshold: 0 });

    var rows = container.querySelectorAll('.stock-row:not([data-loaded])');
    rows.forEach(function(row) {
      observer.observe(row);
    });
  }

  /* ───────────── 섹터 클릭 → 종목 패널 ───────────── */
  var sectorEl = document.getElementById('sectorSignals');
  if (sectorEl) {
    sectorEl.addEventListener('click', function (e) {
      var srow = e.target.closest('.srow');
      if (!srow) return;
      var idx    = parseInt(srow.getAttribute('data-index'), 10);
      var sector = allSectorsData[idx];
      if (!sector) return;

      currentSectorStocks = sector.stocks || [];
      var total = currentSectorStocks.length;

      /* 섹터명 + 종목 수 */
      document.getElementById('selectedSectorName').textContent =
        sector.name + ' 관련 종목 (총 ' + total + '개)';

      var container = document.getElementById('stockListContainer');

      /* 검색 바 */
      var searchBarHTML = total > 5
        ? '<div style="padding:10px 0 10px; display:flex; gap:10px; align-items:center;">'
        + '  <input id="stockSearchInput" type="text" placeholder="종목명 / 종목코드 검색..."'
        + '    style="flex:1; padding:9px 14px; border-radius:8px; border:1px solid #3d4e6e;'
        + '           background:#1a2540; color:#e0e6f0; font-size:14px; outline:none;">'
        + '  <span style="color:#7090b0; font-size:13px; white-space:nowrap;">총 ' + total + '개</span>'
        + '</div>'
        : '';

      container.innerHTML = searchBarHTML + buildStockTableHTML(currentSectorStocks);

      /* 카드 표시 + 스크롤 */
      document.getElementById('stockListCard').style.display = 'block';
      document.getElementById('stockListCard').scrollIntoView({ behavior: 'smooth' });

      /* 스크롤 시 로딩 관찰 */
      if (total > 0) observeAllRows(container);

      /* 검색 입력 */
      var searchInput = document.getElementById('stockSearchInput');
      if (searchInput) {
        searchInput.addEventListener('input', function () {
          var q = this.value.trim().toLowerCase();
          var filtered = q
            ? currentSectorStocks.filter(function (s) {
                return s.name.toLowerCase().indexOf(q) >= 0 || s.code.indexOf(q) >= 0;
              })
            : currentSectorStocks;

          /* 기존 테이블만 교체 */
          var oldTable = container.querySelector('#sectorStockTable');
          if (oldTable) {
            var wrapper = oldTable.parentElement; // div.stock-table-wrapper
            wrapper.outerHTML = buildStockTableHTML(filtered);
          }
          if (filtered.length > 0) observeAllRows(container);
        });
      }
    });
  }

  /* ───────────── 탭 클릭 공통 이벤트 (모달 포함) ───────────── */
  document.body.addEventListener('click', function (e) {
    var tabBtn = e.target.closest('.tab-btn');
    if (tabBtn) {
      var targetId      = tabBtn.getAttribute('data-target');
      var parentDetails = tabBtn.closest('.modal-body, .stock-details');
      if (!parentDetails) return;
      parentDetails.querySelectorAll('.tab-btn').forEach(function (b) {
        b.style.fontWeight = 'normal'; b.style.background = '#2a3550'; b.style.color = '#a0b4d0';
      });
      tabBtn.style.fontWeight = 'bold'; tabBtn.style.background = '#3d4e6e'; tabBtn.style.color = '#e0e6f0';
      parentDetails.querySelectorAll('.tab-content').forEach(function (c) { c.style.display = 'none'; });
      parentDetails.querySelector('.' + targetId).style.display = 'block';
    }
  });

  /* ───────────── 종목 모달 닫기 ───────────── */
  var stockModal = document.getElementById('stockDetailModal');
  var stockModalCloseBtn = document.getElementById('stockModalCloseBtn');
  if (stockModalCloseBtn) stockModalCloseBtn.addEventListener('click', function () { stockModal.style.display = 'none'; });
  if (stockModal) stockModal.addEventListener('click', function (e) { if (e.target === stockModal) stockModal.style.display = 'none'; });

  /* ───────────── 종목 행 클릭 → 팝업 표시 ───────────── */
  var stockListContainer = document.getElementById('stockListContainer');
  if (stockListContainer) {
    stockListContainer.addEventListener('click', function (e) {
      var stockRow = e.target.closest('.stock-row');
      if (!stockRow) return;

      var code = stockRow.getAttribute('data-code');
      // 종목명 추출 (첫번째 td의 첫번째 텍스트 노드)
      var nameNode = stockRow.querySelector('td').firstChild;
      var stockName = nameNode ? nameNode.textContent.trim() : '종목 상세';

      document.getElementById('stockModalTitle').textContent = stockName + ' (' + code + ')';
      var detailsDiv = document.getElementById('stockModalBody');
      detailsDiv.innerHTML = '<div style="color:#7090b0; text-align:center; padding:20px;">상세 데이터 불러오는 중...</div>';
      stockModal.style.display = 'flex';

      fetch(ctx + '/api/stock/info?type=details&code=' + encodeURIComponent(code))
        .then(function (res) { return res.json(); })
        .then(function (data) {
          /* 탭 UI */
          var tabStyle     = 'padding:8px 18px; cursor:pointer; border-radius:6px 6px 0 0; font-size:13px; font-weight:700; border:none; margin-right:4px;';
          var activeStyle  = tabStyle + ' background:#3d4e6e; color:#e0e6f0;';
          var inactiveStyle= tabStyle + ' background:#1a2540; color:#7090b0;';

          var html = '<div style="display:flex; border-bottom:2px solid #3d4e6e; margin-bottom:0;">'
                   + '  <button class="tab-btn" data-target="tab-chart"   style="' + activeStyle   + '">📈 주가 차트</button>'
                   + '  <button class="tab-btn" data-target="tab-company" style="' + inactiveStyle + '">🏢 기업 정보</button>'
                   + '</div>'
                   + '<div style="background:#1e2a40; padding:18px; border-radius:0 0 6px 6px;">'
                   + '  <div class="tab-content tab-chart" style="display:block; height:260px;">'
                   + '    <canvas id="chart-' + esc(code) + '"></canvas>'
                   + '  </div>'
                   + '  <div class="tab-content tab-company" style="display:none; font-size:14px; line-height:2; color:#c8d8e8;">';

          if (data.company) {
            var c = data.company;
            html += buildInfoRow('대표자',   c.ceo_nm);
            html += buildInfoRow('설립일',   c.est_dt);
            html += buildInfoRow('업종',     c.induty_nm);
            html += buildInfoRow('법인구분', c.corp_cls);
            html += buildInfoRow('주소',     c.adres);
            if (c.hm_url) {
              html += '<div><strong style="color:#a0b4d0;">홈페이지:</strong> '
                    + '<a href="' + esc(c.hm_url) + '" target="_blank" style="color:#4d9eff;">' + esc(c.hm_url) + '</a></div>';
            }
          } else {
            html += '<div style="color:#7090b0;">기업 정보(DART)를 조회할 수 없습니다.</div>';
          }

          html += '  </div></div>';
          detailsDiv.innerHTML = html;

          /* 차트 렌더 */
          if (data.chart && data.chart.output2 && data.chart.output2.length > 0) {
            var canvasEl   = document.getElementById('chart-' + code);
            var ctxCanvas  = canvasEl.getContext('2d');
            var chartData  = data.chart.output2.slice(0, 60).reverse();
            var labels     = chartData.map(function (d) {
              var dt = d.stck_bsop_date;
              return dt.substring(4, 6) + '/' + dt.substring(6, 8);
            });
            var prices     = chartData.map(function (d) { return Number(d.stck_clpr); });
            var volumes    = chartData.map(function (d) { return Number(d.acml_vol);  });

            new Chart(ctxCanvas, {
              type: 'line',
              data: {
                labels: labels,
                datasets: [{
                  label: '종가',
                  data: prices,
                  borderColor: 'rgba(77, 192, 192, 1)',
                  backgroundColor: 'rgba(77, 192, 192, 0.08)',
                  borderWidth: 2,
                  fill: true,
                  tension: 0.3,
                  pointRadius: 2,
                  yAxisID: 'yPrice'
                }, {
                  label: '거래량',
                  data: volumes,
                  type: 'bar',
                  backgroundColor: 'rgba(100, 140, 220, 0.3)',
                  borderColor: 'rgba(100, 140, 220, 0.6)',
                  borderWidth: 1,
                  yAxisID: 'yVol'
                }]
              },
              options: {
                responsive: true,
                maintainAspectRatio: false,
                interaction: { intersect: false, mode: 'index' },
                plugins: { legend: { labels: { color: '#a0b4d0' } } },
                scales: {
                  x:      { ticks: { color: '#7090b0', maxTicksLimit: 10 }, grid: { color: '#2a3550' } },
                  yPrice: { position: 'left',  ticks: { color: '#a0b4d0' }, grid: { color: '#2a3550' }, beginAtZero: false },
                  yVol:   { position: 'right', ticks: { color: '#7090b0', display: false }, grid: { drawOnChartArea: false } }
                }
              }
            });
          } else {
            var canvas = document.getElementById('chart-' + code);
            if (canvas) canvas.outerHTML = '<div style="color:#7090b0; text-align:center; padding:20px;">차트 데이터 없음 (영업일 외 또는 KIS API 제한)</div>';
          }
        })
        .catch(function (err) {
          console.error(err);
          var errMsg = err ? (err.stack || err.message || String(err)) : "Unknown Error";
          detailsDiv.innerHTML = '<div style="color:#ff5470; text-align:center; padding:16px;">상세 정보를 불러오지 못했습니다.<br><br><span style="font-size:12px; color:#a0b4d0;">Error: ' + esc(errMsg) + '</span></div>';
        });
    });
  }

  function buildInfoRow(label, value) {
    return '<div><strong style="color:#a0b4d0;">' + label + ':</strong> ' + esc(value) + '</div>';
  }

  /* ───────────── 수집·분석 버튼 ───────────── */
  var collectBtn = document.getElementById('collectBtn');
  if (collectBtn) {
    collectBtn.addEventListener('click', function () {
      var btn = this;
      btn.disabled = true; btn.textContent = '수집 중…';
      fetch(ctx + '/collect/run', { method: 'POST', headers: { 'X-CSRF-Token': csrf } })
        .then(function () {
          setTimeout(function () {
            load(); btn.disabled = false; btn.textContent = '수집·분석 실행';
          }, 2500);
        })
        .catch(function () { btn.disabled = false; btn.textContent = '수집·분석 실행'; });
    });
  }

  /* ───────────── 이슈 필터·정렬 ───────────── */
  function renderFilteredIssues() {
    var filterEl = document.getElementById('issueFilter');
    var sortEl   = document.getElementById('issueSort');
    var filterVal = filterEl ? filterEl.value : 'ALL';
    var sortVal   = sortEl   ? sortEl.value   : 'IMPACT';

    var filtered = allIssuesData.filter(function (it) {
      if (filterVal === 'ALL') return true;
      if (filterVal === 'NEUTRAL' && (it.type === 'NEUTRAL' || it.type === 'MIXED')) return true;
      return it.type === filterVal;
    });

    filtered.sort(function (a, b) {
      if (sortVal === 'IMPACT') return Math.abs(b.score) - Math.abs(a.score) || b.dup - a.dup;
      if (sortVal === 'DUP')    return b.dup - a.dup || Math.abs(b.score) - Math.abs(a.score);
      return 0;
    });

    var el = document.getElementById('issues');
    if (el) el.innerHTML = filtered.map(issueRow).join('') || '<div class="empty">조건에 맞는 이슈가 없습니다.</div>';
  }

  var filterEl = document.getElementById('issueFilter');
  var sortEl   = document.getElementById('issueSort');
  if (filterEl) filterEl.addEventListener('change', renderFilteredIssues);
  if (sortEl)   sortEl.addEventListener('change',   renderFilteredIssues);

  /* ───────────── 실시간 업데이트 (15초 주기) ───────────── */
  function refreshRealtimeData() {
    // 거시경제 지표 갱신
    fetch(ctx + '/api/macro/prices')
      .then(function(r) { return r.json(); })
      .then(function(res) {
        var macroData = res.prices || {};
        renderMacro(lastMacroSignals, macroData);
      })
      .catch(function(){});

    // 표시된 종목 가격 갱신 (이미 한 번 로드된 종목 대상)
    var container = document.getElementById('stockListContainer');
    if (container) {
      var rows = container.querySelectorAll('.stock-row[data-loaded="true"]');
      rows.forEach(function(row) {
        if (fetchQueue.indexOf(row) === -1) {
          fetchQueue.push(row);
        }
      });
      processFetchQueue();
    }
  }

  // 15초마다 실시간 갱신 실행
  setInterval(refreshRealtimeData, 15000);

  load();
})();
