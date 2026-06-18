package com.newssignal.user;

import com.google.gson.JsonObject;
import com.newssignal.common.MacroDataClient;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

@WebServlet("/api/macro/prices")
public class MacroInfoServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");

        JsonObject result = new JsonObject();
        try {
            if ("sparkline".equals(req.getParameter("type"))) {
                result.add("sparklines", MacroDataClient.getSparklines());
            } else {
                result.add("prices", MacroDataClient.getMacroPrices());
            }
            result.addProperty("status", "success");
        } catch (Exception e) {
            e.printStackTrace();
            result.addProperty("status", "error");
            result.addProperty("message", e.getMessage());
        }

        PrintWriter out = resp.getWriter();
        out.print(result.toString());
        out.flush();
    }
}
