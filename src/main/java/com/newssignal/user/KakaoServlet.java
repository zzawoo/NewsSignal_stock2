package com.newssignal.user;

import com.google.gson.JsonObject;
import com.newssignal.common.AuthUtil;
import com.newssignal.common.CsrfFilter;
import com.newssignal.common.KakaoClient;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * 카카오톡 '나에게 보내기' 연동 흐름.
 *  GET  /kakao/login    → 카카오 인가 페이지로 redirect
 *  GET  /kakao/callback → 인가코드 수신 → 토큰 교환·저장 → 마이페이지로 복귀
 *  POST /kakao/unlink   → 연동 해제 (CSRF)
 *  POST /kakao/test     → 테스트 메시지 1건 전송 (CSRF)
 */
@WebServlet("/kakao/*")
public class KakaoServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo() == null ? "" : req.getPathInfo();
        Long uid = AuthUtil.userId(req);
        String ctx = req.getContextPath();
        if (uid == null) { resp.sendRedirect(ctx + "/login.jsp"); return; }
        if (!KakaoClient.isConfigured()) { resp.sendRedirect(ctx + "/my/watchlist.jsp?kakao=disabled"); return; }

        if ("/login".equals(path)) {
            resp.sendRedirect(KakaoClient.authorizeUrl(KakaoClient.redirectUri()));
            return;
        }
        if ("/callback".equals(path)) {
            String code = req.getParameter("code");
            if (code == null || code.trim().isEmpty()) { resp.sendRedirect(ctx + "/my/watchlist.jsp?kakao=fail"); return; }
            boolean ok = KakaoClient.exchangeAndSave(uid, code.trim(), KakaoClient.redirectUri());
            resp.sendRedirect(ctx + "/my/watchlist.jsp?kakao=" + (ok ? "ok" : "fail"));
            return;
        }
        resp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        JsonObject out = new JsonObject();
        Long uid = AuthUtil.userId(req);
        if (uid == null) { resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED); out.addProperty("ok", false); write(resp, out); return; }
        if (!CsrfFilter.validate(req)) { resp.setStatus(HttpServletResponse.SC_FORBIDDEN); out.addProperty("ok", false); write(resp, out); return; }

        String path = req.getPathInfo() == null ? "" : req.getPathInfo();
        if ("/unlink".equals(path)) {
            KakaoClient.unlink(uid);
            out.addProperty("ok", true);
        } else if ("/test".equals(path)) {
            boolean ok = KakaoClient.sendToMe(uid, "[NewsSignal] 카카오톡 알림 연동 테스트입니다. 이렇게 알림을 받게 됩니다.",
                    KakaoClient.appBaseUrl() + "/user/dashboard.jsp");
            out.addProperty("ok", ok);
            if (!ok) out.addProperty("error", "전송 실패 — 연동 상태/권한을 확인하세요.");
        } else {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND); out.addProperty("ok", false);
        }
        write(resp, out);
    }

    private static void write(HttpServletResponse resp, JsonObject o) throws IOException {
        try (PrintWriter w = resp.getWriter()) { w.print(o.toString()); }
    }
}
