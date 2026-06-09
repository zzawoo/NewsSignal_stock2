<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isErrorPage="true" %>
<!DOCTYPE html>
<html lang="ko"><head><meta charset="UTF-8">
<title>오류</title>
<link rel="stylesheet" href="<%=request.getContextPath()%>/resources/css/app.css">
</head>
<body>
<main class="wrap" style="text-align:center;padding-top:80px">
  <h2>요청을 처리하지 못했습니다</h2>
  <p class="hint">잠시 후 다시 시도해 주세요. 문제가 계속되면 관리자에게 문의하세요.</p>
  <p><a class="btn" href="<%=request.getContextPath()%>/user/dashboard.jsp">대시보드로 돌아가기</a></p>
</main>
</body></html>
