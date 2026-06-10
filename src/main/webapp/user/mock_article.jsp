<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.sql.*" %>
<%@ page import="com.newssignal.common.Db" %>
<%@ include file="/common/header.jsp" %>

<%
  String idParam = request.getParameter("id");
  String title = "기사를 찾을 수 없습니다.";
  String description = "해당 기사가 존재하지 않거나 데이터베이스 조회 중 오류가 발생했습니다.";
  String press = "시스템 알림";
  String pubDateStr = "";
  String originalLink = "";

  if (idParam != null && !idParam.trim().isEmpty()) {
      try {
          long id = Long.parseLong(idParam);
          try (Connection conn = Db.conn();
               PreparedStatement ps = conn.prepareStatement(
                       "SELECT title, description, press, pub_date, original_link FROM news_articles WHERE id = ?")) {
              ps.setLong(1, id);
              try (ResultSet rs = ps.executeQuery()) {
                  if (rs.next()) {
                      title = rs.getString("title");
                      description = rs.getString("description");
                      press = rs.getString("press");
                      Timestamp pubDate = rs.getTimestamp("pub_date");
                      if (pubDate != null) {
                          pubDateStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(pubDate);
                      }
                      originalLink = rs.getString("original_link");
                  }
              }
          }
      } catch (Exception e) {
          e.printStackTrace();
      }
  }
%>

<main class="wrap">
  <div class="card" style="padding: 30px; max-width: 800px; margin: 20px auto; border-radius: 16px; border: 1px solid var(--line); background: var(--panel);">
    <div style="margin-bottom: 24px; border-bottom: 1px solid var(--line); padding-bottom: 20px;">
      <span class="sig neutral" style="font-size: 11px; padding: 4px 10px; font-weight: 600; border-radius: 6px; color: var(--neu); background: #1c2333; display: inline-block; margin-bottom: 12px;">
        <c:out value="<%=press%>"/>
      </span>
      <h1 style="font-size: 22px; font-weight: 600; line-height: 1.5; color: var(--ink); margin-top: 4px; letter-spacing: -0.02em;">
        <c:out value="<%=title%>"/>
      </h1>
      <div style="font-size: 12px; color: var(--ink3); margin-top: 14px;">
        발행시간: <c:out value="<%=pubDateStr%>"/>
      </div>
    </div>
    
    <div style="font-size: 15px; line-height: 1.8; color: var(--ink2); white-space: pre-wrap; margin-bottom: 35px; min-height: 200px; word-break: break-all;">
      <c:out value="<%=description%>"/>
    </div>

    <div style="border-top: 1px solid var(--line); padding-top: 24px; text-align: center; display: flex; justify-content: center; gap: 12px;">
      <a href="<%=request.getContextPath()%>/user/dashboard.jsp" class="btn" style="min-width: 140px; text-align: center;">대시보드로 이동</a>
      <% if (originalLink != null && !originalLink.isEmpty()) { %>
        <a href="<c:out value='<%=originalLink%>'/>" target="_blank" class="btn primary" style="min-width: 160px; text-align: center;">원본 기사 링크 (모의)</a>
      <% } %>
    </div>
  </div>
</main>

<%@ include file="/common/footer.jsp" %>
