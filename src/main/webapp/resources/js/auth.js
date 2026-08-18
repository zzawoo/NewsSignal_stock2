(function(){
  var ctx = document.body.getAttribute('data-ctx') || '';
  var csrf = (document.querySelector('meta[name="csrf-token"]')||{}).content || '';
  var msg = document.getElementById('authMsg');
  function show(t,err){ msg.textContent=t; msg.style.color = err ? '#ff5470' : '#19c37d'; }
  function tab(login){
    document.getElementById('loginForm').style.display = login ? 'block':'none';
    document.getElementById('signupForm').style.display = login ? 'none':'block';
    document.getElementById('tabLogin').classList.toggle('primary', login);
    document.getElementById('tabSignup').classList.toggle('primary', !login);
    show('');
  }
  document.getElementById('tabLogin').onclick = function(){ tab(true); };
  document.getElementById('tabSignup').onclick = function(){ tab(false); };
  tab(true);

  function submit(form, url){
    var data = new URLSearchParams(new FormData(form));
    fetch(ctx + url, { method:'POST', headers:{ 'X-CSRF-Token':csrf, 'Content-Type':'application/x-www-form-urlencoded' }, body:data.toString() })
      .then(function(r){ return r.json(); })
      .then(function(res){
        if (res.ok) { show('환영합니다, ' + (res.name||'') + '님!'); setTimeout(function(){ location.href = ctx + '/my/watchlist.jsp'; }, 600); }
        else show(res.error || '실패했습니다.', true);
      })
      .catch(function(){ show('요청 실패', true); });
  }
  document.getElementById('loginForm').addEventListener('submit', function(e){ e.preventDefault(); submit(this, '/auth/login'); });
  document.getElementById('signupForm').addEventListener('submit', function(e){ e.preventDefault(); submit(this, '/auth/register'); });
})();
