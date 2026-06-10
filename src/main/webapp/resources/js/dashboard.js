/* 대시보드 클라이언트 (운영 골격).
 * - 수집 버튼: CSRF 토큰을 헤더에 담아 POST /collect/run
 * - 데이터: GET /api/dashboard
 * - 출력 시 escape로 XSS 방지(서버 JSP는 c:out, JS는 textContent/escape) */
(function () {
  var ctx = document.body.getAttribute('data-ctx') || '';
  var csrf = (document.querySelector('meta[name="csrf-token"]') || {}).content || '';

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }
  var LABEL = { GOOD: '호재', BAD: '악재', NEUTRAL: '중립', MIXED: '혼합' };

  function load() {
    fetch(ctx + '/api/dashboard')
      .then(function (r) { return r.json(); })
      .then(render)
      .catch(function () {
        document.getElementById('issues').innerHTML =
          '<div class="empty">데이터를 불러오지 못했습니다. 잠시 후 다시 시도하세요.</div>';
      });
  }

  function row(it) {
    var sectors = (it.sectors || '').split(',').filter(Boolean)
      .map(function (s) { return '<span class="chip">' + esc(s.trim()) + '</span>'; }).join('');
    var sign = it.score > 0 ? '+' : '';
    var summaryText = it.summary || it.title; // 요약문이 없으면 타이틀로 폴백
    return '<div class="item" data-group-id="' + it.id + '" style="cursor: pointer;">'
      + '<span class="sig ' + esc((it.type || '').toLowerCase()) + '">' + (LABEL[it.type] || '-') + '</span>'
      + '<h3 class="item-title">' + esc(it.title) + '</h3>'
      + '<p class="item-summary">' + esc(summaryText) + '</p>'
      + '<div class="meta">영향도 ' + sign + esc(it.score) + ' · 유사 ' + esc(it.dup) + '건</div>'
      + '<div class="chips">' + sectors + '</div></div>';
  }

  function render(data) {
    var allList = (data.topIssues || []).concat(data.moreIssues || []);
    var issues = allList.map(row).join('')
      || '<div class="empty">수집된 이슈가 없습니다. 위 버튼을 눌러 시작하세요.</div>';
    document.getElementById('issues').innerHTML = issues;

    document.getElementById('goodNews').innerHTML =
      (data.goodNews || []).map(row).join('') || '<div class="empty">호재 없음</div>';
    document.getElementById('badNews').innerHTML =
      (data.badNews || []).map(row).join('') || '<div class="empty">악재 없음</div>';
    renderMacro(data.macroSignals || []);
    renderStocks(data.stockSignals || []);
  }

  function renderMacro(list) {
    var el = document.getElementById('macroSignals');
    if (!list || !list.length) {
      el.innerHTML = '<div class="empty">지수 및 환율 정보가 없습니다.</div>';
      return;
    }
    el.innerHTML = list.map(function (r) {
      var tot = r.total || 1;
      var avgColor = r.avg > 0 ? 'var(--good)' : r.avg < 0 ? 'var(--bad)' : 'var(--neu)';
      var sign = r.avg > 0 ? '+' : '';
      
      return '<div class="srow">'
        + '<div class="line1">'
        + '  <span class="sname">' + esc(r.name) + '</span>'
        + '  <span class="stype">' + esc(r.type) + '</span>'
        + '  <span class="savg" style="color:' + avgColor + '">평균 ' + sign + esc(r.avg.toFixed(1)) + '</span>'
        + '</div>'
        + '<div class="gbbar">'
        + '  <div class="g" style="width:' + (r.good / tot * 100) + '%"></div>'
        + '  <div class="n" style="width:' + (r.neu / tot * 100) + '%"></div>'
        + '  <div class="b" style="width:' + (r.bad / tot * 100) + '%"></div>'
        + '</div>'
        + '<div class="gbcount">'
        + '  <span><span class="d" style="background:var(--good)"></span>호재 ' + r.good + '</span>'
        + '  <span><span class="d" style="background:var(--neu)"></span>중립 ' + r.neu + '</span>'
        + '  <span><span class="d" style="background:var(--bad)"></span>악재 ' + r.bad + '</span>'
        + '</div>'
        + '</div>';
    }).join('');
  }

  function renderStocks(list) {
    var el = document.getElementById('stockSignals');
    if (!list || !list.length) {
      el.innerHTML = '<div class="empty">종목별 분석 정보가 없습니다.</div>';
      return;
    }
    el.innerHTML = list.map(function (r) {
      var tot = r.total || 1;
      var avgColor = r.avg > 0 ? 'var(--good)' : r.avg < 0 ? 'var(--bad)' : 'var(--neu)';
      var sign = r.avg > 0 ? '+' : '';
      
      return '<div class="srow">'
        + '<div class="line1">'
        + '  <span class="sname">' + esc(r.name) + ' <small style="color:var(--ink3);font-weight:400;font-size:11px;">' + esc(r.code) + '</small></span>'
        + '  <span class="stype">' + esc(r.market) + '</span>'
        + '  <span class="savg" style="color:' + avgColor + '">평균 ' + sign + esc(r.avg.toFixed(1)) + '</span>'
        + '</div>'
        + '<div class="gbbar">'
        + '  <div class="g" style="width:' + (r.good / tot * 100) + '%"></div>'
        + '  <div class="n" style="width:' + (r.neu / tot * 100) + '%"></div>'
        + '  <div class="b" style="width:' + (r.bad / tot * 100) + '%"></div>'
        + '</div>'
        + '<div class="gbcount">'
        + '  <span><span class="d" style="background:var(--good)"></span>호재 ' + r.good + '</span>'
        + '  <span><span class="d" style="background:var(--neu)"></span>중립 ' + r.neu + '</span>'
        + '  <span><span class="d" style="background:var(--bad)"></span>악재 ' + r.bad + '</span>'
        + '</div>'
        + '</div>';
    }).join('');
  }

  // --- 모달 제어 로직 ---
  var modal = document.getElementById('articlesModal');
  var modalCloseBtn = document.getElementById('modalCloseBtn');
  var modalTitle = document.getElementById('modalTitle');
  var modalBody = document.getElementById('modalBody');

  function openArticlesModal(groupId, groupTitle) {
    modalTitle.textContent = groupTitle;
    modalBody.innerHTML = '<div class="loading-spinner">뉴스 불러오는 중...</div>';
    modal.style.display = 'flex';

    fetch(ctx + '/api/group/articles?groupId=' + groupId)
      .then(function (r) {
        if (!r.ok) throw new Error();
        return r.json();
      })
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
               + '  <div class="mfoot">'
               + '    ' + repBadge
               + '    <span class="press">' + esc(n.press) + '</span>'
               + '    <span class="pubdate">' + esc(n.pub_date || '') + '</span>'
               + '  </div>'
               + '</div>';
        }).join('');

        // 기사 클릭 이벤트 바인딩
        var items = modalBody.querySelectorAll('.modal-item');
        items.forEach(function (item) {
          item.addEventListener('click', function () {
            var url = this.getAttribute('data-link');
            var id = this.getAttribute('data-id');
            if (url && url !== '#') {
              if (url.indexOf('mock.link') !== -1) {
                window.open(ctx + '/user/mock_article.jsp?id=' + id, '_blank');
              } else {
                window.open(url, '_blank');
              }
            }
          });
        });
      })
      .catch(function () {
        modalBody.innerHTML = '<div class="empty">뉴스를 불러오지 못했습니다.</div>';
      });
  }

  // 모달 닫기
  if (modalCloseBtn) {
    modalCloseBtn.addEventListener('click', function () {
      modal.style.display = 'none';
    });
  }
  if (modal) {
    modal.addEventListener('click', function (e) {
      if (e.target === modal) {
        modal.style.display = 'none';
      }
    });
  }

  ['issues', 'goodNews', 'badNews'].forEach(function (id) {
    var el = document.getElementById(id);
    if (el) {
      el.addEventListener('click', function (e) {
        var item = e.target.closest('.item');
        if (item) {
          var groupId = item.getAttribute('data-group-id');
          if (groupId) {
            openArticlesModal(groupId, item.querySelector('h3').textContent);
          }
        }
      });
    }
  });

  document.getElementById('collectBtn').addEventListener('click', function () {
    var btn = this; btn.disabled = true; btn.textContent = '수집 중…';
    fetch(ctx + '/collect/run', {
      method: 'POST',
      headers: { 'X-CSRF-Token': csrf }
    }).then(function () {
      setTimeout(function () {
        load(); btn.disabled = false; btn.textContent = '수집·분석 실행';
      }, 2500);
    }).catch(function () {
      btn.disabled = false; btn.textContent = '수집·분석 실행';
    });
  });

  load();
})();
