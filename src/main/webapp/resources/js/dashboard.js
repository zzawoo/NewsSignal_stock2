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
  var currentPeriod  = 'all'; // 주요 이슈 기간 필터: all | today | yesterday
  var lastMacroSignals = [];
  var lastMacroData = {};
  var macroSparklines = {}; // 카드 미니 그래프용 시계열
  var currentSectorStocks = [];
  var CHUNK_SIZE = 15; // 한 번에 KIS API를 호출할 종목 수

  /* ───────────── 데이터 로드 ───────────── */
  function load() {
    Promise.all([
      fetch(ctx + '/api/dashboard?period=' + encodeURIComponent(currentPeriod)).then(function (r) { return r.json(); }),
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
    var rawIssues = (data.topIssues || []).concat(data.moreIssues || []);
    allIssuesData = [];
    var seen = {};
    for (var i = 0; i < rawIssues.length; i++) {
      if (!seen[rawIssues[i].id]) {
        seen[rawIssues[i].id] = true;
        allIssuesData.push(rawIssues[i]);
      }
    }
    allSectorsData = data.sectors || [];
    lastMacroSignals = data.macroSignals || [];
    lastMacroData = macroData || {};
    renderFilteredIssues();
    renderMacro(lastMacroSignals, lastMacroData);
    renderSectors(allSectorsData);
  }

  /* ───────────── 이슈 카드 ───────────── */
  function issueRow(it) {
    var sectors = (it.sectors || '').split(',').filter(Boolean)
      .map(function (s) { return '<span class="chip">' + esc(s.trim()) + '</span>'; }).join('');
    var sign = it.score > 0 ? '+' : '';
    var summaryText = it.summary || '요약 정보가 없습니다.';
    return '<div class="item" data-group-id="' + it.id + '" data-group-title="' + esc(it.title) + '" style="cursor:pointer;" title="클릭하여 관련 기사 목록 보기">'
      + '<span class="sig ' + esc((it.type || '').toLowerCase()) + '">' + (LABEL[it.type] || '-') + '</span>'
      + '<p class="item-summary" style="font-size:15px; color:#e0e6f0; font-weight:500; line-height:1.5; margin:8px 0; display:-webkit-box; -webkit-line-clamp:3; -webkit-box-orient:vertical; overflow:hidden;">' + esc(summaryText) + '</p>'
      + '<div class="meta">영향도 ' + sign + esc(it.score) + ' · 유사 ' + esc(it.dup) + '건</div>'
      + '<div class="chips">' + sectors + '</div></div>';
  }

  /* 카드용 미니 그래프(스파크라인) SVG */
  function macroSparkSVG(vals, color) {
    if (!vals || vals.length < 2) return '';
    var w = 140, h = 32, pad = 2, n = vals.length;
    var min = Math.min.apply(null, vals), max = Math.max.apply(null, vals), range = (max - min) || 1;
    var pts = [];
    for (var i = 0; i < n; i++) {
      var x = (pad + (i / (n - 1)) * (w - 2 * pad)).toFixed(1);
      var y = (pad + (1 - (vals[i] - min) / range) * (h - 2 * pad)).toFixed(1);
      pts.push(x + ',' + y);
    }
    var line = pts.join(' ');
    var area = line + ' ' + (w - pad).toFixed(1) + ',' + h + ' ' + pad.toFixed(1) + ',' + h;
    return '<svg viewBox="0 0 ' + w + ' ' + h + '" preserveAspectRatio="none" style="width:100%;height:32px;display:block;margin-top:6px;">'
      + '<polygon points="' + area + '" style="fill:' + color + ';fill-opacity:0.10;"/>'
      + '<polyline points="' + line + '" style="fill:none;stroke:' + color + ';stroke-width:1.5;" vector-effect="non-scaling-stroke" stroke-linejoin="round" stroke-linecap="round"/>'
      + '</svg>';
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

      var spark = macroSparkSVG((macroSparklines && macroSparklines[mKey]) || [], priceColor);

      html += '<div class="m-item">';
      html += '  <div class="m-name">' + esc(req) + '</div>';
      html += '  <div class="m-price" style="font-size:18px; font-weight:700; margin:4px 0; color:' + priceColor + '">' + esc(priceStr) + ' <span style="font-size:12px; font-weight:normal;">' + esc(diffStr) + '</span></div>';
      html += spark;
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
        modalBody.innerHTML = '<div class="empty">서버와 연결할 수 없습니다. 잠시 후 다시 시도해주세요.</div>';
      });
  }

  if (modalCloseBtn) modalCloseBtn.addEventListener('click', function () { modal.style.display = 'none'; });
  if (modal) modal.addEventListener('click', function (e) { if (e.target === modal) modal.style.display = 'none'; });

  document.getElementById('issues') && document.getElementById('issues').addEventListener('click', function (e) {
    var item = e.target.closest('.item');
    if (item) {
      var groupId = item.getAttribute('data-group-id');
      var groupTitle = item.getAttribute('data-group-title');
      if (groupId) openArticlesModal(groupId, groupTitle);
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
          // 거래량/거래대금/시총: 값이 있고 0보다 클 때만 갱신 (없으면 직전 값 유지 → 0 깜빡임 방지)
          if (p.acml_vol != null && Number(p.acml_vol) > 0) {
            rowEl.querySelector('.st-vol').innerHTML = Number(p.acml_vol).toLocaleString();
          }
          if (p.acml_tr_pbmn != null && Number(p.acml_tr_pbmn) > 0) {
            rowEl.querySelector('.st-amt').innerHTML = Math.floor(Number(p.acml_tr_pbmn) / 100000000).toLocaleString() + '억';
          }
          if (p.hts_avls != null && Number(p.hts_avls) > 0) {
            rowEl.querySelector('.st-cap').innerHTML = Number(p.hts_avls).toLocaleString() + '억';
          }
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

      container.innerHTML = searchBarHTML + '<div id="stockTableHost">' + buildStockTableHTML(currentSectorStocks) + '</div>';

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

          /* 테이블 호스트만 교체 (결과 0건이어도 복구 가능 → 검색 멈춤 버그 방지) */
          var host = document.getElementById('stockTableHost');
          if (host) {
            host.innerHTML = buildStockTableHTML(filtered);
            if (filtered.length > 0) observeAllRows(container);
          }
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

      renderStockDetail(code, detailsDiv);
    });
  }

  function buildInfoRow(label, value) {
    return '<div><strong style="color:#a0b4d0;">' + label + ':</strong> ' + esc(value) + '</div>';
  }

  /* ───────────── 종목 상세: 기업정보(DART) + 실시간 캔들차트(KIS) ─────────────
     - 기업정보: 24h 캐시(서버), 현재가/지표: 5초 캐시, 차트: 60초 캐시
     - 캔들/라인 토글, 이동평균(5/20/60), 볼린저밴드, RSI, 시간대(1·5·10분/일/주/월) */
  function renderStockDetail(code, host) {
    var st = { tf: 'D', type: 'candle', candles: [], chart: null, rsiChart: null,
               ind: { sma5: true, sma20: true, sma60: false, boll: false, rsi: false } };

    function num(v) { if (v === undefined || v === null) return null; var x = Number(String(v).replace(/,/g, '')); return isNaN(x) ? null : x; }
    function comma(v) { var x = num(v); return x === null ? '-' : x.toLocaleString(); }
    function won(v) { var x = num(v); return x === null ? '-' : x.toLocaleString() + '원'; }
    function pct(v) { var x = num(v); return x === null ? '-' : x.toFixed(2) + '%'; }
    function card(label, val, color) {
      return '<div style="background:#16203a;border:1px solid #2a3a55;border-radius:8px;padding:9px 11px;">'
        + '<div style="font-size:11px;color:#7d92b0;margin-bottom:3px;">' + label + '</div>'
        + '<div style="font-size:15px;font-weight:700;color:' + (color || '#e8eef7') + ';">' + val + '</div></div>';
    }

    /* ── 보조지표 계산 ── */
    function sma(vals, p) {
      var out = []; for (var i = 0; i < vals.length; i++) {
        if (i < p - 1) { out.push(null); continue; }
        var s = 0; for (var j = i - p + 1; j <= i; j++) s += vals[j]; out.push(s / p);
      } return out;
    }
    function bollinger(vals, p, k) {
      var mid = sma(vals, p), up = [], lo = [];
      for (var i = 0; i < vals.length; i++) {
        if (mid[i] === null) { up.push(null); lo.push(null); continue; }
        var s = 0; for (var j = i - p + 1; j <= i; j++) { var d = vals[j] - mid[i]; s += d * d; }
        var sd = Math.sqrt(s / p); up.push(mid[i] + k * sd); lo.push(mid[i] - k * sd);
      } return { mid: mid, up: up, lo: lo };
    }
    function rsiCalc(vals, p) {
      var out = [], gain = 0, loss = 0;
      for (var i = 0; i < vals.length; i++) {
        if (i === 0) { out.push(null); continue; }
        var ch = vals[i] - vals[i - 1], g = ch > 0 ? ch : 0, l = ch < 0 ? -ch : 0;
        if (i <= p) { gain += g; loss += l; if (i === p) { gain /= p; loss /= p; out.push(100 - 100 / (1 + (loss === 0 ? 100 : gain / loss))); } else out.push(null); }
        else { gain = (gain * (p - 1) + g) / p; loss = (loss * (p - 1) + l) / p; out.push(100 - 100 / (1 + (loss === 0 ? 100 : gain / loss))); }
      } return out;
    }
    function toTime(c) {
      var ts = String(c.ts || '');
      if (ts.length >= 12) { return Math.floor(Date.UTC(+ts.substr(0, 4), +ts.substr(4, 2) - 1, +ts.substr(6, 2), +ts.substr(8, 2), +ts.substr(10, 2), 0) / 1000); }
      if (ts.length >= 8) { return ts.substr(0, 4) + '-' + ts.substr(4, 2) + '-' + ts.substr(6, 2); }
      return c.t;
    }

    /* ── 컨트롤 스타일 ── */
    var tfBase = 'padding:5px 12px;cursor:pointer;border-radius:6px;font-size:12px;font-weight:700;border:1px solid #2a3a55;margin-right:4px;';
    var tfActive = tfBase + 'background:#3d4e6e;color:#fff;';
    var tfIdle = tfBase + 'background:#16203a;color:#7d92b0;';
    function chk(id, label) {
      return '<label style="font-size:12px;color:#a0b4d0;margin-right:10px;cursor:pointer;">'
        + '<input type="checkbox" class="sd-ind" data-ind="' + id + '" ' + (st.ind[id] ? 'checked' : '') + ' style="vertical-align:middle;"> ' + label + '</label>';
    }
    var tfList = [['1', '1분'], ['5', '5분'], ['10', '10분'], ['D', '일'], ['W', '주'], ['M', '월']];
    var tfBtns = tfList.map(function (t) { return '<button class="sd-tf" data-tf="' + t[0] + '" style="' + (t[0] === st.tf ? tfActive : tfIdle) + '">' + t[1] + '</button>'; }).join('');

    host.innerHTML =
      '<div style="display:flex;gap:14px;padding:16px;align-items:flex-start;">'
      + '  <div id="sd-company" style="flex:0 0 320px;min-width:300px;"><div style="color:#7090b0;padding:8px;">기업 정보 불러오는 중...</div></div>'
      + '  <div style="flex:1;min-width:0;">'
      + '    <div style="display:flex;flex-wrap:wrap;gap:4px;align-items:center;margin-bottom:8px;">' + tfBtns
      + '      <span style="flex:1 1 auto;"></span>'
      + '      <label style="font-size:12px;color:#a0b4d0;margin-right:8px;cursor:pointer;"><input type="radio" name="sd-type" value="candle" checked> 캔들</label>'
      + '      <label style="font-size:12px;color:#a0b4d0;cursor:pointer;"><input type="radio" name="sd-type" value="line"> 라인</label>'
      + '    </div>'
      + '    <div style="margin-bottom:8px;">' + chk('sma5', 'MA5') + chk('sma20', 'MA20') + chk('sma60', 'MA60') + chk('boll', '볼린저') + chk('rsi', 'RSI') + '</div>'
      + '    <div id="sd-chart" style="width:100%;height:360px;background:#0f1830;border-radius:8px;"></div>'
      + '    <div id="sd-rsi" style="width:100%;height:90px;background:#0f1830;border-radius:8px;margin-top:6px;display:none;"></div>'
      + '  </div>'
      + '</div>';

    function renderCompany(data) {
      var p = data.price || {}, c = data.company, fin = (data.finance && data.finance.list) ? data.finance.list : null;
      var sign = p.prdy_vrss_sign || '3', up = (sign === '1' || sign === '2'), down = (sign === '4' || sign === '5');
      var pc = up ? '#ff5470' : down ? '#19c37d' : '#e0e6f0';
      var vr = num(p.prdy_vrss) || 0;
      var vrStr = (up ? '▲' : down ? '▼' : '') + ' ' + Math.abs(vr).toLocaleString() + ' (' + (p.prdy_ctrt || '0') + '%)';
      var grid = '<div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:6px;">'
        + card('현재가', comma(p.stck_prpr), pc)
        + card('전일대비', '<span style="font-size:13px;">' + vrStr + '</span>', pc)
        + card('시가총액', (num(p.hts_avls) !== null ? comma(p.hts_avls) + '억' : '-'))
        + card('PER', (num(p.per) !== null ? p.per + '배' : '-'))
        + card('PBR', (num(p.pbr) !== null ? p.pbr + '배' : '-'))
        + card('EPS', won(p.eps))
        + card('외국인지분율', pct(p.hts_frgn_ehrt))
        + card('52주최고', comma(p.w52_hgpr))
        + '</div>';
      var rows = '';
      if (c) {
        rows += buildInfoRow('대표자', c.ceo_nm) + buildInfoRow('설립일', c.est_dt) + buildInfoRow('업종', c.induty_nm) + buildInfoRow('주소', c.adres);
        if (c.hm_url) {
          var hurl = String(c.hm_url).trim();
          // DART hm_url은 스킴(http/https)이 없는 경우가 많아 상대경로로 잘못 해석됨 → 보정
          var href = /^https?:\/\//i.test(hurl) ? hurl : 'https://' + hurl.replace(/^\/+/, '');
          rows += '<div><strong style="color:#a0b4d0;">홈페이지:</strong> <a href="' + esc(href) + '" target="_blank" rel="noopener noreferrer" style="color:#4d9eff;">' + esc(hurl) + '</a></div>';
        }
      } else rows = '<div style="color:#7090b0;">기업 정보(DART) 조회 불가</div>';

      /* 재무분석 표 (당기/전기, 단위 억원) */
      function eokAmt(v) { var x = num(v); return x === null ? '-' : Math.round(x / 100000000).toLocaleString() + '억'; }
      function finRow(label, names) {
        var cur = '-', prev = '-';
        if (fin) for (var i = 0; i < fin.length; i++) { if (names.indexOf(fin[i].account_nm) >= 0) { cur = eokAmt(fin[i].thstrm_amount); prev = eokAmt(fin[i].frmtrm_amount); break; } }
        return '<tr><td style="padding:4px 6px;color:#a0b4d0;">' + label + '</td>'
          + '<td style="padding:4px 6px;text-align:right;color:#e8eef7;">' + cur + '</td>'
          + '<td style="padding:4px 6px;text-align:right;color:#7d92b0;">' + prev + '</td></tr>';
      }
      var finTable = !fin ? '<div style="color:#7090b0;font-size:13px;">재무 정보(DART) 없음</div>'
        : '<table style="width:100%;border-collapse:collapse;font-size:12px;">'
        + '<thead><tr style="border-bottom:1px solid #2a3a55;"><th style="text-align:left;padding:4px 6px;color:#7d92b0;font-weight:600;">항목</th><th style="text-align:right;padding:4px 6px;color:#7d92b0;font-weight:600;">당기</th><th style="text-align:right;padding:4px 6px;color:#7d92b0;font-weight:600;">전기</th></tr></thead><tbody>'
        + finRow('매출액', ['매출액', '영업수익']) + finRow('영업이익', ['영업이익']) + finRow('자산총계', ['자산총계']) + finRow('부채총계', ['부채총계']) + finRow('자본총계', ['자본총계'])
        + '</tbody></table><div style="text-align:right;font-size:10px;color:#5a6e90;margin-top:3px;">* 단위: 억원</div>';

      /* 동일 업종 비교 (같은 업종 노출 상위 종목) */
      var peers = data.peers || [];
      var peerHtml = peers.length === 0 ? '<div style="color:#7090b0;font-size:12px;">비교 종목 없음</div>'
        : peers.map(function (pp) {
            var ps = pp.sign, pu = (ps === '1' || ps === '2'), pd = (ps === '4' || ps === '5');
            var col = pu ? '#ff5470' : pd ? '#19c37d' : '#9fb0c8';
            var price = (pp.price !== undefined) ? comma(pp.price) + '원' : '-';
            var ctrt = (pp.ctrt !== undefined) ? ((pu ? '+' : '') + pp.ctrt + '%') : '';
            return '<div class="sd-peer" data-code="' + esc(pp.code) + '" data-name="' + esc(pp.name) + '" style="display:flex;justify-content:space-between;align-items:center;background:#16203a;border:1px solid #2a3a55;border-radius:6px;padding:7px 10px;margin-bottom:5px;cursor:pointer;">'
              + '<span style="font-size:13px;font-weight:600;color:#dce6f5;">' + esc(pp.name) + '</span>'
              + '<span style="text-align:right;line-height:1.3;"><span style="font-size:13px;font-weight:700;color:' + col + ';">' + price + '</span>'
              + (ctrt ? '<br><span style="font-size:11px;color:' + col + ';">' + ctrt + '</span>' : '') + '</span></div>';
          }).join('');

      /* 소속 섹터(테마) 동향 — 대시보드 섹터 호재/악재 평균 시그널 */
      var secs = data.sectors || [];
      function sectorAvg(name) { for (var i = 0; i < allSectorsData.length; i++) if (allSectorsData[i].name === name) return allSectorsData[i].avg; return null; }
      var secHtml = secs.length === 0 ? '<div style="color:#7090b0;font-size:12px;">소속 섹터 없음</div>'
        : secs.map(function (s) {
            var a = sectorAvg(s.name);
            var col = a === null ? '#5a6e90' : (a > 0 ? 'var(--good)' : a < 0 ? 'var(--bad)' : 'var(--neu)');
            var av = (a === null) ? '' : ' <span style="color:' + col + ';font-weight:700;">' + (a > 0 ? '+' : '') + a.toFixed(1) + '</span>';
            return '<span style="display:inline-block;background:#16203a;border:1px solid #2a3a55;border-radius:14px;padding:4px 11px;margin:0 6px 6px 0;font-size:12px;color:#cdd9ea;">' + esc(s.name) + av + '</span>';
          }).join('');

      var box = document.getElementById('sd-company');
      if (!box) return;
      box.innerHTML =
        '<div style="font-weight:700;color:#9fc0ff;margin-bottom:8px;">📋 기업 정보 및 재무 현황</div>' + grid
        + '<div style="font-weight:700;color:#9fc0ff;margin:12px 0 6px;">🏢 기업개요</div>'
        + '<div style="font-size:13px;line-height:1.9;color:#c8d8e8;">' + rows + '</div>'
        + '<div style="font-weight:700;color:#9fc0ff;margin:14px 0 6px;">📊 최근 재무분석</div>' + finTable
        + '<div style="font-weight:700;color:#9fc0ff;margin:14px 0 6px;">🏭 동일 업종 비교</div>' + peerHtml
        + '<div style="font-weight:700;color:#9fc0ff;margin:14px 0 6px;">🧩 소속 섹터(테마) 동향</div>'
        + '<div>' + secHtml + '</div>';

      /* 비교 종목 클릭 → 해당 종목 상세로 전환 */
      box.querySelectorAll('.sd-peer').forEach(function (el) {
        el.addEventListener('click', function () {
          var pcode = el.getAttribute('data-code'), pname = el.getAttribute('data-name');
          var titleEl = document.getElementById('stockModalTitle');
          if (titleEl) titleEl.textContent = pname + ' (' + pcode + ')';
          renderStockDetail(pcode, host);
        });
      });
    }

    function renderChart() {
      var box = document.getElementById('sd-chart'); if (!box) return;
      if (st.chart) { st.chart.remove(); st.chart = null; }
      if (st.rsiChart) { st.rsiChart.remove(); st.rsiChart = null; }
      box.innerHTML = '';
      var rsiBox = document.getElementById('sd-rsi');
      if (rsiBox) { rsiBox.innerHTML = ''; rsiBox.style.display = st.ind.rsi ? 'block' : 'none'; }
      var candles = st.candles;
      if (!candles || candles.length === 0) { box.innerHTML = '<div style="color:#7090b0;text-align:center;padding:60px 20px;">차트 데이터 없음 (영업일 외 또는 KIS API 제한)</div>'; return; }
      if (!window.LightweightCharts) { box.innerHTML = '<div style="color:#ff5470;text-align:center;padding:60px 20px;">차트 라이브러리 로드 실패</div>'; return; }
      var intraday = (st.tf === '1' || st.tf === '5' || st.tf === '10');
      var times = candles.map(toTime), closes = candles.map(function (c) { return c.c; });
      var opts = {
        layout: { background: { color: 'transparent' }, textColor: '#a0b4d0' },
        grid: { vertLines: { color: '#22304d' }, horzLines: { color: '#22304d' } },
        rightPriceScale: { borderColor: '#2a3a55' },
        timeScale: { borderColor: '#2a3a55', timeVisible: intraday, secondsVisible: false },
        crosshair: { mode: 0 }, width: box.clientWidth, height: 360
      };
      var chart = LightweightCharts.createChart(box, opts); st.chart = chart;
      if (st.type === 'candle') {
        var cs = chart.addCandlestickSeries({ upColor: '#ff5470', downColor: '#19c37d', borderVisible: false, wickUpColor: '#ff5470', wickDownColor: '#19c37d' });
        cs.setData(candles.map(function (c, i) { return { time: times[i], open: c.o, high: c.h, low: c.l, close: c.c }; }));
      } else {
        var ls = chart.addLineSeries({ color: '#4d9eff', lineWidth: 2 });
        ls.setData(candles.map(function (c, i) { return { time: times[i], value: c.c }; }));
      }
      var vol = chart.addHistogramSeries({ priceScaleId: '', priceFormat: { type: 'volume' } });
      vol.priceScale().applyOptions({ scaleMargins: { top: 0.82, bottom: 0 } });
      vol.setData(candles.map(function (c, i) { return { time: times[i], value: c.v, color: (c.c >= c.o ? 'rgba(255,84,112,0.4)' : 'rgba(25,195,125,0.4)') }; }));
      function addLine(arr, color) {
        var s = chart.addLineSeries({ color: color, lineWidth: 1, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false });
        var d = []; for (var i = 0; i < arr.length; i++) if (arr[i] !== null) d.push({ time: times[i], value: arr[i] }); s.setData(d);
      }
      if (st.ind.sma5) addLine(sma(closes, 5), '#f5c451');
      if (st.ind.sma20) addLine(sma(closes, 20), '#e879f9');
      if (st.ind.sma60) addLine(sma(closes, 60), '#38bdf8');
      if (st.ind.boll) { var b = bollinger(closes, 20, 2); addLine(b.up, '#8aa0c0'); addLine(b.mid, '#5a6e90'); addLine(b.lo, '#8aa0c0'); }
      chart.timeScale().fitContent();
      if (st.ind.rsi && rsiBox) {
        var rc = LightweightCharts.createChart(rsiBox, { layout: opts.layout, grid: opts.grid, rightPriceScale: opts.rightPriceScale, timeScale: { borderColor: '#2a3a55', timeVisible: intraday, secondsVisible: false }, crosshair: { mode: 0 }, width: rsiBox.clientWidth, height: 90 });
        st.rsiChart = rc;
        var rs = rc.addLineSeries({ color: '#f5c451', lineWidth: 1 });
        var rv = rsiCalc(closes, 14), rd = []; for (var i = 0; i < rv.length; i++) if (rv[i] !== null) rd.push({ time: times[i], value: rv[i] });
        rs.setData(rd); rc.timeScale().fitContent();
      }
    }

    function loadDetail() {
      fetch(ctx + '/api/stock/info?type=details&tf=' + encodeURIComponent(st.tf) + '&code=' + encodeURIComponent(code))
        .then(function (r) { return r.json(); })
        .then(function (data) {
          renderCompany(data);
          st.candles = (data.chart && data.chart.candles) ? data.chart.candles : [];
          renderChart();
        })
        .catch(function (err) {
          console.error(err);
          var em = err ? (err.stack || err.message || String(err)) : 'Unknown';
          host.innerHTML = '<div style="color:#ff5470;text-align:center;padding:16px;">상세 정보를 불러오지 못했습니다.<br><span style="font-size:12px;color:#a0b4d0;">' + esc(em) + '</span></div>';
        });
    }

    /* 컨트롤 이벤트 */
    host.querySelectorAll('.sd-tf').forEach(function (b) {
      b.addEventListener('click', function () {
        st.tf = b.getAttribute('data-tf');
        host.querySelectorAll('.sd-tf').forEach(function (x) { x.style.cssText = tfIdle; });
        b.style.cssText = tfActive;
        loadDetail();
      });
    });
    host.querySelectorAll('input[name="sd-type"]').forEach(function (r) {
      r.addEventListener('change', function () { if (r.checked) { st.type = r.value; renderChart(); } });
    });
    host.querySelectorAll('.sd-ind').forEach(function (cb) {
      cb.addEventListener('change', function () { st.ind[cb.getAttribute('data-ind')] = cb.checked; renderChart(); });
    });

    loadDetail();
  }

  /* ───────────── 수집·분석 버튼 ───────────── */
  var collectBtn = document.getElementById('collectBtn');
  var pollInterval = null;

  function checkCollectStatus(btn) {
    fetch(ctx + '/api/collect/status')
      .then(function(r) { return r.json(); })
      .then(function(res) {
        if (res.running) {
          // 여전히 실행 중이면 2초 뒤 다시 체크
          pollInterval = setTimeout(function() { checkCollectStatus(btn); }, 2000);
        } else {
          // 실행 완료! 화면 갱신
          load();
          btn.disabled = false;
          btn.textContent = '분석·요약 실행';
        }
      })
      .catch(function() {
        // 에러 발생 시 초기화
        btn.disabled = false;
        btn.textContent = '분석·요약 실행';
      });
  }

  if (collectBtn) {
    collectBtn.addEventListener('click', function () {
      var btn = this;
      btn.disabled = true;
      btn.textContent = '분석 중…';
      fetch(ctx + '/collect/run', { method: 'POST', headers: { 'X-CSRF-Token': csrf } })
        .then(function () {
          // 비동기 호출 성공, 폴링 시작
          if (pollInterval) clearTimeout(pollInterval);
          setTimeout(function() { checkCollectStatus(btn); }, 1000);
        })
        .catch(function () { btn.disabled = false; btn.textContent = '분석·요약 실행'; });
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
  var periodEl = document.getElementById('issuePeriod');
  if (filterEl) filterEl.addEventListener('change', renderFilteredIssues);
  if (sortEl)   sortEl.addEventListener('change',   renderFilteredIssues);
  // 기간(전체/당일/전일)은 서버에서 다시 조회 (당일·전일 이슈를 실제로 새로 받아옴)
  if (periodEl) periodEl.addEventListener('change', function () {
    currentPeriod = periodEl.value || 'all';
    load();
  });

  /* ───────────── 실시간 업데이트 (15초 주기) ───────────── */
  function refreshRealtimeData() {
    // 거시경제 지표 갱신
    fetch(ctx + '/api/macro/prices')
      .then(function(r) { return r.json(); })
      .then(function(res) {
        var macroData = res.prices || {};
        lastMacroData = macroData;
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

  // 카드 미니 그래프(스파크라인) 1회 로드 — 일별 시계열이라 매번 갱신 불필요
  fetch(ctx + '/api/macro/prices?type=sparkline')
    .then(function (r) { return r.json(); })
    .then(function (d) {
      macroSparklines = (d && d.sparklines) || {};
      if (Object.keys(lastMacroData).length) renderMacro(lastMacroSignals, lastMacroData);
    })
    .catch(function () {});
})();
