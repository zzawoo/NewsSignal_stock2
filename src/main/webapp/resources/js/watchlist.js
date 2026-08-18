(function(){
  var ctx = document.body.getAttribute('data-ctx') || '';
  var csrf = (document.querySelector('meta[name="csrf-token"]')||{}).content || '';
  var setList = document.getElementById('setList');
  var expanded = {};   // setId -> true (펼침 상태 유지)

  function $(id){ return document.getElementById(id); }
  function esc(s){ return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }
  function post(params){
    return fetch(ctx + '/api/my/watchlist', { method:'POST', headers:{'X-CSRF-Token':csrf,'Content-Type':'application/x-www-form-urlencoded'}, body:new URLSearchParams(params).toString() }).then(function(r){return r.json();});
  }
  function postRaw(usp){
    return fetch(ctx + '/api/my/watchlist', { method:'POST', headers:{'X-CSRF-Token':csrf,'Content-Type':'application/x-www-form-urlencoded'}, body:usp.toString() }).then(function(r){return r.json();});
  }
  function search(q){ return fetch(ctx + '/api/my/watchlist?action=searchStock&q=' + encodeURIComponent(q)).then(function(r){return r.json();}); }

  var INP = 'padding:9px 12px; border-radius:8px; border:1px solid #3d4e6e; background:#1a2540; color:#e0e6f0;';
  var CHIP = 'display:inline-flex; align-items:center; gap:6px; background:#1a2540; border:1px solid #2a3a55; border-radius:14px; padding:5px 12px; font-size:13px; color:#dce6f5;';
  function empty(t){ return '<span style="color:#7090b0; font-size:13px;">'+t+'</span>'; }

  /* ───────── 작성기(composer): 종목/키워드 담기 → 한 번에 추가 ───────── */
  var pendKws = [], pendStocks = [];   // pendStocks: {code,name}
  var cName=$('cName'), cStock=$('cStock'), cSug=$('cStockSuggest'), cStChips=$('cStockChips'),
      cKw=$('cKw'), cKwChips=$('cKwChips'), cImpact=$('cImpact'), cPct=$('cPct'), cMsg=$('cMsg');

  function renderPend(){
    cKwChips.innerHTML = pendKws.map(function(k,i){ return '<span style="'+CHIP+'">'+esc(k)+'<b data-rmk="'+i+'" style="cursor:pointer;color:#ff5470;">×</b></span>'; }).join('');
    cStChips.innerHTML = pendStocks.map(function(s,i){ return '<span style="'+CHIP+'">'+esc(s.name||s.code)+' <small style="color:#7090b0;">'+esc(s.code)+'</small><b data-rms="'+i+'" style="cursor:pointer;color:#ff5470;">×</b></span>'; }).join('');
  }
  function hideCSug(){ cSug.style.display='none'; cSug.innerHTML=''; }
  function addPendStock(code, name){
    code = (code||'').trim(); if(!code) return;
    for(var i=0;i<pendStocks.length;i++) if(pendStocks[i].code===code) return;
    pendStocks.push({code:code, name:name||''}); renderPend();
  }
  function addPendKw(k){
    k=(k||'').trim(); if(!k) return;
    if(pendKws.indexOf(k)<0){ pendKws.push(k); renderPend(); }
  }

  cStock.addEventListener('input', function(){
    cStock.removeAttribute('data-code');
    var q=cStock.value.trim(); clearTimeout(cStock._t);
    if(q.length<1){ hideCSug(); return; }
    cStock._t=setTimeout(function(){ search(q).then(function(d){
      var list=d.results||[];
      if(!list.length){ cSug.innerHTML='<div style="padding:10px 12px;color:#7090b0;font-size:13px;">검색 결과 없음</div>'; cSug.style.display='block'; return; }
      cSug.innerHTML=list.map(function(s){ return '<div class="sg-item" data-code="'+esc(s.code)+'" data-name="'+esc(s.name)+'" style="padding:9px 12px;cursor:pointer;border-bottom:1px solid #233650;font-size:13px;color:#dce6f5;">'+esc(s.name)+' <small style="color:#7090b0;">'+esc(s.code)+'</small></div>'; }).join('');
      cSug.style.display='block';
    }).catch(hideCSug); },180);
  });
  cSug.addEventListener('click', function(e){
    var it=e.target.closest('.sg-item'); if(!it) return;
    addPendStock(it.getAttribute('data-code'), it.getAttribute('data-name')); cStock.value=''; hideCSug();
  });
  function cStockCommit(){
    var first=cSug.querySelector('.sg-item');
    if(first){ addPendStock(first.getAttribute('data-code'), first.getAttribute('data-name')); }
    else { var raw=cStock.value.trim(); if(/^\d{6}$/.test(raw)) addPendStock(raw,''); }
    cStock.value=''; hideCSug();
  }
  $('cStockAdd').onclick = cStockCommit;
  cStock.addEventListener('keydown', function(e){ if(e.key==='Enter'){ e.preventDefault(); cStockCommit(); } });
  $('cKwAdd').onclick = function(){ addPendKw(cKw.value); cKw.value=''; };
  cKw.addEventListener('keydown', function(e){ if(e.key==='Enter'){ e.preventDefault(); addPendKw(cKw.value); cKw.value=''; } });
  cStChips.addEventListener('click', function(e){ var i=e.target.getAttribute('data-rms'); if(i!==null){ pendStocks.splice(+i,1); renderPend(); } });
  cKwChips.addEventListener('click', function(e){ var i=e.target.getAttribute('data-rmk'); if(i!==null){ pendKws.splice(+i,1); renderPend(); } });

  $('cCreate').onclick = function(){
    cStockCommit(); // 입력칸에 남은 종목도 반영
    if(!pendKws.length && !pendStocks.length){ cMsg.textContent='종목이나 키워드를 하나 이상 담아주세요.'; return; }
    var usp=new URLSearchParams();
    usp.append('action','createAlert');
    if(cName.value.trim()) usp.append('name', cName.value.trim());
    usp.append('impactMin', cImpact.value);
    usp.append('pricePct', cPct.value);
    pendKws.forEach(function(k){ usp.append('keyword', k); });
    pendStocks.forEach(function(s){ usp.append('code', s.code); });
    postRaw(usp).then(function(res){
      if(res && res.ok===false){ cMsg.textContent=res.error||'추가 실패'; return; }
      if(res && res.setId) expanded[res.setId]=true;
      pendKws=[]; pendStocks=[]; renderPend();
      cName.value=''; cKw.value=''; cStock.value=''; cImpact.value='4'; cPct.value='5'; cMsg.textContent='';
      load();
    });
  };

  /* ───────── 관심 알림 리스트(아코디언) ───────── */
  function kwChip(k){ return '<span style="'+CHIP+'">'+esc(k)+'<b data-delkw="'+esc(k)+'" style="cursor:pointer;color:#ff5470;">×</b></span>'; }
  function stChip(s){ return '<span style="'+CHIP+'">'+esc(s.name||s.code)+' <small style="color:#7090b0;">'+esc(s.code)+'</small><b data-delstock="'+esc(s.code)+'" style="cursor:pointer;color:#ff5470;">×</b></span>'; }
  function summary(s){
    return '키워드 <b style="color:#cdd9ea;">'+(s.keywords||[]).length+'</b> · 종목 <b style="color:#cdd9ea;">'+(s.stocks||[]).length
      + '</b> · 호재악재 ≥<b style="color:#cdd9ea;">'+s.impactMin+'</b> · 급변 ±<b style="color:#cdd9ea;">'+s.pricePct+'%</b>';
  }
  function setRow(s){
    var open = !!expanded[s.id];
    var impactOpts = [2,3,4,5].map(function(v){ return '<option value="'+v+'"'+(v===s.impactMin?' selected':'')+'>'+v+'</option>'; }).join('');
    var kws = (s.keywords||[]).map(kwChip).join('') || empty('관심 키워드가 없습니다.');
    var sts = (s.stocks||[]).map(stChip).join('') || empty('관심 종목이 없습니다.');
    var body =
        '<div data-f="body" style="display:'+(open?'block':'none')+'; padding:4px 18px 18px; border-top:1px solid #233650;">'
      +   '<div style="display:flex; gap:8px; align-items:center; margin:14px 0;">'
      +     '<span style="font-size:12px; color:#8aa0c0;">이름</span>'
      +     '<input data-f="setName" value="'+esc(s.name)+'" style="flex:1; '+INP+'">'
      +     '<button data-act="rename" class="btn">이름저장</button>'
      +     '<button data-act="delSet" class="btn" style="color:#ff5470;">삭제</button>'
      +   '</div>'
      +   '<div style="display:flex; gap:18px; flex-wrap:wrap; align-items:center; background:#16203a; border:1px solid #2a3a55; border-radius:8px; padding:12px; margin-bottom:16px;">'
      +     '<span style="font-size:13px; color:#9fc0ff; font-weight:700;">🔔 알림기준</span>'
      +     '<label style="font-size:13px;color:#c8d8e8;">호재/악재 |점수| ≥ <select data-f="impactMin" style="margin-left:4px; padding:5px 8px; border-radius:6px; background:#1a2540; color:#e0e6f0; border:1px solid #3d4e6e;">'+impactOpts+'</select></label>'
      +     '<label style="font-size:13px;color:#c8d8e8;">급변 ± <input data-f="pricePct" type="number" step="0.5" min="0.5" max="30" value="'+s.pricePct+'" style="width:64px; margin-left:4px; padding:5px 8px; border-radius:6px; background:#1a2540; color:#e0e6f0; border:1px solid #3d4e6e;"> %</label>'
      +     '<button data-act="saveSet" class="btn primary">기준 저장</button>'
      +     '<span data-f="msg" style="font-size:12px; color:#19c37d;"></span>'
      +   '</div>'
      +   '<h4 style="margin:0 0 8px; font-size:14px; color:#dce6f5;">🔑 관심 키워드</h4>'
      +   '<div style="display:flex; gap:8px; margin-bottom:10px;">'
      +     '<input data-f="kwInput" placeholder="키워드 입력 후 추가" style="flex:1; '+INP+'">'
      +     '<button data-act="addKw" class="btn">추가</button>'
      +   '</div>'
      +   '<div style="display:flex; flex-wrap:wrap; gap:8px; margin-bottom:18px;">'+kws+'</div>'
      +   '<h4 style="margin:0 0 8px; font-size:14px; color:#dce6f5;">📈 관심 종목</h4>'
      +   '<div style="position:relative; margin-bottom:10px;">'
      +     '<div style="display:flex; gap:8px;">'
      +       '<input data-f="stQuery" autocomplete="off" placeholder="종목명 또는 코드 검색" style="flex:1; '+INP+'">'
      +       '<button data-act="addStock" class="btn">추가</button>'
      +     '</div>'
      +     '<div data-f="suggest" style="display:none; position:absolute; left:0; right:90px; top:46px; background:#16203a; border:1px solid #2a3a55; border-radius:8px; max-height:240px; overflow-y:auto; z-index:50;"></div>'
      +   '</div>'
      +   '<div style="display:flex; flex-wrap:wrap; gap:8px;">'+sts+'</div>'
      + '</div>';
    var on = s.enabled !== false;
    var enPill = '<button data-act="enToggle" data-on="'+(on?'1':'0')+'" title="이 알림 켜기/끄기" '
      + 'style="flex:0 0 auto; border:1px solid '+(on?'#19c37d':'#3d4e6e')+'; background:'+(on?'rgba(25,195,125,.15)':'#1a2540')
      + '; color:'+(on?'#19c37d':'#7d92b0')+'; font-size:12px; font-weight:700; border-radius:14px; padding:4px 12px; cursor:pointer; white-space:nowrap;">'
      + (on?'🔔 알림 ON':'🔕 알림 OFF') + '</button>';
    return ''
      + '<section class="card" data-set-id="'+s.id+'" style="padding:0; margin-bottom:12px; overflow:visible;'+(on?'':' opacity:.6;')+'">'
      +   '<div data-act="toggle" style="display:flex; align-items:center; gap:12px; padding:15px 18px; cursor:pointer;">'
      +     '<span data-f="chev" style="color:#7d92b0; font-size:13px; width:12px;">'+(open?'▾':'▸')+'</span>'
      +     '<span style="font-size:15px; font-weight:700; color:#e8eef7;">'+esc(s.name)+'</span>'
      +     '<span style="flex:1; text-align:right; font-size:12px; color:#8aa0c0;">'+summary(s)+'</span>'
      +     enPill
      +   '</div>'
      +   body
      + '</section>';
  }

  /* ───────── 카카오톡 알림 연동 ───────── */
  function renderKakao(d){
    var st=$('kakaoStatus'), ac=$('kakaoActions'); if(!st||!ac) return;
    if(!d.kakaoConfigured){ st.textContent='관리자 미설정 — 카카오 연동 비활성화 (KAKAO_REST_API_KEY 필요)'; ac.innerHTML=''; return; }
    if(d.kakaoLinked){
      st.innerHTML='<span style="color:#19c37d;">연동됨 ✓</span> — 새 알림을 카카오톡으로도 받습니다.';
      ac.innerHTML='<button id="kakaoTest" class="btn">테스트 전송</button> <button id="kakaoUnlink" class="btn" style="color:#ff5470;">연동 해제</button>';
    } else {
      st.textContent='미연동 — 카카오톡으로 알림을 받으려면 연동하세요.';
      ac.innerHTML='<a href="'+ctx+'/kakao/login" class="btn primary">카카오톡 연동하기</a>';
    }
  }
  (function(){
    var m=(location.search.match(/[?&]kakao=([^&]+)/)||[])[1]; var box=$('kakaoMsg'); if(!m||!box) return;
    if(m==='ok'){ box.style.color='#19c37d'; box.textContent='카카오톡 연동 완료!'; }
    else if(m==='fail'){ box.style.color='#ff5470'; box.textContent='카카오톡 연동 실패 — 다시 시도해주세요.'; }
    else if(m==='disabled'){ box.style.color='#f5c451'; box.textContent='카카오 연동이 아직 설정되지 않았습니다(관리자).'; }
  })();
  var kakaoCard=$('kakaoCard');
  if(kakaoCard) kakaoCard.addEventListener('click', function(e){
    var box=$('kakaoMsg');
    if(e.target.id==='kakaoTest'){
      e.target.disabled=true; box.style.color='#a0b4d0'; box.textContent='전송 중…';
      fetch(ctx+'/kakao/test',{method:'POST',headers:{'X-CSRF-Token':csrf}}).then(function(r){return r.json();}).then(function(res){
        box.style.color=res.ok?'#19c37d':'#ff5470';
        box.textContent=res.ok?'테스트 메시지를 보냈습니다. 카카오톡을 확인하세요.':(res.error||'전송 실패');
        var b=$('kakaoTest'); if(b) b.disabled=false;
      }).catch(function(){ box.style.color='#ff5470'; box.textContent='전송 실패'; var b=$('kakaoTest'); if(b) b.disabled=false; });
    } else if(e.target.id==='kakaoUnlink'){
      if(!confirm('카카오톡 알림 연동을 해제할까요?')) return;
      fetch(ctx+'/kakao/unlink',{method:'POST',headers:{'X-CSRF-Token':csrf}}).then(function(r){return r.json();}).then(function(){ load(); });
    }
  });

  function load(){
    return fetch(ctx + '/api/my/watchlist').then(function(r){return r.json();}).then(function(d){
      $('who').textContent = (d.name||'') + ' (@' + (d.username||'') + ')';
      renderKakao(d);
      var sets = d.sets || [];
      setList.innerHTML = sets.length ? sets.map(setRow).join('')
        : '<div class="card" style="padding:18px; color:#7090b0;">아직 관심 알림이 없습니다. 위에서 추가하세요.</div>';
    });
  }

  function suggestBox(card){ return card.querySelector('[data-f="suggest"]'); }
  function hideSuggest(card){ var b=suggestBox(card); if(b){ b.style.display='none'; b.innerHTML=''; } }
  function renderSuggest(card, list){
    var b = suggestBox(card); if(!b) return;
    if(!list.length){ b.innerHTML='<div style="padding:10px 12px; color:#7090b0; font-size:13px;">검색 결과 없음</div>'; b.style.display='block'; return; }
    b.innerHTML = list.map(function(s){ return '<div class="sg-item" data-code="'+esc(s.code)+'" data-name="'+esc(s.name)+'" style="padding:9px 12px; cursor:pointer; border-bottom:1px solid #233650; font-size:13px; color:#dce6f5;">'+esc(s.name)+' <small style="color:#7090b0;">'+esc(s.code)+'</small></div>'; }).join('');
    b.style.display='block';
  }
  function addStockFromCard(card, sid){
    var inp = card.querySelector('[data-f="stQuery"]');
    var raw = inp.value.trim(); var code = inp.getAttribute('data-code');
    if(!raw && !code) return;
    var params = code ? {action:'addStock', setId:sid, code:code, name:inp.getAttribute('data-name')||''}
               : (/^\d{6}$/.test(raw) ? {action:'addStock', setId:sid, code:raw} : {action:'addStock', setId:sid, name:raw});
    post(params).then(function(res){ if(res && res.ok===false){ alert(res.error||'추가 실패'); return; } load(); });
  }

  setList.addEventListener('click', function(e){
    var card = e.target.closest('[data-set-id]'); if(!card) return;
    var sid = card.getAttribute('data-set-id');
    var sg = e.target.closest('.sg-item');
    if(sg){ var inp=card.querySelector('[data-f="stQuery"]'); inp.value=sg.getAttribute('data-name')+' ('+sg.getAttribute('data-code')+')'; inp.setAttribute('data-code',sg.getAttribute('data-code')); inp.setAttribute('data-name',sg.getAttribute('data-name')); hideSuggest(card); return; }
    var enBtn = e.target.closest('[data-act="enToggle"]');
    if(enBtn){ post({action:'setEnabled', setId:sid, enabled: enBtn.getAttribute('data-on')==='1'?'N':'Y'}).then(load); return; }
    if(e.target.closest('[data-act="toggle"]')){
      var open = !expanded[sid];
      if(open){ expanded[sid]=true; } else { delete expanded[sid]; }
      card.querySelector('[data-f="body"]').style.display = open?'block':'none';
      var ch=card.querySelector('[data-f="chev"]'); if(ch) ch.textContent = open?'▾':'▸';
      return;
    }
    var act = e.target.getAttribute('data-act');
    if(act==='delSet'){ if(confirm('이 관심 알림을 삭제할까요? (키워드/종목 포함)')) post({action:'deleteSet', setId:sid}).then(function(){ delete expanded[sid]; load(); }); return; }
    if(act==='rename'){ var v=card.querySelector('[data-f="setName"]').value.trim(); if(v) post({action:'renameSet', setId:sid, name:v}).then(load); return; }
    if(act==='saveSet'){ post({action:'settings', setId:sid, impactMin:card.querySelector('[data-f="impactMin"]').value, pricePct:card.querySelector('[data-f="pricePct"]').value}).then(function(){ var m=card.querySelector('[data-f="msg"]'); m.textContent='저장되었습니다.'; setTimeout(function(){ load(); },700); }); return; }
    if(act==='addKw'){ var kv=card.querySelector('[data-f="kwInput"]').value.trim(); if(kv) post({action:'addKeyword', setId:sid, keyword:kv}).then(load); return; }
    if(act==='addStock'){ addStockFromCard(card, sid); return; }
    var dk=e.target.getAttribute('data-delkw'); if(dk!==null){ post({action:'delKeyword', setId:sid, keyword:dk}).then(load); return; }
    var dsx=e.target.getAttribute('data-delstock'); if(dsx!==null){ post({action:'delStock', setId:sid, code:dsx}).then(load); return; }
  });

  setList.addEventListener('input', function(e){
    if(!e.target.matches('[data-f="stQuery"]')) return;
    var card = e.target.closest('[data-set-id]');
    e.target.removeAttribute('data-code');
    var q = e.target.value.trim();
    clearTimeout(card._t);
    if(q.length<1){ hideSuggest(card); return; }
    card._t = setTimeout(function(){ search(q).then(function(d){ renderSuggest(card, d.results||[]); }).catch(function(){ hideSuggest(card); }); }, 180);
  });

  setList.addEventListener('keydown', function(e){
    if(e.key!=='Enter') return;
    var card = e.target.closest('[data-set-id]'); if(!card) return;
    var sid = card.getAttribute('data-set-id');
    if(e.target.matches('[data-f="kwInput"]')){ e.preventDefault(); var v=e.target.value.trim(); if(v) post({action:'addKeyword', setId:sid, keyword:v}).then(load); }
    else if(e.target.matches('[data-f="stQuery"]')){ e.preventDefault(); var first=card.querySelector('.sg-item'); if(first && !e.target.getAttribute('data-code')){ e.target.setAttribute('data-code',first.getAttribute('data-code')); e.target.setAttribute('data-name',first.getAttribute('data-name')); } addStockFromCard(card, sid); }
  });

  document.addEventListener('click', function(e){
    if(!e.target.closest('#cStock') && !e.target.closest('#cStockSuggest')) hideCSug();
    if(e.target.closest('[data-f="stQuery"]') || e.target.closest('[data-f="suggest"]')) return;
    setList.querySelectorAll('[data-set-id]').forEach(hideSuggest);
  });

  load();
})();
