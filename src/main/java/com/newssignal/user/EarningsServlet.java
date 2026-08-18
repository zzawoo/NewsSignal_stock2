package com.newssignal.user;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 실적발표 피드: DART 공정공시(pblntf_ty=I) 중 "영업(잠정)실적" 공시를 최근순으로 모아 날짜별로 묶는다.
 * (미래 예정일정이 아니라 실제 실적발표 발생 — 안정적 무료 소스). 30분 캐시.
 *   /api/earnings → { generatedAt, days:[ {date, items:[{name,code,report,rm}]} ] }
 */
@WebServlet("/api/earnings")
public class EarningsServlet extends HttpServlet {

    private static volatile JsonObject cache;
    private static volatile long cacheTs;
    private static final long TTL_MS = 30 * 60 * 1000L;
    private static final Object LOCK = new Object();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        long now = System.currentTimeMillis();
        if (cache == null || now - cacheTs >= TTL_MS) {
            synchronized (LOCK) {
                if (cache == null || System.currentTimeMillis() - cacheTs >= TTL_MS) {
                    JsonObject c = compute();
                    if (c != null) { cache = c; cacheTs = System.currentTimeMillis(); }
                }
            }
        }
        JsonObject out = cache != null ? cache : new JsonObject();
        try (PrintWriter w = resp.getWriter()) { w.print(out.toString()); }
    }

    private JsonObject compute() {
        String key = System.getenv("DART_API_KEY");
        JsonObject out = new JsonObject();
        JsonArray days = new JsonArray();
        if (key == null || key.trim().isEmpty()) { out.add("days", days); return out; }

        // 최근 10일 범위의 공정공시(I)에서 잠정실적만 필터
        java.util.Calendar cal = java.util.Calendar.getInstance();
        String end = ymd(cal);
        cal.add(java.util.Calendar.DAY_OF_MONTH, -10);
        String bgn = ymd(cal);

        // date -> list of item (dedup by code+date)
        Map<String, Map<String, JsonObject>> byDate = new LinkedHashMap<String, Map<String, JsonObject>>();
        // 실적공시예고 filings (rcNo, name, code, filingDate) → 문서에서 공시예정일 추출
        List<String[]> preAnnounce = new ArrayList<String[]>();
        for (int page = 1; page <= 5; page++) {
            String url = "https://opendart.fss.or.kr/api/list.json?crtfc_key=" + key.trim()
                    + "&bgn_de=" + bgn + "&end_de=" + end + "&pblntf_ty=I&page_no=" + page + "&page_count=100";
            JsonObject resp = fetchJson(url);
            if (resp == null || !"000".equals(str(resp, "status"))) break;
            if (!resp.has("list") || !resp.get("list").isJsonArray()) break;
            JsonArray list = resp.getAsJsonArray("list");
            for (int i = 0; i < list.size(); i++) {
                JsonObject r = list.get(i).getAsJsonObject();
                String report = str(r, "report_nm").trim();
                String code = str(r, "stock_code").trim();
                if (code.isEmpty() || code.length() != 6) continue;       // 상장 종목만
                String date = str(r, "rcept_dt").trim();
                if (date.length() != 8) continue;
                String name = str(r, "corp_name").trim();

                if (report.contains("실적공시예고")) {                    // 예정: 결산실적공시예고
                    preAnnounce.add(new String[]{ str(r, "rcept_no").trim(), name, code, date });
                    continue;
                }
                if (!(report.contains("잠정") && report.contains("실적"))) continue; // 지난: 영업(잠정)실적
                Map<String, JsonObject> m = byDate.get(date);
                if (m == null) { m = new LinkedHashMap<String, JsonObject>(); byDate.put(date, m); }
                if (m.containsKey(code)) continue;
                JsonObject item = new JsonObject();
                item.addProperty("name", name);
                item.addProperty("code", code);
                item.addProperty("report", cleanReport(report));
                item.addProperty("rm", str(r, "rm").trim());
                m.put(code, item);
            }
            int totalPage = intOf(resp, "total_page");
            if (page >= totalPage) break;
        }

        // 예정 실적: 공시예고 문서에서 공시예정일 추출 → 오늘 이후만, 예정일 오름차순
        out.add("upcoming", buildUpcoming(preAnnounce, key.trim(), end));

        // 날짜 내림차순 정렬
        List<String> dates = new ArrayList<String>(byDate.keySet());
        java.util.Collections.sort(dates, java.util.Collections.reverseOrder());
        for (String d : dates) {
            JsonObject dayObj = new JsonObject();
            dayObj.addProperty("date", fmtDate(d));
            dayObj.addProperty("raw", d);
            JsonArray items = new JsonArray();
            for (JsonObject it : byDate.get(d).values()) items.add(it);
            dayObj.addProperty("count", items.size());
            dayObj.add("items", items);
            days.add(dayObj);
        }
        out.addProperty("generatedAt", new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date()));
        out.add("days", days);
        return out;
    }

    /** 공시예고 문서에서 공시예정일을 뽑아 오늘 이후만 날짜별로 묶는다. */
    private JsonArray buildUpcoming(List<String[]> pre, String key, String today) {
        final int CAP = 60;
        Map<String, Map<String, JsonObject>> byDate = new java.util.TreeMap<String, Map<String, JsonObject>>();
        int done = 0;
        for (String[] f : pre) {
            if (done >= CAP) break;
            done++;
            String planned = extractPlannedDate(f[0], key, f[3]); // rcept_no, key, filingDate
            if (planned == null || planned.compareTo(today) < 0) continue; // 오늘 이후만
            String code = f[2], name = f[1];
            Map<String, JsonObject> m = byDate.get(planned);
            if (m == null) { m = new LinkedHashMap<String, JsonObject>(); byDate.put(planned, m); }
            if (m.containsKey(code)) continue;
            JsonObject it = new JsonObject();
            it.addProperty("name", name);
            it.addProperty("code", code);
            m.put(code, it);
        }
        JsonArray out = new JsonArray();
        for (Map.Entry<String, Map<String, JsonObject>> e : byDate.entrySet()) { // TreeMap → 예정일 오름차순
            JsonObject dayObj = new JsonObject();
            dayObj.addProperty("date", fmtDate(e.getKey()));
            dayObj.addProperty("raw", e.getKey());
            JsonArray items = new JsonArray();
            for (JsonObject it : e.getValue().values()) items.add(it);
            dayObj.addProperty("count", items.size());
            dayObj.add("items", items);
            out.add(dayObj);
        }
        return out;
    }

    /** 공시예고 문서(document.xml=zip)에서 '제출일 이후 가장 이른 날짜'(=공시예정일)를 yyyyMMdd로 반환. */
    private String extractPlannedDate(String rcNo, String key, String filingDate) {
        if (rcNo == null || rcNo.isEmpty()) return null;
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://opendart.fss.or.kr/api/document.xml?crtfc_key=" + key + "&rcept_no=" + rcNo);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            if (conn.getResponseCode() != 200) return null;
            // 응답은 zip. 첫 엔트리 내용을 ISO-8859-1로 읽음(날짜는 ASCII라 인코딩 무관).
            java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(conn.getInputStream());
            if (zis.getNextEntry() == null) { zis.close(); return null; }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
            zis.close();
            String text = new String(bos.toByteArray(), "ISO-8859-1");
            // 모든 ISO 날짜 중 제출일 이후 가장 이른 것 = 공시예정일
            java.util.regex.Matcher mt = java.util.regex.Pattern.compile("(20\\d{2})-(\\d{2})-(\\d{2})").matcher(text);
            String best = null;
            while (mt.find()) {
                String ymd = mt.group(1) + mt.group(2) + mt.group(3);
                if (ymd.compareTo(filingDate) >= 0 && (best == null || ymd.compareTo(best) < 0)) best = ymd;
            }
            return best;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String cleanReport(String s) {
        // "연결재무제표기준영업(잠정)실적(공정공시)" → "영업(잠정)실적"
        if (s.contains("영업(잠정)실적")) return "영업(잠정)실적";
        return s.replace("(공정공시)", "").trim();
    }
    private static String fmtDate(String yyyymmdd) {
        if (yyyymmdd.length() != 8) return yyyymmdd;
        return yyyymmdd.substring(0, 4) + "-" + yyyymmdd.substring(4, 6) + "-" + yyyymmdd.substring(6, 8);
    }
    private static String ymd(java.util.Calendar c) {
        return new java.text.SimpleDateFormat("yyyyMMdd").format(c.getTime());
    }

    private JsonObject fetchJson(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = br.readLine()) != null) sb.append(line);
            }
            return JsonParser.parseString(sb.toString()).getAsJsonObject();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String str(JsonObject o, String k) {
        try { return (o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : ""; }
        catch (Exception e) { return ""; }
    }
    private static int intOf(JsonObject o, String k) {
        try { return (o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsInt() : 0; }
        catch (Exception e) { return 0; }
    }
}
