package com.backtesting.service.dart;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * DART 기업코드 ↔ 종목코드 매핑.
 * /api/corpCode.xml 은 zip으로 전체 기업 리스트를 제공. 한 번 다운로드 후 메모리 캐시.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DartCorpCodeService {

    private final DartClient client;

    private final ReentrantLock initLock = new ReentrantLock();
    /** 종목코드(6자리) → 기업코드(8자리). */
    private volatile Map<String, String> stockToCorp;
    /** 종목코드 → 기업명(표시용). */
    private volatile Map<String, String> stockToName;

    /**
     * 최초 접근 시 DART에서 기업코드 다운로드(~수 MB zip). 동시 호출에도 1회만 수행.
     */
    public Optional<String> corpCodeFor(String stockCode) {
        ensureLoaded();
        String k = normalize(stockCode);
        return Optional.ofNullable(stockToCorp.get(k));
    }

    public Optional<String> nameFor(String stockCode) {
        ensureLoaded();
        String k = normalize(stockCode);
        return Optional.ofNullable(stockToName.get(k));
    }

    public record CorpEntry(String stockCode, String corpCode, String name) {}

    /** 상장 기업 전체 (stock, corp, name). SPAC 유니버스 발굴 등에 사용. */
    public java.util.List<CorpEntry> listAll() {
        ensureLoaded();
        java.util.List<CorpEntry> out = new java.util.ArrayList<>(stockToCorp.size());
        stockToCorp.forEach((stock, corp) ->
                out.add(new CorpEntry(stock, corp, stockToName.get(stock))));
        return out;
    }

    /** 캐시된 매핑 수(디버그/헬스체크용). 미로드 상태면 0. */
    public int cachedCount() {
        Map<String, String> m = stockToCorp;
        return m == null ? 0 : m.size();
    }

    public void forceReload() {
        stockToCorp = null;
        stockToName = null;
        ensureLoaded();
    }

    private void ensureLoaded() {
        if (stockToCorp != null) return;
        initLock.lock();
        try {
            if (stockToCorp != null) return;
            log.info("Loading DART corp code mapping (first access)");
            load();
        } finally {
            initLock.unlock();
        }
    }

    private void load() {
        try {
            byte[] zipBytes = client.getBytes("/corpCode.xml", Map.of());
            Map<String, String> stockMap = new LinkedHashMap<>();
            Map<String, String> nameMap = new LinkedHashMap<>();
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (!entry.getName().toLowerCase().endsWith(".xml")) continue;
                    byte[] xml = zis.readAllBytes();
                    parseXml(xml, stockMap, nameMap);
                    break;
                }
            }
            this.stockToCorp = stockMap;
            this.stockToName = nameMap;
            log.info("DART corp code loaded: {} listed stocks", stockMap.size());
        } catch (Exception e) {
            throw new RuntimeException("DART corpCode 다운로드 실패: " + e.getMessage(), e);
        }
    }

    private void parseXml(byte[] xml, Map<String, String> stockMap, Map<String, String> nameMap) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        NodeList list = doc.getElementsByTagName("list");
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (!(n instanceof Element e)) continue;
            String corpCode = textOf(e, "corp_code");
            String stockCode = textOf(e, "stock_code");
            String name = textOf(e, "corp_name");
            // 상장사만 (stock_code 공백이 아닌 경우)
            if (stockCode != null && !stockCode.isBlank() && !"null".equals(stockCode)) {
                String s = normalize(stockCode);
                stockMap.put(s, corpCode);
                nameMap.put(s, name);
            }
        }
    }

    private static String textOf(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) return null;
        Node c = nl.item(0).getFirstChild();
        return c == null ? null : c.getNodeValue();
    }

    private static String normalize(String stockCode) {
        if (stockCode == null) return "";
        String s = stockCode.trim();
        // DART는 일부 6자리, 일부 7자리(.stock). 6자리로 통일.
        if (s.length() > 6) s = s.substring(s.length() - 6);
        if (s.length() < 6) s = String.format("%06d", Integer.parseInt(s));
        return s;
    }
}
