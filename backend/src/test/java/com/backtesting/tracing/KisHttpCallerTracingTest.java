package com.backtesting.tracing;

import com.backtesting.config.KisProperties;
import com.backtesting.service.kis.KisHttpCaller;
import com.backtesting.service.kis.KisRateLimiter;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

/**
 * KIS HTTP 호출이 OpenTelemetry span 을 생성하고 상태 코드 attribute 를 기록하는지.
 * InMemorySpanExporter 로 전송된 span 을 직접 검사.
 */
class KisHttpCallerTracingTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Test
    void successfulGet_producesSpanWith200StatusAttribute() throws Exception {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        OpenTelemetry otel = buildOtel(exporter);
        KisHttpCaller http = newCaller(otel);

        wm.stubFor(get(urlPathEqualTo("/ok"))
                .willReturn(okJson("{\"ok\":true}")));

        http.get(wm.baseUrl() + "/ok", Map.of(), Map.of());

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        SpanData span = spans.get(0);
        assertEquals("kis.http", span.getName());
        assertEquals(200L, span.getAttributes().asMap().entrySet().stream()
                .filter(e -> e.getKey().getKey().equals("http.status_code"))
                .map(Map.Entry::getValue).findFirst().orElse(null));
    }

    @Test
    void errorResponse_spanMarkedWithErrorStatus() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        OpenTelemetry otel = buildOtel(exporter);
        KisHttpCaller http = newCaller(otel);

        wm.stubFor(get(urlPathEqualTo("/err")).willReturn(serverError()));

        assertThrows(RuntimeException.class,
                () -> http.get(wm.baseUrl() + "/err", Map.of(), Map.of()));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, spans.get(0).getStatus().getStatusCode());
    }

    @Test
    void noopOtel_doesNotCrashHttpCall() throws Exception {
        KisHttpCaller http = newCaller(OpenTelemetry.noop());
        wm.stubFor(get(urlPathEqualTo("/noop")).willReturn(okJson("{}")));
        assertDoesNotThrow(() -> http.get(wm.baseUrl() + "/noop", Map.of(), Map.of()));
    }

    // ----- helpers -----

    private static OpenTelemetry buildOtel(InMemorySpanExporter exporter) {
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        return OpenTelemetrySdk.builder().setTracerProvider(provider).build();
    }

    private static KisHttpCaller newCaller(OpenTelemetry otel) {
        KisProperties kp = new KisProperties();
        kp.setRequestsPerSecond(100);
        kp.setTokenRequestsPerSecond(10);
        kp.setMaxWaitMs(2000);
        Tracer tracer = otel.getTracer("test");
        return new KisHttpCaller(new KisRateLimiter(kp, java.time.Clock.systemUTC()), tracer);
    }
}
