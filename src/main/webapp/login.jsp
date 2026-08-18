<%@ include file="/common/header.jsp" %>
<main class="wrap" style="max-width:460px; margin:40px auto;">
  <section class="card" style="padding:28px;">
    <div style="display:flex; gap:8px; margin-bottom:20px;">
      <button id="tabLogin"  class="btn" style="flex:1;">로그인</button>
      <button id="tabSignup" class="btn" style="flex:1;">회원가입</button>
    </div>

    <form id="loginForm">
      <h2 style="margin:0 0 14px;">로그인</h2>
      <input name="username" type="text" placeholder="아이디" required autocomplete="username"
             style="width:100%; padding:11px 14px; margin-bottom:10px; border-radius:8px; border:1px solid #3d4e6e; background:#1a2540; color:#e0e6f0; box-sizing:border-box;">
      <input name="password" type="password" placeholder="비밀번호" required autocomplete="current-password"
             style="width:100%; padding:11px 14px; margin-bottom:14px; border-radius:8px; border:1px solid #3d4e6e; background:#1a2540; color:#e0e6f0; box-sizing:border-box;">
      <button class="btn primary" type="submit" style="width:100%;">로그인</button>
    </form>

    <form id="signupForm" style="display:none;">
      <h2 style="margin:0 0 14px;">회원가입</h2>
      <input name="username" type="text" placeholder="아이디 (영문/숫자/밑줄 3~20자)" required pattern="[A-Za-z0-9_]{3,20}" autocomplete="username"
             style="width:100%; padding:11px 14px; margin-bottom:10px; border-radius:8px; border:1px solid #3d4e6e; background:#1a2540; color:#e0e6f0; box-sizing:border-box;">
      <input name="displayName" type="text" placeholder="표시 이름 (선택)"
             style="width:100%; padding:11px 14px; margin-bottom:10px; border-radius:8px; border:1px solid #3d4e6e; background:#1a2540; color:#e0e6f0; box-sizing:border-box;">
      <input name="email" type="email" placeholder="이메일 (선택 — 메일 알림용)"
             style="width:100%; padding:11px 14px; margin-bottom:10px; border-radius:8px; border:1px solid #3d4e6e; background:#1a2540; color:#e0e6f0; box-sizing:border-box;">
      <input name="password" type="password" placeholder="비밀번호 (6자 이상)" required minlength="6" autocomplete="new-password"
             style="width:100%; padding:11px 14px; margin-bottom:14px; border-radius:8px; border:1px solid #3d4e6e; background:#1a2540; color:#e0e6f0; box-sizing:border-box;">
      <button class="btn primary" type="submit" style="width:100%;">가입하기</button>
    </form>

    <div id="authMsg" style="margin-top:12px; font-size:13px; min-height:18px;"></div>
  </section>
</main>

<script src="<%=request.getContextPath()%>/resources/js/auth.js?v=<%=System.currentTimeMillis()%>"></script>
<%@ include file="/common/footer.jsp" %>
