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
    return '<div class="item">'
      + '<span class="sig ' + esc((it.type || '').toLowerCase()) + '">' + (LABEL[it.type] || '-') + '</span>'
      + '<h3>' + esc(it.title) + '</h3>'
      + '<div class="meta">영향도 ' + sign + esc(it.score) + ' · 유사 ' + esc(it.dup) + '건</div>'
      + '<div class="chips">' + sectors + '</div></div>';
  }

  function render(data) {
    var issues = (data.topIssues || []).map(row).join('')
      || '<div class="empty">수집된 이슈가 없습니다. 위 버튼을 눌러 시작하세요.</div>';
    document.getElementById('issues').innerHTML = issues;
    document.getElementById('goodNews').innerHTML =
      (data.goodNews || []).map(row).join('') || '<div class="empty">호재 없음</div>';
    document.getElementById('badNews').innerHTML =
      (data.badNews || []).map(row).join('') || '<div class="empty">악재 없음</div>';
  }

  document.getElementById('collectBtn').addEventListener('click', function () {
    var btn = this; btn.disabled = true; btn.textContent = '수집 중…';
    fetch(ctx + '/collect/run', {
      method: 'POST',
      headers: { 'X-CSRF-Token': csrf }
    }).then(function () {
      // 수집은 비동기. 잠시 후 데이터 갱신
      setTimeout(function () {
        load(); btn.disabled = false; btn.textContent = '수집·분석 실행';
      }, 2500);
    }).catch(function () {
      btn.disabled = false; btn.textContent = '수집·분석 실행';
    });
  });

  load();
})();
