(function(){
  var bell = document.getElementById('notifBell');
  if (!bell) return;
  var ctx = document.body.getAttribute('data-ctx') || '';
  var csrf = (document.querySelector('meta[name="csrf-token"]')||{}).content || '';
  var badge = document.getElementById('notifBadge');
  var panel = document.getElementById('notifPanel');
  function esc(s){ return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }

  function refreshCount(){
    fetch(ctx + '/api/my/notifications?action=count').then(function(r){return r.json();}).then(function(d){
      var n = d.unread || 0;
      badge.style.display = n > 0 ? 'inline-block' : 'none';
      badge.textContent = n > 99 ? '99+' : n;
    }).catch(function(){});
  }

  function openPanel(){
    fetch(ctx + '/api/my/notifications').then(function(r){return r.json();}).then(function(d){
      var items = d.items || [];
      panel.innerHTML = items.length ? items.map(function(n){
        var t = n.title || '';
        var color = t.indexOf('악재') >= 0 ? '#19c37d' : (t.indexOf('급변') >= 0 ? '#f5c451' : '#ff5470');
        return '<div class="notif-item" data-link="' + esc(n.link || '') + '" title="클릭하여 대상 보기" style="padding:9px 12px; border-bottom:1px solid #233650; cursor:pointer;' + (n.read ? 'opacity:.55;' : '') + '">'
          + '<div style="font-size:12.5px; font-weight:600; color:' + color + ';">' + esc(t) + '</div>'
          + '<div style="font-size:11px; color:#9fb0c8; margin-top:2px; line-height:1.4;">' + esc((n.body || '').slice(0, 90)) + '</div></div>';
      }).join('') : '<div style="padding:18px; color:#7090b0; text-align:center; font-size:13px;">알림이 없습니다.</div>';
      panel.style.display = 'block';
      // 항목 클릭 → 알림 대상(이슈/종목)으로 이동
      panel.querySelectorAll('.notif-item').forEach(function(it){
        it.addEventListener('click', function(){
          var l = this.getAttribute('data-link');
          if (l) location.href = ctx + l;
        });
      });
      fetch(ctx + '/api/my/notifications', { method:'POST', headers:{ 'X-CSRF-Token':csrf, 'Content-Type':'application/x-www-form-urlencoded' }, body:'' })
        .then(refreshCount).catch(function(){});
    });
  }

  bell.addEventListener('click', function(e){
    e.stopPropagation();
    if (panel.style.display === 'block') panel.style.display = 'none';
    else openPanel();
  });
  document.addEventListener('click', function(){ panel.style.display = 'none'; });
  panel.addEventListener('click', function(e){ e.stopPropagation(); });

  refreshCount();
  setInterval(refreshCount, 30000);

  var lo = document.getElementById('logoutBtn');
  if (lo) lo.addEventListener('click', function(e){
    e.preventDefault();
    fetch(ctx + '/auth/logout', { method:'POST', headers:{ 'X-CSRF-Token':csrf } })
      .then(function(){ location.href = ctx + '/user/dashboard.jsp'; });
  });
})();
