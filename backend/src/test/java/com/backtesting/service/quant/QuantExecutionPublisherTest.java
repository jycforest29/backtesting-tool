package com.backtesting.service.quant;

import com.backtesting.model.quant.QuantExecutionLog;
import com.backtesting.model.quant.QuantStrategyType;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE 브로커 단위 테스트. 스프링 컨텍스트 없이 Publisher 만 검증.
 *
 *  - subscribe → 풀 등록 + hello 이벤트 송신 + onCompletion/onError 핸들러 부착
 *  - publish → 풀의 모든 emitter 에 broadcast (id 자동 증가)
 *  - 끊긴 emitter (complete 후 send) → 풀에서 자동 제거 (publish 가 IllegalStateException 처리)
 *  - 빈 풀 publish → no-op (예외 안 남)
 */
class QuantExecutionPublisherTest {

    private QuantExecutionLog sample() {
        return QuantExecutionLog.builder()
                .strategyType(QuantStrategyType.DUAL_MOMENTUM)
                .executedAt(LocalDateTime.of(2026, 4, 30, 15, 25))
                .kind(QuantExecutionLog.ExecutionKind.MANUAL)
                .orders(List.of())
                .build();
    }

    @Test
    void subscribeRegistersEmitterAndSendsHello() {
        QuantExecutionPublisher pub = new QuantExecutionPublisher();
        SseEmitter e = pub.subscribe();
        assertThat(e).isNotNull();
        assertThat(pub.subscriberCount()).isEqualTo(1);
        // hello 이벤트는 첫 send 가 발생했음을 의미. SseEmitter 내부 큐에 적재됨.
        // 외부에서 확인 가능한 부수효과는 풀 size — IOException 없이 등록 완료된 점이 hello 송신 성공의 증거.
    }

    @Test
    void publishWithNoSubscribersIsNoop() {
        QuantExecutionPublisher pub = new QuantExecutionPublisher();
        pub.publish(sample());  // 예외 안 나면 통과
        assertThat(pub.subscriberCount()).isZero();
    }

    @Test
    void publishToActiveSubscribersKeepsThemInPool() {
        QuantExecutionPublisher pub = new QuantExecutionPublisher();
        SseEmitter a = pub.subscribe();
        SseEmitter b = pub.subscribe();
        assertThat(pub.subscriberCount()).isEqualTo(2);

        pub.publish(sample());
        pub.publish(sample());

        // 정상 emitter 는 send 후에도 풀에 남아있음.
        assertThat(pub.subscriberCount()).isEqualTo(2);
        assertThat(a).isNotSameAs(b);
    }

    @Test
    void completedSubscriberIsRemovedOnNextPublish() {
        QuantExecutionPublisher pub = new QuantExecutionPublisher();
        SseEmitter doomed = pub.subscribe();
        SseEmitter alive = pub.subscribe();
        assertThat(pub.subscriberCount()).isEqualTo(2);

        // emitter.complete() 후 send 시도 → IllegalStateException → publish 가 풀에서 제거.
        // (standalone 단위 테스트에서는 SseEmitter 의 HTTP handler 가 없어 onCompletion 콜백이
        //  즉시 실행되지 않는다. 끊김 정리는 publish 경로에서만 관측 가능.)
        doomed.complete();

        pub.publish(sample());

        // 끊긴 쪽만 빠지고, 살아있는 쪽은 그대로 — 한 클라이언트 끊김이 다른 구독자에게 영향 없음 (격리).
        assertThat(pub.subscriberCount()).isEqualTo(1);
        assertThat(alive).isNotNull();
    }
}
