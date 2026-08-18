<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<%-- 공통 헤더: 모든 화면이 include. CSRF 토큰을 meta로 노출(스크립트가 헤더에 사용) --%>
<!DOCTYPE html>
<html lang="ko">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <meta name="csrf-token" content="<c:out value='${sessionScope.csrfToken}'/>">
  <title>NewsSignal AI</title>
  <link rel="stylesheet" href="<%=request.getContextPath()%>/resources/css/app.css?v=<%=System.currentTimeMillis()%>">
</head>
<body data-ctx="<%=request.getContextPath()%>" data-logged="${not empty sessionScope.userId}">
<header class="topbar">
  <div class="brand"><span class="pulse"></span> NewsSignal AI
    <small>국내 증시 뉴스 시그널</small></div>
  <nav style="display:flex; align-items:center; gap:14px;">
    <a href="<%=request.getContextPath()%>/user/dashboard.jsp">대시보드</a>
    <a href="<%=request.getContextPath()%>/admin/settings.jsp">관리자</a>
    <c:choose>
      <c:when test="${not empty sessionScope.userId}">
        <span id="notifBell" style="position:relative; cursor:pointer; font-size:18px;">🔔<span id="notifBadge" style="display:none; position:absolute; top:-6px; right:-8px; background:#ff5470; color:#fff; font-size:10px; font-weight:700; padding:1px 5px; border-radius:10px;">0</span>
          <div id="notifPanel" style="display:none; position:absolute; top:28px; right:0; width:320px; max-height:380px; overflow-y:auto; background:#16203a; border:1px solid #2a3a55; border-radius:8px; z-index:1000; text-align:left;"></div>
        </span>
        <a href="<%=request.getContextPath()%>/my/watchlist.jsp">마이페이지</a>
        <a href="#" id="logoutBtn">로그아웃<small style="color:#7090b0;"> (<c:out value="${sessionScope.userName}"/>)</small></a>
      </c:when>
      <c:otherwise>
        <a href="<%=request.getContextPath()%>/login.jsp">로그인</a>
      </c:otherwise>
    </c:choose>
  </nav>
</header>
<c:if test="${not empty sessionScope.userId}">
  <script src="<%=request.getContextPath()%>/resources/js/notif.js?v=<%=System.currentTimeMillis()%>"></script>
</c:if>
