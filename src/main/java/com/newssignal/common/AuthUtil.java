package com.newssignal.common;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/** 세션 기반 로그인 상태 헬퍼. */
public final class AuthUtil {

    public static final String UID = "userId";
    public static final String ULOGIN = "userLogin";   // 로그인 아이디(username)
    public static final String UNAME = "userName";      // 표시 이름(display_name)

    public static Long userId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null) return null;
        Object v = s.getAttribute(UID);
        return (v instanceof Long) ? (Long) v : null;
    }

    public static void login(HttpServletRequest req, long id, String login, String name) {
        HttpSession s = req.getSession(true);
        s.setAttribute(UID, id);
        s.setAttribute(ULOGIN, login);
        s.setAttribute(UNAME, name);
    }

    public static void logout(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s != null) {
            s.removeAttribute(UID);
            s.removeAttribute(ULOGIN);
            s.removeAttribute(UNAME);
        }
    }

    private AuthUtil() {}
}
