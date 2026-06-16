package com.newssignal.user;

import com.newssignal.collector.AnalyzeJob;
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
        // "분석·요약 실행" 버튼: 수집은 자동(10분 스케줄)으로 적재되고,
        //  이 버튼은 **이미 적재된 미분석 뉴스**를 분석/요약만 수행한다(수집 안 함).
        //  - 응답 지연 방지를 위해 백그라운드 스레드로 위임하고 클라이언트는 상태를 폴링한다.
        //  - AnalyzeJob의 isRunning 가드로 중복 실행은 스킵된다.
        new Thread(new AnalyzeJob(), "manual-analyze").start();

        resp.setContentType("application/json; charset=UTF-8");
        try (PrintWriter out = resp.getWriter()) {
            out.print("{\"status\":\"started\",\"mode\":\"analyze\"}");
        }
    }
}
