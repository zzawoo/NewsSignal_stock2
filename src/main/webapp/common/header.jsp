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
  <link rel="stylesheet" href="<%=request.getContextPath()%>/resources/css/app.css">
</head>
<body data-ctx="<%=request.getContextPath()%>">
<header class="topbar">
  <div class="brand"><span class="pulse"></span> NewsSignal AI
    <small>국내 증시 뉴스 시그널</small></div>
  <nav>
    <a href="<%=request.getContextPath()%>/user/dashboard.jsp">대시보드</a>
    <a href="<%=request.getContextPath()%>/admin/settings.jsp">관리자</a>
  </nav>
</header>
