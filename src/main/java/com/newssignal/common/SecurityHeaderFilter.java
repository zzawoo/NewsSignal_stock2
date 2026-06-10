package com.newssignal.common;

import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * UTF-8 인코딩 + 보안 응답 헤더 필터 (계획서 9장).
 * - 요청/응답 UTF-8 강제.
 * - X-Frame-Options, X-Content-Type-Options, Referrer-Policy, 기본 CSP.
 */
@WebFilter("/*")
public class SecurityHeaderFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        req.setCharacterEncoding("UTF-8");
        resp.setCharacterEncoding("UTF-8");

        HttpServletResponse http = (HttpServletResponse) resp;
        http.setHeader("X-Frame-Options", "DENY");
        http.setHeader("X-Content-Type-Options", "nosniff");
        http.setHeader("Referrer-Policy", "same-origin");
        http.setHeader("Content-Security-Policy",
                "default-src 'self'; img-src 'self' data:; "
              + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
              + "font-src https://fonts.gstatic.com; script-src 'self'");
        
        // Disable browser caching to prevent stale CSRF tokens and stale JS files
        http.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        http.setHeader("Pragma", "no-cache");
        http.setDateHeader("Expires", 0);
 
        chain.doFilter(req, resp);
    }

    @Override public void init(FilterConfig f) {}
    @Override public void destroy() {}
}
