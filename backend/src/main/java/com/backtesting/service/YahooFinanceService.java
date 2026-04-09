package com.backtesting.service;

import com.backtesting.model.AssetType;
import com.backtesting.model.PricePoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class YahooFinanceService {

    private static final String CHART_URL = "https://query1.finance.yahoo.com/v8/finance/chart/";
    private static final String SEARCH_URL = "https://query1.finance.yahoo.com/v1/finance/search";
    private static final String CRUMB_URL = "https://query2.finance.yahoo.com/v1/test/getcrumb";
    private static final String COOKIE_URL = "https://fc.yahoo.com";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String crumb;

    public YahooFinanceService() {
        this.httpClient = HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
    }

    @PostConstruct
    public void init() {
        try {
            refreshCrumb();
        } catch (Exception e) {
            log.warn("Failed to initialize Yahoo Finance crumb: {}", e.getMessage());
        }
    }

    private void refreshCrumb() throws Exception {
        // Step 1: Get cookies
        HttpRequest cookieReq = HttpRequest.newBuilder()
                .uri(URI.create(COOKIE_URL))
                .header("User-Agent", USER_AGENT)
                .GET().build();
        httpClient.send(cookieReq, HttpResponse.BodyHandlers.ofString());

        // Step 2: Get crumb
        HttpRequest crumbReq = HttpRequest.newBuilder()
                .uri(URI.create(CRUMB_URL))
                .header("User-Agent", USER_AGENT)
                .GET().build();
        HttpResponse<String> crumbResp = httpClient.send(crumbReq, HttpResponse.BodyHandlers.ofString());
        this.crumb = crumbResp.body().trim();
        log.info("Yahoo Finance crumb initialized");
    }

    public String resolveSymbol(AssetType assetType, String symbol) {
        // For commodities/forex, always use preset symbols
        if (assetType == AssetType.GOLD) return "GC=F";
        if (assetType == AssetType.SILVER) return "SI=F";
        if (assetType == AssetType.BITCOIN) return "BTC-USD";
        if (assetType == AssetType.FOREX && !symbol.contains("=")) return symbol + "=X";

        // For stocks, the symbol from search is already a full Yahoo symbol (e.g. 6758.T, 005930.KS)
        // Only add suffix if the user typed a raw code without exchange
        if (symbol.contains(".") || symbol.contains("=")) {
            return symbol;
        }
        return switch (assetType) {
            case KR_STOCK -> symbol + ".KS";
            case JP_STOCK -> symbol + ".T";
            default -> symbol;
        };
    }

    public record ChartResult(
            String name,
            String currency,
            List<PricePoint> priceHistory,
            BigDecimal currentPrice
    ) {}

    public ChartResult getChartData(String yahooSymbol, LocalDate startDate) throws Exception {
        long period1 = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        long period2 = LocalDate.now().plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC);

        String url = CHART_URL + yahooSymbol
                + "?period1=" + period1
                + "&period2=" + period2
                + "&interval=1d&includePrePost=false";
        if (crumb != null) {
            url += "&crumb=" + crumb;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET().build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            refreshCrumb();
            url = CHART_URL + yahooSymbol
                    + "?period1=" + period1 + "&period2=" + period2
                    + "&interval=1d&includePrePost=false&crumb=" + crumb;
            request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .GET().build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode result = root.path("chart").path("result").get(0);

        if (result == null) {
            String errorMsg = root.path("chart").path("error").path("description").asText("Unknown symbol");
            throw new IllegalArgumentException("Symbol not found: " + yahooSymbol + " - " + errorMsg);
        }

        JsonNode meta = result.path("meta");
        String name = meta.path("shortName").asText(yahooSymbol);
        String currency = meta.path("currency").asText("USD");
        BigDecimal currentPrice = BigDecimal.valueOf(meta.path("regularMarketPrice").asDouble());

        JsonNode timestamps = result.path("timestamp");
        JsonNode closes = result.path("indicators").path("quote").get(0).path("close");

        List<PricePoint> history = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            long ts = timestamps.get(i).asLong();
            LocalDate date = LocalDate.ofEpochDay(ts / 86400);
            JsonNode closeNode = closes.get(i);
            if (closeNode != null && !closeNode.isNull()) {
                history.add(new PricePoint(date, BigDecimal.valueOf(closeNode.asDouble())));
            }
        }

        return new ChartResult(name, currency, history, currentPrice);
    }

    public List<Map<String, String>> search(String query) throws Exception {
        String url = SEARCH_URL + "?q=" + query + "&quotesCount=10&newsCount=0";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET().build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode quotes = root.path("quotes");

        List<Map<String, String>> results = new ArrayList<>();
        for (JsonNode quote : quotes) {
            results.add(Map.of(
                    "symbol", quote.path("symbol").asText(),
                    "name", quote.path("shortname").asText(quote.path("longname").asText("")),
                    "exchange", quote.path("exchange").asText(""),
                    "type", quote.path("quoteType").asText("")
            ));
        }
        return results;
    }
}
