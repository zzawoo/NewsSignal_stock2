package com.newssignal.user;

import com.google.gson.JsonObject;
import com.newssignal.common.AuthUtil;
import com.newssignal.common.CsrfFilter;
import com.newssignal.common.Db;
import com.newssignal.common.PasswordUtil;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/** 회원가입/로그인/로그아웃. /auth/register, /auth/login, /auth/logout (POST, JSON). */
@WebServlet("/auth/*")
public class AuthServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        String path = req.getPathInfo() == null ? "" : req.getPathInfo();
        JsonObject out = new JsonObject();

        if (!CsrfFilter.validate(req)) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            write(resp, fail(out, "보안 토큰이 유효하지 않습니다."));
            return;
        }

        try {
            if ("/register".equals(path)) {
                handleRegister(req, out);
            } else if ("/login".equals(path)) {
                handleLogin(req, out);
            } else if ("/logout".equals(path)) {
                AuthUtil.logout(req);
                out.addProperty("ok", true);
            } else {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                fail(out, "알 수 없는 요청");
            }
        } catch (Exception e) {
            e.printStackTrace();
            fail(out, "처리 중 오류가 발생했습니다.");
        }
        write(resp, out);
    }

    private void handleRegister(HttpServletRequest req, JsonObject out) throws Exception {
        String username = trim(req.getParameter("username"));
        String pw = req.getParameter("password");
        String name = trim(req.getParameter("displayName"));
        String email = trim(req.getParameter("email"));   // 선택(향후 메일 알림용)
        if (!username.matches("[A-Za-z0-9_]{3,20}")) { fail(out, "아이디는 영문/숫자/밑줄 3~20자로 입력하세요."); return; }
        if (pw == null || pw.length() < 6) { fail(out, "비밀번호는 6자 이상이어야 합니다."); return; }
        if (!email.isEmpty() && !email.contains("@")) { fail(out, "이메일 형식이 올바르지 않습니다."); return; }
        if (name.isEmpty()) name = username;

        try (Connection c = Db.conn()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM users WHERE username=?")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) { fail(out, "이미 사용 중인 아이디입니다."); return; }
                }
            }
            long id;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users (username, email, password_hash, display_name) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, username);
                if (email.isEmpty()) ps.setNull(2, java.sql.Types.VARCHAR); else ps.setString(2, email);
                ps.setString(3, PasswordUtil.hash(pw));
                ps.setString(4, name);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); id = rs.getLong(1); }
            }
            AuthUtil.login(req, id, username, name);
            out.addProperty("ok", true);
            out.addProperty("name", name);
        }
    }

    private void handleLogin(HttpServletRequest req, JsonObject out) throws Exception {
        String username = trim(req.getParameter("username"));
        String pw = req.getParameter("password");
        try (Connection c = Db.conn();
             PreparedStatement ps = c.prepareStatement(
                "SELECT id, password_hash, display_name FROM users WHERE username=?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && PasswordUtil.verify(pw, rs.getString("password_hash"))) {
                    long id = rs.getLong("id");
                    String name = rs.getString("display_name");
                    AuthUtil.login(req, id, username, name);
                    out.addProperty("ok", true);
                    out.addProperty("name", name);
                    return;
                }
            }
        }
        fail(out, "아이디 또는 비밀번호가 올바르지 않습니다.");
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }
    private static JsonObject fail(JsonObject o, String msg) { o.addProperty("ok", false); o.addProperty("error", msg); return o; }
    private static void write(HttpServletResponse resp, JsonObject o) throws IOException {
        try (PrintWriter w = resp.getWriter()) { w.print(o.toString()); }
    }
}
