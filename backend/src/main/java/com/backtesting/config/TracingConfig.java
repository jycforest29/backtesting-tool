package com.backtesting.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * OpenTelemetry SDK 초기화.
 *
 * 전략:
 *  - otel.exporter.otlp.endpoint 미설정이면 OpenTelemetry.noop() — 프로덕션 OTLP collector 없이도
 *    앱이 기동하고 trace 인스트루먼트 코드가 예외 없이 통과 (span 은 버려짐).
 *  - 설정되면 batch exporter 로 실제 OTLP 서버(예: Jaeger, Grafana Tempo) 로 전송.
 *
 * Resource attributes:
 *  - service.name / service.version / deployment.environment — 분산 시스템에서 서비스 식별
 */
@Slf4j
@Configuration
public class TracingConfig {

    private static final String TRACER_NAME = "backtesting-tool";

    @Value("${otel.exporter.otlp.endpoint:}")
    private String otlpEndpoint;

    @Value("${otel.service.name:backtesting-tool}")
    private String serviceName;

    @Value("${otel.deployment.environment:local}")
    private String deploymentEnv;

    /**
     * destroyMethod="" : OpenTelemetry interface 는 close() 시그니처가 구현체별로 다르고
     * noop() 반환 객체는 Spring 의 destroy 추론에서 "Invalid destruction signature" 로 보고된다.
     * SDK 모드에서는 별도 SdkTracerProvider 빈을 두어 그쪽에서 close() 를 책임진다.
     */
    @Bean(destroyMethod = "")
    @Primary
    public OpenTelemetry openTelemetry() {
        if (otlpEndpoint == null || otlpEndpoint.isBlank()) {
            log.info("OTLP endpoint 미설정 → OpenTelemetry no-op 모드");
            return OpenTelemetry.noop();
        }
        log.info("OpenTelemetry SDK 기동: endpoint={}, service={}", otlpEndpoint, serviceName);
        Resource resource = Resource.getDefault().merge(Resource.builder()
                .put("service.name", serviceName)
                .put("deployment.environment", deploymentEnv)
                .build());
        OtlpGrpcSpanExporter exporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(otlpEndpoint)
                .setTimeout(Duration.ofSeconds(10))
                .build();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                        .setScheduleDelay(Duration.ofSeconds(2))
                        .setMaxExportBatchSize(512)
                        .build())
                .setResource(resource)
                .build();
        return OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .build();
    }

    @Bean
    public Tracer tracer(OpenTelemetry otel) {
        return otel.getTracer(TRACER_NAME);
    }
}
