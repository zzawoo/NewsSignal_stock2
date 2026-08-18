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
  var sectorTrends = {}; // 섹터명 → {d,p} (구성종목 시총가중 등락)
  var hotIssueData = null; // 오늘의 핫이슈 AI 요약
  var hotIssueTimer = null; // 핫이슈 슬라이드 자동순환 타이머
  var macroSparklines = {}; // 카드 미니 그래프용 시계열
  var currentSectorStocks = [];
  var CHUNK_SIZE = 15; // 한 번에 KIS API를 호출할 종목 수

  /* ───────────── 관심목록(★) ───────────── */
  var isLogged = document.body.getAttribute('data-logged') === 'true';
  var watchedKw = {};      // 관심 키워드 set
  var watchedStock = {};   // 관심 종목(code) set

  function starKw(name) {
    var on = !!watchedKw[name];
    return '<span class="watch-star" data-star-kw="' + esc(name) + '" title="관심 키워드 추가/해제"'
      + ' style="cursor:pointer; margin-left:8px; font-size:15px; line-height:1; color:' + (on ? '#f5c451' : '#5b6b85') + ';">'
      + (on ? '★' : '☆') + '</span>';
  }
  function starStock(code, name) {
    var on = !!watchedStock[code];
    return '<span class="watch-star" data-star-code="' + esc(code) + '" data-star-name="' + esc(name) + '" title="관심 종목 추가/해제"'
      + ' style="cursor:pointer; margin-right:6px; font-size:15px; line-height:1; color:' + (on ? '#f5c451' : '#5b6b85') + ';">'
      + (on ? '★' : '☆') + '</span>';
  }
  function setStar(el, on) { el.textContent = on ? '★' : '☆'; el.style.color = on ? '#f5c451' : '#5b6b85'; }
  function refreshStars() {
    var ks = document.querySelectorAll('.watch-star[data-star-kw]');
    for (var i = 0; i < ks.length; i++) setStar(ks[i], !!watchedKw[ks[i].getAttribute('data-star-kw')]);
    var ss = document.querySelectorAll('.watch-star[data-star-code]');
    for (var j = 0; j < ss.length; j++) setStar(ss[j], !!watchedStock[ss[j].getAttribute('data-star-code')]);
  }
  function loadWatchlistState() {
    if (!isLogged) return;
    fetch(ctx + '/api/my/watchlist').then(function (r) { return r.ok ? r.json() : null; }).then(function (d) {
      if (!d || !d.sets) return;
      // ★는 '기본' 세트(기본 = 가장 오래된 세트, defaultSetId)를 대상으로 토글한다.
      var def = null;
      for (var i = 0; i < d.sets.length; i++) { if (d.sets[i].id === d.defaultSetId) { def = d.sets[i]; break; } }
      if (!def) def = d.sets[0];
      watchedKw = {}; watchedStock = {};
      if (def) {
        (def.keywords || []).forEach(function (k) { watchedKw[k] = true; });
        (def.stocks || []).forEach(function (s) { watchedStock[s.code] = true; });
      }
      refreshStars();
    }).catch(function () {});
  }
  function wlToast(msg) {
    var t = document.getElementById('wlToast');
    if (!t) {
      t = document.createElement('div'); t.id = 'wlToast';
      t.style.cssText = 'position:fixed; bottom:24px; left:50%; transform:translateX(-50%); background:#16203a; border:1px solid #2a3a55; color:#dce6f5; padding:10px 18px; border-radius:10px; font-size:13px; z-index:3000; opacity:0; transition:opacity .2s; pointer-events:none;';
      document.body.appendChild(t);
    }
    t.textContent = msg; t.style.opacity = '1';
    clearTimeout(t._h); t._h = setTimeout(function () { t.style.opacity = '0'; }, 1600);
  }
  function postWatch(params, cb) {
    fetch(ctx + '/api/my/watchlist', { method: 'POST', headers: { 'X-CSRF-Token': csrf, 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(params).toString() })
      .then(function (r) {
        if (r.status === 401) { if (confirm('로그인이 필요합니다. 로그인 페이지로 이동할까요?')) location.href = ctx + '/login.jsp'; return null; }
        return r.json();
      })
      .then(function (d) { cb(!!(d && d.ok), d); })
      .catch(function () { cb(false, null); });
  }
  function toggleStar(el) {
    if (!isLogged) { if (confirm('로그인이 필요합니다. 로그인 페이지로 이동할까요?')) location.href = ctx + '/login.jsp'; return; }
    var kw = el.getAttribute('data-star-kw');
    if (kw !== null) {
      var onK = !!watchedKw[kw];
      postWatch(onK ? { action: 'delKeyword', keyword: kw } : { action: 'addKeyword', keyword: kw }, function (ok) {
        if (!ok) return;
        if (onK) { delete watchedKw[kw]; } else { watchedKw[kw] = true; }
        setStar(el, !onK); wlToast(onK ? '관심 키워드에서 제외했습니다.' : '관심 키워드에 추가했습니다.');
      });
      return;
    }
    var code = el.getAttribute('data-star-code');
    if (code !== null) {
      var name = el.getAttribute('data-star-name') || '';
      var onS = !!watchedStock[code];
      postWatch(onS ? { action: 'delStock', code: code } : { action: 'addStock', code: code, name: name }, function (ok) {
        if (!ok) return;
        if (onS) { delete watchedStock[code]; } else { watchedStock[code] = true; }
        setStar(el, !onS); wlToast(onS ? '관심 종목에서 제외했습니다.' : '관심 종목에 추가했습니다.');
      });
    }
  }

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
    handleDeepLink();   // 알림에서 진입 시 대상 모달 자동 오픈
  }

  /* ───────────── 이슈 카드 ───────────── */
  // 칩(섹터/매크로)의 실시간 등락 방향 → 상승=빨강 · 하락=초록 · 반반=회색
  function macroDir(name) {
    var key = name === '코스피' ? 'KOSPI' : name === '코스닥' ? 'KOSDAQ' : name;
    var m = lastMacroData[key];
    if (!m || m.sign == null) return null;
    var s = String(m.sign);
    return (s === '1' || s === '2') ? 'UP' : (s === '4' || s === '5') ? 'DOWN' : 'FLAT';
  }
  function macroPct(name) {
    var key = name === '코스피' ? 'KOSPI' : name === '코스닥' ? 'KOSDAQ' : name;
    var m = lastMacroData[key];
    if (!m || m.ratio == null) return null;
    var v = parseFloat(String(m.ratio).replace(/,/g, ''));
    return isNaN(v) ? null : v;
  }
  function chipDir(name) {
    var s = sectorTrends[name];
    if (s) return s.d;          // 섹터: 시총가중 등락 {d,p}
    return macroDir(name);      // 매크로: 지표 자체 등락
  }
  function chipPct(name) {
    var s = sectorTrends[name];
    if (s && s.p != null) return s.p;
    return macroPct(name);
  }
  function chipStyle(dir) {
    if (dir === 'UP')   return 'background:rgba(255,84,112,0.15);border-color:#ff5470;color:#ff7a93;';
    if (dir === 'DOWN') return 'background:rgba(25,195,125,0.15);border-color:#19c37d;color:#4ad8a0;';
    if (dir === 'FLAT') return 'background:rgba(120,140,170,0.14);border-color:#3d4e6e;color:#9fb0c8;';
    return '';
  }
  function issueRow(it) {
    var sectors = (it.sectors || '').split(',').filter(Boolean)
      .map(function (s) {
        var nm = s.trim(), sty = chipStyle(chipDir(nm)), pct = chipPct(nm);
        var title = (pct != null) ? (nm + ' ' + (pct > 0 ? '+' : '') + pct + '%') : nm;
        return '<span class="chip"' + (sty ? ' style="' + sty + '"' : '') + ' title="' + esc(title) + '">' + esc(nm) + '</span>';
      }).join('');
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
      var avgColor = r.avg > 0 ? 'var(--bad)' : r.avg < 0 ? 'var(--good)' : 'var(--neu)';  // 호재(+)=빨강·악재(-)=초록
      var sign = r.avg > 0 ? '+' : '';
      var stockCount = (r.stocks || []).length;
      var stockBadge = stockCount > 0
        ? '<span style="margin-left:8px; background:#3d4e6e; color:#a0b4d0; font-size:11px; padding:2px 8px; border-radius:10px; font-weight:600; white-space:nowrap; flex:0 0 auto;">' + stockCount + '개 종목</span>'
        : '';
      var tr = sectorTrends[r.name], trendHtml = '';
      if (tr && tr.p != null) {
        var tcol = tr.d === 'UP' ? '#ff5470' : tr.d === 'DOWN' ? '#19c37d' : '#9fb0c8';
        trendHtml = '<span title="구성종목 시가총액 가중 실시간 등락률" style="margin-left:8px; font-size:12px; font-weight:700; white-space:nowrap; color:' + tcol + ';">등락 ' + (tr.p > 0 ? '+' : '') + tr.p + '%</span>';
      }
      return '<div class="srow" data-index="' + i + '">'
        + '<div class="line1">'
        + '  <span class="sname">' + esc(r.name) + '</span>'
        + '  <span class="stype">' + esc(r.type) + '</span>'
        + stockBadge
        + trendHtml
        + '  <span class="savg" style="color:' + avgColor + '" title="뉴스 호재/악재 평균 점수">평균 ' + sign + esc(r.avg.toFixed(1)) + '</span>'
        + starKw(r.name)
        + '</div>'
        + '<div class="gbbar">'
        + '  <div class="g" style="width:' + (r.good / tot * 100) + '%"></div>'
        + '  <div class="n" style="width:' + (r.neu  / tot * 100) + '%"></div>'
        + '  <div class="b" style="width:' + (r.bad  / tot * 100) + '%"></div>'
        + '</div>'
        + '<div class="gbcount">'
        + '  <span><span class="d" style="background:var(--bad)"></span>호재 ' + r.good + '</span>'
        + '  <span><span class="d" style="background:var(--neu)"></span>중립 ' + r.neu  + '</span>'
        + '  <span><span class="d" style="background:var(--good)"></span>악재 ' + r.bad  + '</span>'
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
      return '<tr class="stock-row" data-code="' + esc(s.code) + '" data-name="' + esc(s.name) + '" style="cursor:pointer; border-bottom:1px solid #2a3550;">'
           + '<td style="' + tdL + ' font-weight:600;">' + starStock(s.code, s.name) + esc(s.name)
           + '<br><span style="font-size:11px; color:#7090b0; margin-left:21px;">' + esc(s.code) + '</span></td>'
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
      var star = e.target.closest('.watch-star');
      if (star) { e.stopPropagation(); toggleStar(star); return; }
      var srow = e.target.closest('.srow');
      if (!srow) return;
      var idx    = parseInt(srow.getAttribute('data-index'), 10);
      var sector = allSectorsData[idx];
      if (!sector) return;

      openSectorStocks(sector.name, sector.stocks || []);
    });
  }

  /* 섹터 종목 패널 열기 (연관 섹터 클릭 + 핫이슈 강세 섹터 칩 공용) */
  function openSectorStocks(name, stocks) {
    currentSectorStocks = stocks || [];
    var total = currentSectorStocks.length;
    document.getElementById('selectedSectorName').textContent = name + ' 관련 종목 (총 ' + total + '개)';
    var container = document.getElementById('stockListContainer');
    var searchBarHTML = total > 5
      ? '<div style="padding:10px 0 10px; display:flex; gap:10px; align-items:center;">'
      + '  <input id="stockSearchInput" type="text" placeholder="종목명 / 종목코드 검색..."'
      + '    style="flex:1; padding:9px 14px; border-radius:8px; border:1px solid #3d4e6e; background:#1a2540; color:#e0e6f0; font-size:14px; outline:none;">'
      + '  <span style="color:#7090b0; font-size:13px; white-space:nowrap;">총 ' + total + '개</span>'
      + '</div>'
      : '';
    container.innerHTML = searchBarHTML + '<div id="stockTableHost">' + buildStockTableHTML(currentSectorStocks) + '</div>';
    document.getElementById('stockListCard').style.display = 'block';
    document.getElementById('stockListCard').scrollIntoView({ behavior: 'smooth' });
    if (total > 0) observeAllRows(container);
    var searchInput = document.getElementById('stockSearchInput');
    if (searchInput) {
      searchInput.addEventListener('input', function () {
        var q = this.value.trim().toLowerCase();
        var filtered = q ? currentSectorStocks.filter(function (s) { return s.name.toLowerCase().indexOf(q) >= 0 || s.code.indexOf(q) >= 0; }) : currentSectorStocks;
        var host = document.getElementById('stockTableHost');
        if (host) { host.innerHTML = buildStockTableHTML(filtered); if (filtered.length > 0) observeAllRows(container); }
      });
    }
  }
  /* 섹터명으로 종목을 조회해 패널 열기 (핫이슈 강세 섹터 칩 등, allSectorsData에 없을 수 있음) */
  function openSectorByName(name) {
    fetch(ctx + '/api/sector/stocks?name=' + encodeURIComponent(name))
      .then(function (r) { return r.json(); })
      .then(function (d) { openSectorStocks(d.name || name, d.stocks || []); })
      .catch(function () {});
  }
  /* 핫이슈 강세 섹터 칩 클릭 → 해당 섹터 종목 패널 (위임: 카드 재렌더에도 유지) */
  document.addEventListener('click', function (e) {
    var chip = e.target.closest('.hi-sector-chip');
    if (chip) openSectorByName(chip.getAttribute('data-sector'));
  });

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

  /* 종목 상세 모달 열기 (행 클릭·알림 딥링크 공용) */
  function openStockModal(code, name) {
    if (!stockModal) return;
    document.getElementById('stockModalTitle').textContent = (name ? name + ' ' : '') + '(' + code + ')';
    var detailsDiv = document.getElementById('stockModalBody');
    detailsDiv.innerHTML = '<div style="color:#7090b0; text-align:center; padding:20px;">상세 데이터 불러오는 중...</div>';
    stockModal.style.display = 'flex';
    renderStockDetail(code, detailsDiv);
  }

  /* ───────────── 종목 행 클릭 → 팝업 표시 ───────────── */
  var stockListContainer = document.getElementById('stockListContainer');
  if (stockListContainer) {
    stockListContainer.addEventListener('click', function (e) {
      var star = e.target.closest('.watch-star');
      if (star) { e.stopPropagation(); toggleStar(star); return; }
      var stockRow = e.target.closest('.stock-row');
      if (!stockRow) return;
      openStockModal(stockRow.getAttribute('data-code'), stockRow.getAttribute('data-name') || '종목 상세');
    });
  }

  /* ───────────── 상시 종목 검색 (섹터 선택 불필요) ───────────── */
  function setupGlobalStockSearch() {
    var input = document.getElementById('globalStockSearch');
    var sug   = document.getElementById('globalStockSuggest');
    if (!input || !sug) return;
    var timer = null;
    function hide() { sug.style.display = 'none'; sug.innerHTML = ''; }
    function pick(code, name) { hide(); input.value = ''; openStockModal(code, name); }
    input.addEventListener('input', function () {
      var q = this.value.trim();
      if (timer) clearTimeout(timer);
      if (q.length < 1) { hide(); return; }
      timer = setTimeout(function () {
        fetch(ctx + '/api/stock/info?type=search&q=' + encodeURIComponent(q))
          .then(function (r) { return r.json(); })
          .then(function (d) {
            var list = (d && d.results) || [];
            if (!list.length) {
              sug.innerHTML = '<div style="padding:10px 14px; color:#7090b0; font-size:13px;">검색 결과가 없습니다.</div>';
              sug.style.display = 'block'; return;
            }
            sug.innerHTML = list.map(function (s) {
              return '<div class="gs-item" data-code="' + esc(s.code) + '" data-name="' + esc(s.name) + '"'
                + ' style="padding:10px 14px; cursor:pointer; border-bottom:1px solid #233650; font-size:14px; color:#dce6f5;">'
                + esc(s.name) + ' <small style="color:#7090b0;">' + esc(s.code) + '</small></div>';
            }).join('');
            sug.style.display = 'block';
          }).catch(hide);
      }, 180);
    });
    sug.addEventListener('click', function (e) {
      var it = e.target.closest('.gs-item'); if (!it) return;
      pick(it.getAttribute('data-code'), it.getAttribute('data-name'));
    });
    input.addEventListener('keydown', function (e) {
      if (e.key !== 'Enter') return;
      e.preventDefault();
      var first = sug.querySelector('.gs-item');
      if (first) pick(first.getAttribute('data-code'), first.getAttribute('data-name'));
      else if (/^\d{6}$/.test(this.value.trim())) pick(this.value.trim(), '');
    });
    document.addEventListener('click', function (e) { if (e.target !== input && !sug.contains(e.target)) hide(); });
  }

  /* ───────────── 알림 딥링크: ?group=ID(이슈 기사) / ?stock=CODE(종목 상세) ───────────── */
  var deepLinkHandled = false;
  function handleDeepLink() {
    if (deepLinkHandled) return;
    var params = new URLSearchParams(location.search);
    var g = params.get('group');
    var st = params.get('stock');
    if (g) {
      deepLinkHandled = true;
      var title = '알림 관련 뉴스';
      for (var i = 0; i < allIssuesData.length; i++) {
        if (String(allIssuesData[i].id) === String(g)) { title = allIssuesData[i].title; break; }
      }
      openArticlesModal(g, title);
    } else if (st) {
      deepLinkHandled = true;
      openStockModal(st);
    }
  }

  function buildInfoRow(label, value) {
    return '<div><strong style="color:#a0b4d0;">' + label + ':</strong> ' + esc(value) + '</div>';
  }

  /* ───────────── 종목 상세: 기업정보(DART) + 실시간 캔들차트(KIS) ─────────────
     - 기업정보: 24h 캐시(서버), 현재가/지표: 5초 캐시, 차트: 60초 캐시
     - 캔들/라인 토글, 이동평균(5/20/60), 볼린저밴드, RSI, 시간대(1·5·10분/일/주/월) */
  function renderStockDetail(code, host) {
    var st = { tf: 'D', type: 'candle', candles: [], chart: null, rsiChart: null, macdChart: null, stochChart: null, obvChart: null, mfiChart: null, tlChart: null,
               ind: { sma5: true, sma20: true, sma60: false, sma120: false, boll: false, volma: false, ichimoku: false, rsi: false, macd: false, stoch: false, obv: false, mfi: false } };

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
    function ema(vals, p) {
      var out = [], k = 2 / (p + 1), prev = null;
      for (var i = 0; i < vals.length; i++) {
        if (vals[i] == null) { out.push(null); continue; }
        prev = (prev == null) ? vals[i] : (vals[i] * k + prev * (1 - k));
        out.push(prev);
      } return out;
    }
    function macdCalc(vals) {
      var f = ema(vals, 12), s = ema(vals, 26), macd = [];
      for (var i = 0; i < vals.length; i++) macd.push(f[i] - s[i]);
      var signal = ema(macd, 9), hist = [];
      for (var i = 0; i < vals.length; i++) hist.push(macd[i] - signal[i]);
      return { macd: macd, signal: signal, hist: hist };
    }
    function stochCalc(cs, p, d) {
      var k = [];
      for (var i = 0; i < cs.length; i++) {
        if (i < p - 1) { k.push(null); continue; }
        var hh = -Infinity, ll = Infinity;
        for (var j = i - p + 1; j <= i; j++) { if (cs[j].h > hh) hh = cs[j].h; if (cs[j].l < ll) ll = cs[j].l; }
        k.push(hh === ll ? 50 : (cs[i].c - ll) / (hh - ll) * 100);
      }
      var dd = [];
      for (var i = 0; i < k.length; i++) {
        if (k[i] == null) { dd.push(null); continue; }
        var sum = 0, ok = true;
        for (var j = i - d + 1; j <= i; j++) { if (j < 0 || k[j] == null) { ok = false; break; } sum += k[j]; }
        dd.push(ok ? sum / d : null);
      }
      return { k: k, d: dd };
    }
    function ichimoku(cs) {
      function hl(p, i) {
        if (i < p - 1) return null;
        var hh = -Infinity, ll = Infinity;
        for (var j = i - p + 1; j <= i; j++) { if (cs[j].h > hh) hh = cs[j].h; if (cs[j].l < ll) ll = cs[j].l; }
        return (hh + ll) / 2;
      }
      var tenkan = [], kijun = [], spanA = [], spanB = [];
      for (var i = 0; i < cs.length; i++) {
        tenkan.push(hl(9, i)); kijun.push(hl(26, i)); spanB.push(hl(52, i));
        spanA.push((tenkan[i] != null && kijun[i] != null) ? (tenkan[i] + kijun[i]) / 2 : null);
      }
      return { tenkan: tenkan, kijun: kijun, spanA: spanA, spanB: spanB };
    }
    function obvCalc(cs) {
      var out = [0];
      for (var i = 1; i < cs.length; i++) {
        var prev = out[i - 1];
        out.push(cs[i].c > cs[i - 1].c ? prev + cs[i].v : cs[i].c < cs[i - 1].c ? prev - cs[i].v : prev);
      } return out;
    }
    function mfiCalc(cs, p) {
      var tp = [], rmf = [];
      for (var i = 0; i < cs.length; i++) { var t = (cs[i].h + cs[i].l + cs[i].c) / 3; tp.push(t); rmf.push(t * cs[i].v); }
      var out = [];
      for (var i = 0; i < cs.length; i++) {
        if (i < p) { out.push(null); continue; }
        var pos = 0, neg = 0;
        for (var j = i - p + 1; j <= i; j++) { if (tp[j] > tp[j - 1]) pos += rmf[j]; else if (tp[j] < tp[j - 1]) neg += rmf[j]; }
        out.push(neg === 0 ? 100 : 100 - 100 / (1 + pos / neg));
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
    function chk(id, label, color) {
      return '<label style="font-size:12px;color:' + (color || '#a0b4d0') + ';margin-right:10px;cursor:pointer;">'
        + '<input type="checkbox" class="sd-ind" data-ind="' + id + '" ' + (st.ind[id] ? 'checked' : '') + ' style="vertical-align:middle;"> ' + label + '</label>';
    }
    var tfList = [['1', '1분'], ['5', '5분'], ['10', '10분'], ['D', '일'], ['W', '주'], ['M', '월']];
    var tfBtns = tfList.map(function (t) { return '<button class="sd-tf" data-tf="' + t[0] + '" style="' + (t[0] === st.tf ? tfActive : tfIdle) + '">' + t[1] + '</button>'; }).join('');

    host.innerHTML =
      '<div class="sd-layout" style="display:flex;gap:14px;padding:16px;align-items:flex-start;">'
      + '  <div id="sd-company" style="flex:0 0 320px;min-width:300px;"><div style="color:#7090b0;padding:8px;">기업 정보 불러오는 중...</div></div>'
      + '  <div class="sd-chartcol" style="flex:1;min-width:0;">'
      + '    <div style="display:flex;flex-wrap:wrap;gap:4px;align-items:center;margin-bottom:8px;">' + tfBtns
      + '      <span style="flex:1 1 auto;"></span>'
      + '      <label style="font-size:12px;color:#a0b4d0;margin-right:8px;cursor:pointer;"><input type="radio" name="sd-type" value="candle" checked> 캔들</label>'
      + '      <label style="font-size:12px;color:#a0b4d0;cursor:pointer;"><input type="radio" name="sd-type" value="line"> 라인</label>'
      + '    </div>'
      + '    <div style="margin-bottom:8px;line-height:1.9;">'
      + '      <button class="sd-preset" title="MA20·60 + 볼린저 + RSI + 거래량MA" style="font-size:11px;font-weight:700;color:#0f1830;background:#f5c451;border:none;border-radius:6px;padding:4px 10px;margin-right:12px;cursor:pointer;">⭐ 추천지표</button>'
      + chk('sma5', 'MA5', '#f5c451') + chk('sma20', 'MA20', '#e879f9') + chk('sma60', 'MA60', '#38bdf8') + chk('sma120', 'MA120', '#fb923c') + chk('boll', '볼린저', '#8aa0c0') + chk('volma', '거래량MA', '#cbd5e1') + chk('ichimoku', '일목균형표', '#a78bfa') + chk('rsi', 'RSI', '#f5c451') + chk('macd', 'MACD', '#4d9eff') + chk('stoch', '스토캐스틱', '#4d9eff') + chk('obv', 'OBV', '#4d9eff') + chk('mfi', 'MFI', '#f5c451') + '</div>'
      + '    <div style="position:relative;">'
      + '      <div id="sd-mainlabel" style="position:absolute;top:6px;left:10px;z-index:5;font-size:11px;font-weight:700;color:#cdd9ea;background:rgba(15,24,48,0.6);padding:1px 7px;border-radius:4px;pointer-events:none;">📈 가격 · 거래량</div>'
      + '      <div id="sd-ohlc" style="display:none;position:absolute;top:28px;left:10px;z-index:5;font-size:11px;color:#a0b4d0;background:rgba(15,24,48,0.88);padding:3px 9px;border-radius:6px;pointer-events:none;white-space:nowrap;"></div>'
      + '      <div id="sd-chart" style="width:100%;height:360px;background:#0f1830;border-radius:8px;"></div>'
      + '    </div>'
      + '    <div id="sd-rsi-wrap" style="position:relative;margin-top:6px;display:none;">'
      + '      <div style="position:absolute;top:5px;left:10px;z-index:5;font-size:11px;font-weight:700;color:#f5c451;background:rgba(15,24,48,0.6);padding:1px 7px;border-radius:4px;pointer-events:none;">RSI 14 <span style="color:#7d92b0;font-weight:400;">(70 과매수 / 30 과매도)</span></div>'
      + '      <div id="sd-rsi" style="width:100%;height:90px;background:#0f1830;border-radius:8px;"></div>'
      + '    </div>'
      + '    <div id="sd-macd-wrap" style="position:relative;margin-top:6px;display:none;">'
      + '      <div style="position:absolute;top:5px;left:10px;z-index:5;font-size:11px;background:rgba(15,24,48,0.6);padding:1px 7px;border-radius:4px;pointer-events:none;"><b style="color:#cdd9ea;">MACD 12·26·9</b> <span style="color:#4d9eff;">━ MACD선</span> <span style="color:#f5c451;">━ 시그널</span> <span style="color:#9fb0c8;">▮ 히스토그램</span></div>'
      + '      <div id="sd-macd" style="width:100%;height:110px;background:#0f1830;border-radius:8px;"></div>'
      + '    </div>'
      + '    <div id="sd-stoch-wrap" style="position:relative;margin-top:6px;display:none;">'
      + '      <div style="position:absolute;top:5px;left:10px;z-index:5;font-size:11px;background:rgba(15,24,48,0.6);padding:1px 7px;border-radius:4px;pointer-events:none;"><b style="color:#cdd9ea;">스토캐스틱 14·3</b> <span style="color:#4d9eff;">━ %K</span> <span style="color:#f5c451;">━ %D</span> <span style="color:#7d92b0;">(80/20)</span></div>'
      + '      <div id="sd-stoch" style="width:100%;height:90px;background:#0f1830;border-radius:8px;"></div>'
      + '    </div>'
      + '    <div id="sd-obv-wrap" style="position:relative;margin-top:6px;display:none;">'
      + '      <div style="position:absolute;top:5px;left:10px;z-index:5;font-size:11px;background:rgba(15,24,48,0.6);padding:1px 7px;border-radius:4px;pointer-events:none;"><b style="color:#4d9eff;">OBV</b> <span style="color:#7d92b0;font-weight:400;">누적 거래량(추세 확인)</span></div>'
      + '      <div id="sd-obv" style="width:100%;height:90px;background:#0f1830;border-radius:8px;"></div>'
      + '    </div>'
      + '    <div id="sd-mfi-wrap" style="position:relative;margin-top:6px;display:none;">'
      + '      <div style="position:absolute;top:5px;left:10px;z-index:5;font-size:11px;background:rgba(15,24,48,0.6);padding:1px 7px;border-radius:4px;pointer-events:none;"><b style="color:#f5c451;">MFI 14</b> <span style="color:#7d92b0;font-weight:400;">거래량 반영 RSI (80/20)</span></div>'
      + '      <div id="sd-mfi" style="width:100%;height:90px;background:#0f1830;border-radius:8px;"></div>'
      + '    </div>'
      + '    <div id="sd-timeline-wrap" style="margin-top:16px;display:none;">'
      + '      <div style="font-weight:700;color:#9fc0ff;margin:0 0 6px;">📊 호재/악재 타임라인 <span style="font-size:11px;font-weight:400;color:#7d92b0;">(위 빨강=호재 · 아래 초록=악재 · 영향도 합산)</span></div>'
      + '      <div id="sd-timeline-chart" style="width:100%;height:120px;background:#0f1830;border-radius:8px;"></div>'
      + '    </div>'
      + '    <div id="sd-news" style="margin-top:14px;"><div style="color:#7090b0;font-size:13px;">관련 뉴스 불러오는 중...</div></div>'
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

      // 목표주가(컨센서스)·투자의견·배당 (네이버)
      var consHtml = '';
      var cns = data.consensus || {};
      if (cns.targetPrice || cns.dividendYield || cns.dps) {
        var tp = num(cns.targetPrice), curP = num(p.stck_prpr);
        var upside = (tp !== null && curP) ? ((tp / curP - 1) * 100) : null;
        var upColor = upside === null ? '#e8eef7' : (upside > 0 ? '#ff5470' : '#19c37d');
        var rec = parseFloat(cns.recommMean);
        var recTxt = isNaN(rec) ? '-' : rec >= 4.5 ? '강력매수' : rec >= 3.5 ? '매수' : rec >= 2.5 ? '중립' : rec >= 1.5 ? '매도' : '강력매도';
        var recColor = isNaN(rec) ? '#9fb0c8' : rec >= 3.5 ? '#ff5470' : rec <= 2.5 ? '#19c37d' : '#cbd5e1';
        consHtml = '<div style="font-weight:700;color:#9fc0ff;margin:12px 0 6px;">🎯 목표주가 · 투자의견 · 배당</div>'
          + '<div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;">'
          + card('목표주가', cns.targetPrice ? esc(cns.targetPrice) + '원' : '-')
          + card('상승여력', upside === null ? '-' : ((upside > 0 ? '+' : '') + upside.toFixed(1) + '%'), upColor)
          + card('투자의견', recTxt + (isNaN(rec) ? '' : ' (' + rec + ')'), recColor)
          + card('배당수익률', esc(cns.dividendYield || '-'))
          + (cns.dps ? card('주당배당금', esc(cns.dps)) : '')
          + (cns.cnsPer ? card('추정PER', esc(cns.cnsPer)) : '')
          + '</div>';
      }
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
            var col = a === null ? '#5a6e90' : (a > 0 ? 'var(--bad)' : a < 0 ? 'var(--good)' : 'var(--neu)');
            var av = (a === null) ? '' : ' <span style="color:' + col + ';font-weight:700;">' + (a > 0 ? '+' : '') + a.toFixed(1) + '</span>';
            return '<span style="display:inline-block;background:#16203a;border:1px solid #2a3a55;border-radius:14px;padding:4px 11px;margin:0 6px 6px 0;font-size:12px;color:#cdd9ea;">' + esc(s.name) + av + '</span>';
          }).join('');

      var box = document.getElementById('sd-company');
      if (!box) return;
      box.innerHTML =
        '<div style="font-weight:700;color:#9fc0ff;margin-bottom:8px;">📋 기업 정보 및 재무 현황</div>' + grid + consHtml
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
      if (st.macdChart) { st.macdChart.remove(); st.macdChart = null; }
      if (st.stochChart) { st.stochChart.remove(); st.stochChart = null; }
      if (st.obvChart) { st.obvChart.remove(); st.obvChart = null; }
      if (st.mfiChart) { st.mfiChart.remove(); st.mfiChart = null; }
      box.innerHTML = '';
      var rsiBox = document.getElementById('sd-rsi'), rsiWrap = document.getElementById('sd-rsi-wrap');
      if (rsiWrap) rsiWrap.style.display = st.ind.rsi ? 'block' : 'none';
      if (rsiBox) rsiBox.innerHTML = '';
      var macdBox = document.getElementById('sd-macd'), macdWrap = document.getElementById('sd-macd-wrap');
      if (macdWrap) macdWrap.style.display = st.ind.macd ? 'block' : 'none';
      if (macdBox) macdBox.innerHTML = '';
      var stochBox = document.getElementById('sd-stoch'), stochWrap = document.getElementById('sd-stoch-wrap');
      if (stochWrap) stochWrap.style.display = st.ind.stoch ? 'block' : 'none';
      if (stochBox) stochBox.innerHTML = '';
      var obvBox = document.getElementById('sd-obv'), obvWrap = document.getElementById('sd-obv-wrap');
      if (obvWrap) obvWrap.style.display = st.ind.obv ? 'block' : 'none';
      if (obvBox) obvBox.innerHTML = '';
      var mfiBox = document.getElementById('sd-mfi'), mfiWrap = document.getElementById('sd-mfi-wrap');
      if (mfiWrap) mfiWrap.style.display = st.ind.mfi ? 'block' : 'none';
      if (mfiBox) mfiBox.innerHTML = '';
      var ohlcEl = document.getElementById('sd-ohlc'); if (ohlcEl) ohlcEl.style.display = 'none';
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
      function subChart(boxEl, h) {
        return LightweightCharts.createChart(boxEl, {
          layout: opts.layout, grid: opts.grid, rightPriceScale: opts.rightPriceScale,
          timeScale: { borderColor: '#2a3a55', timeVisible: intraday, secondsVisible: false },
          crosshair: { mode: 0 }, width: boxEl.clientWidth, height: h
        });
      }
      var chart = LightweightCharts.createChart(box, opts); st.chart = chart;
      var syncCharts = [chart];
      function seriesData(arr) { var d = []; for (var i = 0; i < arr.length; i++) { d.push(arr[i] == null ? { time: times[i] } : { time: times[i], value: arr[i] }); } return d; }
      var mainSeries;
      if (st.type === 'candle') {
        mainSeries = chart.addCandlestickSeries({ upColor: '#ff5470', downColor: '#19c37d', borderVisible: false, wickUpColor: '#ff5470', wickDownColor: '#19c37d' });
        mainSeries.setData(candles.map(function (c, i) { return { time: times[i], open: c.o, high: c.h, low: c.l, close: c.c }; }));
      } else {
        mainSeries = chart.addLineSeries({ color: '#4d9eff', lineWidth: 2 });
        mainSeries.setData(candles.map(function (c, i) { return { time: times[i], value: c.c }; }));
      }
      var vol = chart.addHistogramSeries({ priceScaleId: '', priceFormat: { type: 'volume' } });
      vol.priceScale().applyOptions({ scaleMargins: { top: 0.82, bottom: 0 } });
      vol.setData(candles.map(function (c, i) { return { time: times[i], value: c.v, color: (c.c >= c.o ? 'rgba(255,84,112,0.4)' : 'rgba(25,195,125,0.4)') }; }));
      function addLine(arr, color) {
        var s = chart.addLineSeries({ color: color, lineWidth: 1, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false });
        var d = []; for (var i = 0; i < arr.length; i++) if (arr[i] !== null) d.push({ time: times[i], value: arr[i] }); s.setData(d);
      }
      function addVolLine(arr, color) {
        var s = chart.addLineSeries({ color: color, lineWidth: 1, priceScaleId: '', priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false });
        s.priceScale().applyOptions({ scaleMargins: { top: 0.82, bottom: 0 } });
        var d = []; for (var i = 0; i < arr.length; i++) if (arr[i] !== null) d.push({ time: times[i], value: arr[i] }); s.setData(d);
      }
      if (st.ind.sma5) addLine(sma(closes, 5), '#f5c451');
      if (st.ind.sma20) addLine(sma(closes, 20), '#e879f9');
      if (st.ind.sma60) addLine(sma(closes, 60), '#38bdf8');
      if (st.ind.sma120) addLine(sma(closes, 120), '#fb923c');
      if (st.ind.boll) { var b = bollinger(closes, 20, 2); addLine(b.up, '#8aa0c0'); addLine(b.mid, '#5a6e90'); addLine(b.lo, '#8aa0c0'); }
      if (st.ind.volma) { var vv = candles.map(function (c) { return c.v; }); addVolLine(sma(vv, 5), '#f5c451'); addVolLine(sma(vv, 20), '#4d9eff'); }
      if (st.ind.ichimoku) {
        var ich = ichimoku(candles), saS = [], sbS = [], chiS = [];
        for (var i = 0; i < candles.length; i++) {
          saS.push(i >= 26 ? ich.spanA[i - 26] : null);   // 선행스팬 26봉 앞으로
          sbS.push(i >= 26 ? ich.spanB[i - 26] : null);
          chiS.push(i + 26 < candles.length ? closes[i + 26] : null); // 후행스팬 26봉 뒤로
        }
        addLine(ich.tenkan, '#ff5470');   // 전환선
        addLine(ich.kijun, '#4d9eff');    // 기준선
        addLine(saS, '#19c37d');          // 선행스팬1 (구름 상단)
        addLine(sbS, '#fb923c');          // 선행스팬2 (구름 하단)
        addLine(chiS, '#a78bfa');         // 후행스팬
      }
      var mainLabel = document.getElementById('sd-mainlabel');
      if (mainLabel) mainLabel.innerHTML = '📈 가격 · 거래량' + (st.ind.ichimoku
        ? ' <span style="font-weight:400;">| 일목 <span style="color:#ff5470;">전환</span>·<span style="color:#4d9eff;">기준</span>·<span style="color:#19c37d;">선행1</span>·<span style="color:#fb923c;">선행2</span>·<span style="color:#a78bfa;">후행</span></span>'
        : '');
      // 기본은 최근 ~120봉만 표시 → 마우스 휠로 축소하면 그 전 과거 데이터가 펼쳐진다.
      var nbars = candles.length, showBars = Math.min(nbars, 120);
      chart.timeScale().setVisibleLogicalRange({ from: nbars - showBars, to: nbars });

      /* OHLC 마우스오버 툴팁 */
      if (ohlcEl) {
        function tnum(t) { if (t == null) return null; if (typeof t === 'number') return t; if (typeof t === 'object') return t.year * 10000 + t.month * 100 + t.day; var m = String(t).split('-'); return (+m[0]) * 10000 + (+m[1]) * 100 + (+m[2]); }
        var tidx = {}; for (var i = 0; i < times.length; i++) { var k = tnum(times[i]); if (k != null) tidx[k] = i; }
        function fmt(n) { return (n == null) ? '-' : Number(n).toLocaleString(); }
        chart.subscribeCrosshairMove(function (param) {
          if (!param || param.time == null) { ohlcEl.style.display = 'none'; return; }
          var idx = tidx[tnum(param.time)];
          if (idx == null) { ohlcEl.style.display = 'none'; return; }
          var c = candles[idx], col = (c.c >= c.o) ? '#ff5470' : '#19c37d';
          ohlcEl.innerHTML = '<b style="color:#cdd9ea;">' + esc(c.t || '') + '</b>&nbsp; 시 ' + fmt(c.o)
            + '  고 <span style="color:#ff5470;">' + fmt(c.h) + '</span>  저 <span style="color:#19c37d;">' + fmt(c.l)
            + '</span>  종 <span style="color:' + col + ';">' + fmt(c.c) + '</span>  거래량 ' + fmt(c.v);
          ohlcEl.style.display = 'block';
        });
      }

      /* RSI (70/30 기준선) */
      if (st.ind.rsi && rsiBox) {
        var rc = subChart(rsiBox, 90); st.rsiChart = rc;
        var rs = rc.addLineSeries({ color: '#f5c451', lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
        rs.setData(seriesData(rsiCalc(closes, 14)));
        rs.createPriceLine({ price: 70, color: '#5a6e90', lineWidth: 1, lineStyle: 2, axisLabelVisible: true });
        rs.createPriceLine({ price: 30, color: '#5a6e90', lineWidth: 1, lineStyle: 2, axisLabelVisible: true });
        syncCharts.push(rc);
      }
      /* MACD (MACD선·시그널·히스토그램) */
      if (st.ind.macd && macdBox) {
        var mc = subChart(macdBox, 110); st.macdChart = mc;
        var m = macdCalc(closes);
        var hS = mc.addHistogramSeries({ priceLineVisible: false, lastValueVisible: false });
        hS.setData(times.map(function (t, i) { return { time: t, value: m.hist[i], color: m.hist[i] >= 0 ? 'rgba(255,84,112,0.5)' : 'rgba(25,195,125,0.5)' }; }));
        var mL = mc.addLineSeries({ color: '#4d9eff', lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
        mL.setData(times.map(function (t, i) { return { time: t, value: m.macd[i] }; }));
        var sL = mc.addLineSeries({ color: '#f5c451', lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
        sL.setData(times.map(function (t, i) { return { time: t, value: m.signal[i] }; }));
        syncCharts.push(mc);
      }
      /* 스토캐스틱 (%K·%D, 80/20 기준선) */
      if (st.ind.stoch && stochBox) {
        var sc2 = subChart(stochBox, 90); st.stochChart = sc2;
        var stoch = stochCalc(candles, 14, 3);
        var kS = sc2.addLineSeries({ color: '#4d9eff', lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
        var dS = sc2.addLineSeries({ color: '#f5c451', lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
        kS.setData(seriesData(stoch.k)); dS.setData(seriesData(stoch.d));
        kS.createPriceLine({ price: 80, color: '#5a6e90', lineWidth: 1, lineStyle: 2, axisLabelVisible: true });
        kS.createPriceLine({ price: 20, color: '#5a6e90', lineWidth: 1, lineStyle: 2, axisLabelVisible: true });
        syncCharts.push(sc2);
      }
      /* OBV (누적 거래량) */
      if (st.ind.obv && obvBox) {
        var oc = subChart(obvBox, 90); st.obvChart = oc;
        var oS = oc.addLineSeries({ color: '#4d9eff', lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
        var ov = obvCalc(candles);
        oS.setData(times.map(function (t, i) { return { time: t, value: ov[i] }; }));
        syncCharts.push(oc);
      }
      /* MFI (거래량 반영 RSI, 80/20 기준선) */
      if (st.ind.mfi && mfiBox) {
        var fc = subChart(mfiBox, 90); st.mfiChart = fc;
        var fS = fc.addLineSeries({ color: '#f5c451', lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
        fS.setData(seriesData(mfiCalc(candles, 14)));
        fS.createPriceLine({ price: 80, color: '#5a6e90', lineWidth: 1, lineStyle: 2, axisLabelVisible: true });
        fS.createPriceLine({ price: 20, color: '#5a6e90', lineWidth: 1, lineStyle: 2, axisLabelVisible: true });
        syncCharts.push(fc);
      }

      /* 메인 차트 줌/스크롤에 보조지표 패널 동기화 (logical range) */
      if (syncCharts.length > 1) {
        var initRange = chart.timeScale().getVisibleLogicalRange();
        for (var si = 1; si < syncCharts.length; si++) { if (initRange) syncCharts[si].timeScale().setVisibleLogicalRange(initRange); }
        var syncing = false;
        syncCharts.forEach(function (src) {
          src.timeScale().subscribeVisibleLogicalRangeChange(function (range) {
            if (syncing || !range) return;
            syncing = true;
            syncCharts.forEach(function (dst) { if (dst !== src) dst.timeScale().setVisibleLogicalRange(range); });
            syncing = false;
          });
        });
      }
    }

    /* ── 관련 뉴스 리스트 (클릭 시 기사 링크 새 탭) ── */
    function renderNews(list) {
      var box = document.getElementById('sd-news'); if (!box) return;
      list = list || [];
      var head = '<div style="font-weight:700;color:#9fc0ff;margin:0 0 8px;">📰 관련 뉴스</div>';
      if (!list.length) {
        box.innerHTML = head + '<div style="color:#7090b0;font-size:13px;padding:4px 2px;">관련 분석 뉴스가 없습니다.</div>';
        return;
      }
      box.innerHTML = head + list.map(function (n) {
        var lab = (n.type === 'GOOD') ? '호재' : (n.type === 'BAD') ? '악재' : '중립';
        var col = (n.type === 'GOOD') ? '#ff5470' : (n.type === 'BAD') ? '#19c37d' : '#9fb0c8';
        var sc = (n.score !== undefined && n.score !== null && n.score !== 0) ? ' ' + (n.score > 0 ? '+' : '') + n.score : '';
        var link = n.naver_link || n.original_link || '';
        return '<div class="sd-news-item" data-id="' + n.id + '" data-link="' + esc(link) + '"'
          + ' style="display:flex;gap:10px;align-items:center;padding:9px 11px;border:1px solid #2a3a55;border-radius:8px;margin-bottom:6px;cursor:pointer;background:#16203a;">'
          + '<span style="flex:0 0 auto;font-size:11px;font-weight:700;color:' + col + ';border:1px solid ' + col + ';border-radius:10px;padding:1px 8px;">' + lab + sc + '</span>'
          + '<span style="flex:1;min-width:0;font-size:13px;color:#dce6f5;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">' + esc(n.title) + '</span>'
          + '<span style="flex:0 0 auto;font-size:11px;color:#7d92b0;white-space:nowrap;">' + esc(n.press || '') + (n.pub_date ? ' · ' + esc(n.pub_date) : '') + '</span></div>';
      }).join('');
      box.querySelectorAll('.sd-news-item').forEach(function (it) {
        it.addEventListener('click', function () {
          var url = this.getAttribute('data-link'), id = this.getAttribute('data-id');
          if (url) window.open(url.indexOf('mock.link') !== -1 ? ctx + '/user/mock_article.jsp?id=' + id : url, '_blank');
        });
      });
    }

    /* ── 호재/악재 타임라인 (날짜별 영향도 히스토그램) ── */
    function renderTimeline(list) {
      var wrap = document.getElementById('sd-timeline-wrap'); if (!wrap) return;
      var chartBox = document.getElementById('sd-timeline-chart');
      if (st.tlChart) { st.tlChart.remove(); st.tlChart = null; }
      list = list || [];
      if (!list.length || !window.LightweightCharts) { wrap.style.display = 'none'; return; }
      wrap.style.display = 'block';
      if (chartBox) chartBox.innerHTML = '';
      var byDate = {};
      list.forEach(function (p) { if (!p.date) return; byDate[p.date] = (byDate[p.date] || 0) + (p.score || 0); });
      var dates = Object.keys(byDate).sort();
      var tc = LightweightCharts.createChart(chartBox, {
        layout: { background: { color: 'transparent' }, textColor: '#a0b4d0' },
        grid: { vertLines: { color: '#22304d' }, horzLines: { color: '#22304d' } },
        rightPriceScale: { borderColor: '#2a3a55' }, timeScale: { borderColor: '#2a3a55' },
        crosshair: { mode: 0 }, width: chartBox.clientWidth, height: 120
      });
      st.tlChart = tc;
      var h = tc.addHistogramSeries({ priceLineVisible: false, lastValueVisible: false });
      h.setData(dates.map(function (d) { var v = byDate[d]; return { time: d, value: v, color: v >= 0 ? 'rgba(255,84,112,0.85)' : 'rgba(25,195,125,0.85)' }; }));
      h.createPriceLine({ price: 0, color: '#5a6e90', lineWidth: 1, lineStyle: 2, axisLabelVisible: false });
      tc.timeScale().fitContent();
    }

    function loadDetail() {
      fetch(ctx + '/api/stock/info?type=details&tf=' + encodeURIComponent(st.tf) + '&code=' + encodeURIComponent(code))
        .then(function (r) { return r.json(); })
        .then(function (data) {
          renderCompany(data);
          st.candles = (data.chart && data.chart.candles) ? data.chart.candles : [];
          renderChart();
          renderTimeline(data.timeline);
          renderNews(data.news);
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
    var presetBtn = host.querySelector('.sd-preset');
    if (presetBtn) presetBtn.addEventListener('click', function () {
      var preset = { sma5: false, sma20: true, sma60: true, sma120: false, boll: true, volma: true, ichimoku: false, rsi: true, macd: false, stoch: false, obv: false, mfi: false };
      for (var k in preset) st.ind[k] = preset[k];
      host.querySelectorAll('.sd-ind').forEach(function (cb) { cb.checked = !!st.ind[cb.getAttribute('data-ind')]; });
      renderChart();
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

  /* 섹터 추세(구성종목 과반 등락) 로드 → 이슈 칩 색칠 갱신 */
  function loadSectorTrends() {
    fetch(ctx + '/api/sector/trend').then(function (r) { return r.json(); }).then(function (d) {
      sectorTrends = (d && d.trends) ? d.trends : {};
      renderFilteredIssues();
      renderSectors(allSectorsData); // 주요 연관 섹터 카드에도 등락률 반영
      renderHotIssues();             // 핫이슈 카드의 강세 섹터 칩 갱신
    }).catch(function () {});
  }

  /* 오늘의 핫이슈 — 헤드라인 + 한 줄씩 슬라이드되는 이슈 캐러셀 */
  function hiHeadline(d, ups) {
    if (d.aiSynth && d.headline) return d.headline;
    var k = lastMacroData['KOSPI'], kr = (k && k.ratio != null) ? parseFloat(String(k.ratio).replace(/,/g, '')) : null, mood = '';
    if (kr != null) {
      mood = kr >= 1.5 ? '코스피 강세' : kr > 0 ? '코스피 강보합' : kr <= -1.5 ? '코스피 약세' : kr < 0 ? '코스피 약보합' : '코스피 보합';
      mood += ' (' + (kr > 0 ? '+' : '') + kr + '%)';
    }
    var themes = ups.slice(0, 3).map(function (x) { return x.n; });
    var h = (mood || '오늘의 시장') + (themes.length ? ' — ' + themes.join('·') + ' 주도' : '');
    if (!mood && !themes.length) h = d.headline || '오늘의 핫이슈';
    return h;
  }
  function hiChipsHtml(ups) {
    var c = ups.map(function (x) {
      return '<span class="hi-sector-chip" data-sector="' + esc(x.n) + '" title="' + esc(x.n) + ' 관련 종목 보기" style="font-size:12px; font-weight:700; padding:3px 10px; border-radius:14px; background:rgba(255,84,112,0.15); border:1px solid #ff5470; color:#ff7a93; cursor:pointer;">' + esc(x.n) + ' +' + x.p + '%</span>';
    }).join('');
    return c ? '<span style="font-size:12px; color:#7d92b0; margin-right:2px;">강세 섹터</span>' + c : '';
  }
  function renderHotIssues() {
    var card = document.getElementById('hotIssueCard'); if (!card) return;
    var d = hotIssueData;
    // 아직 응답 전(스켈레톤 표시 중)에는 숨기지 않는다.
    // 섹터 15초 갱신이 renderHotIssues를 먼저 호출해 카드를 숨겨버리던 문제(핫이슈 API가 LLM 재생성 시 20초+ 소요).
    if (!d) return;
    if (!d.enabled) { card.style.display = 'none'; if (hotIssueTimer) { clearInterval(hotIssueTimer); hotIssueTimer = null; } return; }
    var ups = Object.keys(sectorTrends).map(function (k) { return { n: k, p: sectorTrends[k].p, d: sectorTrends[k].d }; })
      .filter(function (x) { return x.d === 'UP'; }).sort(function (a, b) { return b.p - a.p; }).slice(0, 4);
    var headline = hiHeadline(d, ups), chipsHtml = hiChipsHtml(ups), bullets = d.bullets || [];
    var sig = JSON.stringify(bullets);

    // 같은 불릿이면 슬라이더는 그대로 두고 헤드라인/칩만 갱신(15초 섹터 refresh 시 슬라이드 리셋 방지)
    if (card.getAttribute('data-sig') === sig && card.querySelector('[data-f="hi-track"]')) {
      var hEl = card.querySelector('[data-f="hi-headline"]'); if (hEl) hEl.textContent = headline;
      var cEl = card.querySelector('[data-f="hi-chips"]'); if (cEl) cEl.innerHTML = chipsHtml;
      return;
    }
    card.setAttribute('data-sig', sig);

    var slides = bullets.map(function (b) {
      var cls = (b.type !== 'BAD') ? 'sig good' : 'sig bad';   // 주요 이슈와 동일한 호재/악재 색
      var label = (b.type !== 'BAD') ? '호재' : '악재';
      return '<div style="flex:0 0 100%; min-width:100%; display:flex; gap:9px; align-items:center; box-sizing:border-box;">'
        + '<span class="' + cls + '" style="margin:0; flex:0 0 auto;">' + label + '</span>'
        + '<span style="font-size:14px; color:#cdd9ea; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">' + esc(b.text) + '</span></div>';
    });
    var trackInner = slides.join('') + (slides.length > 1 ? slides[0] : ''); // 무한 루프용 첫 슬라이드 복제
    var dots = bullets.map(function (_, i) {
      return '<span class="hi-dot" style="width:6px; height:6px; border-radius:50%; background:#9fb0c8; opacity:' + (i === 0 ? '1' : '0.3') + '; transition:opacity .3s;"></span>';
    }).join('');
    var slider = bullets.length
      ? '<div style="overflow:hidden; height:30px;"><div data-f="hi-track" style="display:flex; height:30px; align-items:center; transition:transform .45s ease; will-change:transform;">' + trackInner + '</div></div>'
      : '<div style="color:#7090b0; font-size:13px;">오늘 분석된 이슈가 없습니다.</div>';

    card.innerHTML =
      '<div style="display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:4px 10px; margin-bottom:12px;">'
      + '<h2 style="margin:0;">🔥 오늘의 핫이슈</h2>'
      + '<span style="font-size:12px; color:#7d92b0; white-space:nowrap;">' + (d.aiSynth ? 'AI 요약' : '자동 요약') + ' · ' + esc(d.generatedAt || '') + ' 기준</span>'
      + '</div>'
      + '<div data-f="hi-headline" style="background:#16203a; border:1px solid #2a3a55; border-radius:8px; padding:11px 14px; font-size:15px; font-weight:600; color:#e8eef7; margin-bottom:14px; line-height:1.5;">' + esc(headline) + '</div>'
      + slider
      + (bullets.length > 1 ? '<div style="display:flex; gap:5px; align-items:center; margin-top:14px;">' + dots + '</div>' : '')
      + '<div data-f="hi-chips" style="display:flex; flex-wrap:wrap; gap:6px; align-items:center; margin-top:' + (bullets.length > 1 ? '10px' : '14px') + ';">' + chipsHtml + '</div>';
    card.style.display = 'block';
    startHotIssueSlider(card, bullets.length);
  }
  function startHotIssueSlider(card, n) {
    if (hotIssueTimer) { clearInterval(hotIssueTimer); hotIssueTimer = null; }
    if (n <= 1) return;
    var track = card.querySelector('[data-f="hi-track"]'), dots = card.querySelectorAll('.hi-dot');
    if (!track) return;
    var idx = 0;
    function dotsTo(real) { for (var i = 0; i < dots.length; i++) dots[i].style.opacity = (i === real) ? '1' : '0.3'; }
    function move(anim) { track.style.transition = anim ? 'transform .45s ease' : 'none'; track.style.transform = 'translateX(-' + (idx * 100) + '%)'; }
    function advance() {
      idx++; move(true); dotsTo(idx % n);
      if (idx === n) setTimeout(function () { idx = 0; move(false); }, 480); // 복제 슬라이드 후 무애니 리셋
    }
    function play() { if (hotIssueTimer) clearInterval(hotIssueTimer); hotIssueTimer = setInterval(advance, 3500); }
    play();
    card.onmouseenter = function () { if (hotIssueTimer) { clearInterval(hotIssueTimer); hotIssueTimer = null; } };
    card.onmouseleave = play;
  }
  /* 핫이슈 API는 캐시 만료 시 LLM 재생성으로 수 초 걸린다.
     그동안 카드가 display:none이면 "핫이슈가 없다"고 오해되므로 스켈레톤을 먼저 띄운다. */
  function showHotIssueSkeleton() {
    var card = document.getElementById('hotIssueCard'); if (!card) return;
    if (card.getAttribute('data-sig')) return;           // 이미 실제 데이터가 렌더된 경우엔 건드리지 않음
    card.innerHTML =
      '<div style="display:flex; align-items:center; justify-content:space-between; margin-bottom:12px;">'
      + '<h2 style="margin:0;">🔥 오늘의 핫이슈</h2>'
      + '<span style="font-size:12px; color:#7d92b0;">요약 준비 중…</span>'
      + '</div>'
      + '<div style="background:#16203a; border:1px solid #2a3a55; border-radius:8px; padding:11px 14px;'
      + ' font-size:14px; color:#7d92b0; line-height:1.5;">오늘의 핫이슈를 불러오는 중입니다…</div>';
    card.style.display = 'block';
  }
  function loadHotIssues() {
    showHotIssueSkeleton();
    fetch(ctx + '/api/hotissues').then(function (r) { return r.json(); }).then(function (d) {
      hotIssueData = d; renderHotIssues();
    }).catch(function () {
      var card = document.getElementById('hotIssueCard');
      if (card && !card.getAttribute('data-sig')) card.style.display = 'none'; // 실패 시 조용히 숨김
    });
  }

  /* ───────────── 시총 히트맵 (스퀘어파이드 트리맵) ───────────── */
  var heatmapMk = 'kospi';
  var heatmapData = {};
  function heatColor(pct) { // 상승=빨강, 하락=초록(한국식), 강도는 |pct|(5%에서 최대)
    var m = Math.min(Math.abs(pct) / 5, 1);
    if (pct > 0.05) return 'rgba(214,64,90,' + (0.20 + 0.6 * m).toFixed(2) + ')';
    if (pct < -0.05) return 'rgba(25,165,110,' + (0.20 + 0.6 * m).toFixed(2) + ')';
    return 'rgba(90,105,130,0.32)';
  }
  function squarify(data, X, Y, W, H) { // data: [{value}] desc → [{x,y,w,h,d}]
    var out = [], total = 0; data.forEach(function (n) { total += n.value; });
    if (total <= 0) return out;
    var vals = data.map(function (n) { return n.value * (W * H) / total; });
    function worst(row, len) {
      var s = 0, mx = -Infinity, mn = Infinity;
      for (var i = 0; i < row.length; i++) { s += row[i]; if (row[i] > mx) mx = row[i]; if (row[i] < mn) mn = row[i]; }
      var s2 = s * s, l2 = len * len; return Math.max(l2 * mx / s2, s2 / (l2 * mn));
    }
    var rx = X, ry = Y, rw = W, rh = H, i = 0;
    while (i < vals.length) {
      var row = [vals[i]], idx = [i], j = i, len = Math.min(rw, rh);
      while (j + 1 < vals.length && worst(row.concat([vals[j + 1]]), len) <= worst(row, len)) { j++; row.push(vals[j]); idx.push(j); }
      var sum = 0; row.forEach(function (v) { sum += v; });
      if (rw >= rh) {
        var cw = sum / rh, cy = ry;
        for (var k = 0; k < row.length; k++) { var th = row[k] / cw; out.push({ x: rx, y: cy, w: cw, h: th, d: data[idx[k]] }); cy += th; }
        rx += cw; rw -= cw;
      } else {
        var ch = sum / rw, cx = rx;
        for (var k2 = 0; k2 < row.length; k2++) { var tw = row[k2] / ch; out.push({ x: cx, y: ry, w: tw, h: ch, d: data[idx[k2]] }); cx += tw; }
        ry += ch; rh -= ch;
      }
      i = j + 1;
    }
    return out;
  }
  function renderHeatmap() {
    var box = document.getElementById('heatmapBox'); if (!box) return;
    var items = heatmapData[heatmapMk] || [];
    if (!items.length) { box.innerHTML = '<div style="color:#7090b0;text-align:center;padding:40px;">데이터를 불러오는 중…</div>'; return; }
    var W = box.clientWidth || 800, H = box.clientHeight || 340;
    var tiles = squarify(items.map(function (it) { return { value: it.cap, it: it }; }), 0, 0, W, H);
    box.innerHTML = tiles.map(function (t) {
      var it = t.d.it;
      var showName = t.w > 24 && t.h > 12;                 // 작은 타일도 이름 표시
      var showPct = t.w > 50 && t.h > 30;                  // 등락%는 넉넉한 타일만
      var fs = Math.max(7, Math.min(14, Math.floor(Math.min(t.w / 5.2, t.h / 2.3))));
      var sign = it.chgPct > 0 ? '+' : '';
      return '<div class="hm-tile" data-code="' + esc(it.code) + '" data-name="' + esc(it.name) + '" title="' + esc(it.name) + ' ' + sign + it.chgPct + '%"'
        + ' style="left:' + t.x + 'px;top:' + t.y + 'px;width:' + t.w + 'px;height:' + t.h + 'px;background:' + heatColor(it.chgPct) + ';">'
        + (showName ? '<span class="nm" style="font-size:' + fs + 'px;">' + esc(it.name) + '</span>' : '')
        + (showPct ? '<span class="pc" style="font-size:' + Math.max(7, fs - 3) + 'px;">' + sign + it.chgPct + '%</span>' : '')
        + '</div>';
    }).join('');
  }
  function loadHeatmap() {
    fetch(ctx + '/api/market?view=heatmap&market=' + heatmapMk)
      .then(function (r) { return r.json(); })
      .then(function (d) { heatmapData[heatmapMk] = d.items || []; renderHeatmap(); })
      .catch(function () {});
  }
  function setupHeatmap() {
    var tabs = document.getElementById('heatmapTabs'); if (!tabs) return;
    function setActive() { tabs.querySelectorAll('.hm-tab').forEach(function (b) { b.classList.toggle('on', b.getAttribute('data-mk') === heatmapMk); }); }
    setActive();
    tabs.addEventListener('click', function (e) {
      var b = e.target.closest('.hm-tab'); if (!b) return;
      heatmapMk = b.getAttribute('data-mk'); setActive();
      if (heatmapData[heatmapMk]) renderHeatmap(); else { var box = document.getElementById('heatmapBox'); if (box) box.innerHTML = '<div style="color:#7090b0;text-align:center;padding:40px;">데이터를 불러오는 중…</div>'; loadHeatmap(); }
    });
    var box = document.getElementById('heatmapBox');
    if (box) box.addEventListener('click', function (e) {
      var t = e.target.closest('.hm-tile'); if (!t) return;
      openStockModal(t.getAttribute('data-code'), t.getAttribute('data-name'));
    });
    var rt; window.addEventListener('resize', function () { clearTimeout(rt); rt = setTimeout(renderHeatmap, 200); });
    loadHeatmap();
  }

  /* ───────────── 오늘의 종목 랭킹 ───────────── */
  var moversMetric = 'value';
  function fmtWon(v) {
    if (v >= 1e12) return (v / 1e12).toFixed(1) + '조';
    if (v >= 1e8) return Math.round(v / 1e8).toLocaleString() + '억';
    if (v >= 1e4) return Math.round(v / 1e4).toLocaleString() + '만';
    return (v || 0).toLocaleString();
  }
  function renderMovers(list) {
    var box = document.getElementById('moversBox'); if (!box) return;
    if (!list || !list.length) { box.innerHTML = '<div style="color:#7090b0;text-align:center;padding:24px;">데이터 없음</div>'; return; }
    box.innerHTML = list.map(function (r, i) {
      var col = r.chgPct > 0 ? '#ff5470' : r.chgPct < 0 ? '#19c37d' : '#9fb0c8';
      var arrow = r.chgPct > 0 ? '▲' : r.chgPct < 0 ? '▼' : '-';
      var sub = (moversMetric === 'volume') ? ((r.volume || 0).toLocaleString() + '주') : fmtWon(r.tradingValue);
      return '<div class="mv-row" data-code="' + esc(r.code) + '" data-name="' + esc(r.name) + '">'
        + '<span class="rk">' + (i + 1) + '</span>'
        + '<span class="nm">' + esc(r.name) + ' <span class="cd">' + esc(r.code) + '</span></span>'
        + '<span class="prc">' + (r.price || 0).toLocaleString() + '</span>'
        + '<span class="val">' + sub + '</span>'
        + '<span class="pc" style="color:' + col + ';">' + arrow + ' ' + Math.abs(r.chgPct) + '%</span></div>';
    }).join('');
  }
  function loadMovers() {
    fetch(ctx + '/api/market?view=movers&metric=' + moversMetric)
      .then(function (r) { return r.json(); })
      .then(function (d) { renderMovers(d.results || []); })
      .catch(function () {});
  }
  function setupMovers() {
    var tabs = document.getElementById('moversTabs'); if (!tabs) return;
    function setActive() { tabs.querySelectorAll('.mv-tab').forEach(function (b) { b.classList.toggle('on', b.getAttribute('data-metric') === moversMetric); }); }
    setActive();
    tabs.addEventListener('click', function (e) {
      var b = e.target.closest('.mv-tab'); if (!b) return;
      moversMetric = b.getAttribute('data-metric'); setActive();
      document.getElementById('moversBox').innerHTML = '<div style="color:#7090b0;text-align:center;padding:24px;">불러오는 중…</div>';
      loadMovers();
    });
    var box = document.getElementById('moversBox');
    if (box) box.addEventListener('click', function (e) {
      var r = e.target.closest('.mv-row'); if (!r) return;
      openStockModal(r.getAttribute('data-code'), r.getAttribute('data-name'));
    });
    loadMovers();
  }

  /* ───────────── 장중 시황정리 ───────────── */
  function renderMarketBrief(d) {
    var box = document.getElementById('marketBriefInline'); if (!box) return;
    if (!d || !d.investors) { box.style.display = 'none'; return; }
    function invCard(label, inv) {
      if (!inv) return '';
      function cell(nm, v) {
        var n = parseFloat(String(v).replace(/[+,]/g, '')) || 0;
        var col = n > 0 ? '#ff5470' : n < 0 ? '#19c37d' : '#9fb0c8';
        return '<div style="flex:1; text-align:center; padding:2px 4px;">'
          + '<div style="font-size:10.5px; color:#7d92b0; margin-bottom:2px;">' + nm + '</div>'
          + '<div style="font-size:13.5px; font-weight:700; color:' + col + ';">' + esc(v || '-') + '</div></div>';
      }
      return '<div style="flex:1; min-width:220px; background:var(--panel2); border:1px solid var(--line); border-radius:9px; padding:9px 10px;">'
        + '<div style="font-size:11.5px; color:#9fc0ff; font-weight:600; margin-bottom:6px;">' + label + ' <span style="color:#6f83a0; font-weight:400;">순매수 (억원)</span></div>'
        + '<div style="display:flex; align-items:stretch;">'
        + cell('외국인', inv.foreign) + '<div style="width:1px; background:var(--line);"></div>'
        + cell('기관', inv.institution) + '<div style="width:1px; background:var(--line);"></div>'
        + cell('개인', inv.personal) + '</div></div>';
    }
    var lines = (d.lines || []).map(function (t) {
      return '<li style="margin-bottom:4px; line-height:1.5;">' + esc(t) + '</li>';
    }).join('');
    box.innerHTML =
      '<div style="display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:6px; margin-bottom:10px;">'
      + '<span style="font-size:13px; font-weight:700; color:#e8eef7;">📈 장중 시황 · 투자자 수급</span>'
      + '<span style="font-size:11px; color:#7d92b0;">' + esc(d.generatedAt || '') + ' 기준 · 90초 갱신</span></div>'
      + (d.investors ? '<div style="display:flex; flex-wrap:wrap; gap:12px;">' + invCard('코스피', d.investors.kospi) + invCard('코스닥', d.investors.kosdaq) + '</div>' : '')
      + '<ul style="margin:11px 0 0; padding-left:18px; font-size:13px; color:#cdd9ea; border-top:1px dashed var(--line); padding-top:10px;">' + lines + '</ul>';
    box.style.display = 'block';
  }
  function loadMarketBrief() {
    fetch(ctx + '/api/market/brief').then(function (r) { return r.json(); })
      .then(renderMarketBrief).catch(function () {});
  }

  /* ───────────── 실적발표 피드 (DART 잠정실적 공시) ───────────── */
  function renderEarnings(d) {
    var card = document.getElementById('earningsCard'); if (!card) return;
    var upcoming = (d && d.upcoming) || [];
    var days = (d && d.days) || [];
    if (!upcoming.length && !days.length) { card.style.display = 'none'; return; }
    var WD = ['일', '월', '화', '수', '목', '금', '토'];
    var todayRaw = (function () { var t = new Date(); return '' + t.getFullYear() + ('0' + (t.getMonth() + 1)).slice(-2) + ('0' + t.getDate()).slice(-2); })();
    function mkTag(m) {
      if (m === 'KOSPI') return '<span class="er-mk kp">KP</span>';
      if (m === 'KOSDAQ') return '<span class="er-mk kd">KQ</span>';
      return '';
    }
    function cols(list, isUp) {
      return list.slice(0, 7).map(function (day) {
        var raw = day.raw || '', wd = '', md = esc(day.date);
        if (raw.length === 8) {
          var jd = new Date(+raw.slice(0, 4), +raw.slice(4, 6) - 1, +raw.slice(6, 8));
          wd = WD[jd.getDay()];
          md = raw.slice(4, 6) + '/' + raw.slice(6, 8);
        }
        var isToday = raw === todayRaw;
        var items = (day.items || []).map(function (it) {
          return '<div class="er-item" data-code="' + esc(it.code) + '" data-name="' + esc(it.name) + '">'
            + '<span class="er-nm">' + esc(it.name) + '</span>'
            + '<span class="er-cd">' + (it.market ? mkTag(it.market) : '') + esc(it.code) + '</span></div>';
        }).join('');
        return '<div class="er-day' + (isUp ? ' er-up' : '') + (isToday ? ' er-today' : '') + '">'
          + '<div class="er-day-head"><span class="er-date">' + md + ' <small>(' + wd + ')</small>' + (isToday ? ' <b class="er-badge">오늘</b>' : '') + '</span>'
          + '<span class="er-cnt">' + day.count + '건</span></div>'
          + '<div class="er-list">' + items + '</div></div>';
      }).join('');
    }
    card.innerHTML =
      '<div style="display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:6px; margin-bottom:14px;">'
      + '<h2 style="margin:0;">🗓️ 실적발표 <span style="font-size:12px; color:#7d92b0; font-weight:400;">예정 · 발표 · DART</span></h2>'
      + '<span style="font-size:12px; color:#7d92b0;">' + esc(d.generatedAt || '') + ' 기준</span></div>'
      + (upcoming.length ? '<div class="er-sub">📅 발표 예정 <small>공시예고 기준</small></div><div class="er-grid" style="margin-bottom:' + (days.length ? '18px' : '0') + ';">' + cols(upcoming, true) + '</div>' : '')
      + (days.length ? '<div class="er-sub">✅ 발표됨 <small>영업(잠정)실적</small></div><div class="er-grid">' + cols(days, false) + '</div>' : '');
    card.style.display = 'block';
    card.querySelectorAll('.er-item').forEach(function (el) {
      el.addEventListener('click', function () { openStockModal(el.getAttribute('data-code'), el.getAttribute('data-name')); });
    });
  }
  function loadEarnings() {
    fetch(ctx + '/api/earnings').then(function (r) { return r.json(); })
      .then(renderEarnings).catch(function () {});
  }

  /* ───────────── 실시간 업데이트 (15초 주기) ───────────── */
  function refreshRealtimeData() {
    // 거시경제 지표 갱신
    fetch(ctx + '/api/macro/prices')
      .then(function(r) { return r.json(); })
      .then(function(res) {
        var macroData = res.prices || {};
        lastMacroData = macroData;
        renderMacro(lastMacroSignals, macroData);
        renderFilteredIssues(); // 매크로 칩 색 최신화
      })
      .catch(function(){});

    loadSectorTrends(); // 섹터 추세 갱신(서버 90초 캐시)

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
  loadWatchlistState();   // 로그인 시 관심목록 상태(★) 동기화
  setupGlobalStockSearch(); // 상시 종목 검색
  loadSectorTrends();     // 섹터 칩 등락 색칠
  loadHotIssues();        // 오늘의 핫이슈 AI 요약
  setupHeatmap();         // 시총 히트맵
  setupMovers();          // 오늘의 종목 랭킹
  loadMarketBrief();      // 장중 시황정리
  loadEarnings();         // 실적발표 피드

  // 카드 미니 그래프(스파크라인) 1회 로드 — 일별 시계열이라 매번 갱신 불필요
  fetch(ctx + '/api/macro/prices?type=sparkline')
    .then(function (r) { return r.json(); })
    .then(function (d) {
      macroSparklines = (d && d.sparklines) || {};
      if (Object.keys(lastMacroData).length) renderMacro(lastMacroSignals, lastMacroData);
    })
    .catch(function () {});
})();
