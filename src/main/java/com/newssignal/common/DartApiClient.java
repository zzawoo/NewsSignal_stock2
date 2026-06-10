package com.newssignal.common;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class DartApiClient {

    private static final String API_KEY = System.getenv("DART_API_KEY");
    private static final Map<String, String> stockToCorpMap = new HashMap<>();
    private static boolean initialized = false;

    private static synchronized void initMapping() {
        if (initialized) return;
        if (API_KEY == null || API_KEY.trim().isEmpty()) {
            System.err.println("[DART] API_KEY is not set.");
            return;
        }

        System.out.println("[DART] Downloading corpCode.xml.zip...");
        String urlStr = "https://opendart.fss.or.kr/api/corpCode.xml?crtfc_key=" + API_KEY;
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);

            try (ZipInputStream zis = new ZipInputStream(conn.getInputStream())) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.getName().endsWith(".xml")) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(zis, "UTF-8"));
                        String line;
                        String corpCode = null;
                        String stockCode = null;

                        while ((line = reader.readLine()) != null) {
                            if (line.contains("<corp_code>")) {
                                corpCode = extractText(line, "<corp_code>", "</corp_code>");
                            } else if (line.contains("<stock_code>")) {
                                stockCode = extractText(line, "<stock_code>", "</stock_code>");
                            } else if (line.contains("</list>")) {
                                if (corpCode != null && stockCode != null && stockCode.trim().length() == 6) {
                                    stockToCorpMap.put(stockCode.trim(), corpCode.trim());
                                }
                                corpCode = null;
                                stockCode = null;
                            }
                        }
                    }
                }
            }
            initialized = true;
            System.out.println("[DART] Mapping loaded. Count: " + stockToCorpMap.size());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String extractText(String line, String startTag, String endTag) {
        int start = line.indexOf(startTag);
        int end = line.indexOf(endTag);
        if (start != -1 && end != -1 && end > start) {
            return line.substring(start + startTag.length(), end);
        }
        return null;
    }

    public static JsonObject getCompanyInfo(String stockCode) {
        if (!initialized) {
            initMapping();
        }
        String corpCode = stockToCorpMap.get(stockCode);
        if (corpCode == null) {
            return null;
        }

        String urlStr = "https://opendart.fss.or.kr/api/company.json?crtfc_key=" + API_KEY + "&corp_code=" + corpCode;
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            try (InputStreamReader isr = new InputStreamReader(conn.getInputStream(), "UTF-8")) {
                Gson gson = new Gson();
                JsonObject response = gson.fromJson(isr, JsonObject.class);
                if (response.has("status") && "000".equals(response.get("status").getAsString())) {
                    return response;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static JsonObject getFinanceInfo(String stockCode) {
        if (!initialized) {
            initMapping();
        }
        String corpCode = stockToCorpMap.get(stockCode);
        if (corpCode == null) {
            return null;
        }

        // 최근 사업연도 동적 설정: 현재 월이 4월 이전이면 전전년도, 이후면 전년도 사용
        int currentYear = java.time.LocalDate.now().getYear();
        int currentMonth = java.time.LocalDate.now().getMonthValue();
        int bsnsYear = (currentMonth < 4) ? currentYear - 2 : currentYear - 1;

        String urlStr = "https://opendart.fss.or.kr/api/fnlttSinglAcnt.json?crtfc_key=" + API_KEY 
                      + "&corp_code=" + corpCode + "&bsns_year=" + bsnsYear + "&reprt_code=11011";
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            try (InputStreamReader isr = new InputStreamReader(conn.getInputStream(), "UTF-8")) {
                Gson gson = new Gson();
                JsonObject response = gson.fromJson(isr, JsonObject.class);
                if (response.has("status") && "000".equals(response.get("status").getAsString())) {
                    return response;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
