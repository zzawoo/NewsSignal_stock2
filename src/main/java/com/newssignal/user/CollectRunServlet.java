package com.newssignal.user;

import com.newssignal.collector.CollectJob;
import com.newssignal.common.CsrfFilter;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * 수동 "수집·분석 실행" 트리거 (계획서: 수동 버튼 방식).
 * - 상태변경이므로 POST + CSRF 토큰 검증 (계획서 9장).
 * - 동기 실행은 응답 지연을 부르므로 별도 스레드로 위임하고 즉시 응답.
 */
@WebServlet("/collect/run")
public class CollectRunServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        // CSRF 검증
        if (!CsrfFilter.validate(req)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "CSRF token mismatch");
            return;
        }
        // 비동기 실행 (간단한 데모용 스레드; 운영은 ExecutorService 권장)
        new Thread(new CollectJob(), "manual-collect").start();

        resp.setContentType("application/json; charset=UTF-8");
        try (PrintWriter out = resp.getWriter()) {
            out.print("{\"status\":\"started\",\"mode\":\"manual\"}");
        }
    }
}
