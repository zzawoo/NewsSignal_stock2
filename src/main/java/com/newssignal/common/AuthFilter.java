package com.newssignal.common;

import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/** 로그인 필요 영역 보호: /my/*(페이지) → 로그인 페이지로, /api/my/*(API) → 401. */
@WebFilter(urlPatterns = {"/my/*", "/api/my/*"})
public class AuthFilter implements Filter {

    @Override
    public void doFilter(ServletRequest sreq, ServletResponse sresp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) sreq;
        HttpServletResponse resp = (HttpServletResponse) sresp;
        if (AuthUtil.userId(req) == null) {
            if (req.getRequestURI().contains("/api/")) {
                resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "login required");
            } else {
                resp.sendRedirect(req.getContextPath() + "/login.jsp");
            }
            return;
        }
        chain.doFilter(sreq, sresp);
    }

    @Override public void init(FilterConfig f) {}
    @Override public void destroy() {}
}
