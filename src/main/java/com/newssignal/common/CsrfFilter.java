package com.newssignal.common;

import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * CSRF 토큰 필터 (계획서 9장).
 * - 세션에 토큰을 발급/보관.
 * - 상태변경(POST/PUT/DELETE) 요청 시 validate()로 검증(서블릿에서 호출).
 * - 토큰은 세션 속성 "csrfToken"으로 JSP에서 hidden/헤더에 주입.
 */
@WebFilter("/*")
public class CsrfFilter implements Filter {

    public static final String TOKEN_ATTR = "csrfToken";
    public static final String HEADER = "X-CSRF-Token";
    private static final SecureRandom RNG = new SecureRandom();

    @Override
    public void doFilter(ServletRequest sreq, ServletResponse sresp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) sreq;
        HttpSession session = req.getSession(true);
        if (session.getAttribute(TOKEN_ATTR) == null) {
            session.setAttribute(TOKEN_ATTR, newToken());
        }
        chain.doFilter(sreq, sresp);
    }

    private static String newToken() {
        byte[] b = new byte[24];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** 상태변경 요청에서 서블릿이 호출하는 검증 메서드 */
    public static boolean validate(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return false;
        Object expected = session.getAttribute(TOKEN_ATTR);
        if (expected == null) return false;
        String got = req.getHeader(HEADER);
        if (got == null) got = req.getParameter("_csrf");
        return expected.equals(got);
    }

    @Override public void init(FilterConfig f) {}
    @Override public void destroy() {}
}
