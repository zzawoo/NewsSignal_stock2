package com.newssignal.user;

import com.newssignal.collector.AnalyzeJob;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * 수집 상태를 반환하는 서블릿 (Polling 용도).
 */
@WebServlet("/api/collect/status")
public class CollectStatusServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        // "분석·요약 실행" 버튼은 분석만 수행하므로 분석 실행 여부만 반영
        boolean isRunning = AnalyzeJob.isRunning.get();
        try (PrintWriter out = resp.getWriter()) {
            out.print("{\"running\":" + isRunning + "}");
        }
    }
}
