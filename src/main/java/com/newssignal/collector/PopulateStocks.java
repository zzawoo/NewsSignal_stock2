package com.newssignal.collector;

import com.newssignal.common.Db;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * stock_master 초기 적재 유틸. KIND(kind.krx.co.kr) corpList 다운로드를
 * marketType 필터로 KOSPI/KOSDAQ를 각각 받아 market 컬럼을 정확히 태깅한다.
 * 단일 통합 목록(searchType=13)은 시장 구분이 없어 'KOSPI/KOSDAQ' 자리표시자가
 * 박혀버리므로 시장별 2-pass 로 받는다. 기존 행의 잘못된 market 도
 * ON DUPLICATE KEY UPDATE 로 함께 교정한다.
 */
public class PopulateStocks {

    // KIND corpList 다운로드: marketType=stockMkt(KOSPI) / kosdaqMkt(KOSDAQ) / konexMkt(KONEX).
    // HTTPS 직접 호출 — HTTP는 302로 HTTPS 리다이렉트되는데 URL.openStream()은
    // 프로토콜이 바뀌는 리다이렉트를 따라가지 않아 빈 응답을 받는다.
    private static final String BASE_URL =
            "https://kind.krx.co.kr/corpgeneral/corpList.do?method=download&searchType=13&marketType=";

    private static final String UPSERT_SQL =
            "INSERT INTO stock_master (stock_code, stock_name, market, industry) VALUES (?, ?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE stock_name = VALUES(stock_name), " +
            "market = VALUES(market), industry = VALUES(industry)";

    public static void main(String[] args) throws Exception {
        try (Connection c = Db.conn();
             PreparedStatement ps = c.prepareStatement(UPSERT_SQL)) {
            int total = 0;
            total += load(ps, "KOSPI", "stockMkt");
            total += load(ps, "KOSDAQ", "kosdaqMkt");
            total += load(ps, "KONEX", "konexMkt");
            System.out.println("Successfully upserted " + total + " stocks.");
        }
    }

    private static int load(PreparedStatement ps, String market, String marketType) throws Exception {
        System.out.println("Downloading " + market + " list with EUC-KR...");
        Document doc = Jsoup.parse(
                new URL(BASE_URL + marketType).openStream(), "EUC-KR", "");
        Elements rows = doc.select("tr");
        System.out.println("Downloaded " + rows.size() + " " + market + " rows. Inserting...");

        // 컬럼 순서: 0=회사명, 1=시장구분, 2=종목코드, 3=업종 (KIND corpList 다운로드 포맷)
        int count = 0;
        for (Element row : rows) {
            Elements cols = row.select("td");
            if (cols.size() > 2) {
                String name = cols.get(0).text().trim();
                String code = cols.get(2).text().trim();
                String industry = cols.size() > 3 ? cols.get(3).text().trim() : null;
                if (code.matches("^[0-9]+$") && !code.isEmpty()) {
                    if (code.length() < 6) {
                        code = String.format("%06d", Integer.parseInt(code));
                    }
                    ps.setString(1, code);
                    ps.setString(2, name);
                    ps.setString(3, market);
                    ps.setString(4, industry);
                    ps.addBatch();
                    count++;
                    if (count % 500 == 0) ps.executeBatch();
                }
            }
        }
        ps.executeBatch();
        System.out.println("Inserted " + count + " " + market + " stocks.");
        return count;
    }
}
