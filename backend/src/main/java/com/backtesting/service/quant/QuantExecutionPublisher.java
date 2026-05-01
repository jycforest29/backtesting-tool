package com.backtesting.service.quant;

import com.backtesting.model.quant.QuantExecutionLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 퀀트 실행 이벤트 SSE 브로커.
 *
 *  - {@link #subscribe()} 호출 시 새 SseEmitter 발급, 풀에 등록
 *  - {@link #publish(QuantExecutionLog)} 가 풀의 모든 emitter 에 broadcast
 *  - 클라이언트 끊김(IOException) / timeout / completion 시 자동 제거
 *
 * 왜 SSE 인가:
 *   - 단방향 server-push 면 충분 (subscribe 외 클라이언트 메시지 없음)
 *   - HTTP 위라 nginx 프록시 그대로 통과 (WebSocket 핸드셰이크 불필요)
 *   - EventSource 가 native auto-reconnect — 재연결 로직 안 짜도 됨
 *   - SockJS/STOMP 대비 운영 단순. WS 는 알림 토글에 별도 사용 중이지만 거래 timeline 에는 과함.
 *
 * 메모리/생애주기:
 *   - emitter 당 하나의 HTTP 응답을 잡고 있음. 풀 size 가 곧 동시 접속자 수.
 *   - 단일 사용자/소수 패널 가정이라 unbounded 풀 OK. 멀티테넌트로 갈 경우 size cap + LRU 필요.
 *   - timeout 60분 — 모바일 백그라운드/슬립 대비. EventSource 가 reconnect 자동.
 */
@Slf4j
@Component
public class QuantExecutionPublisher {

    private static final long TIMEOUT_MS = 60 * 60 * 1000L;  // 1시간

    private final Set<SseEmitter> emitters = new CopyOnWriteArraySet<>();
    private final AtomicLong eventSeq = new AtomicLong();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.add(emitter);
        emitter.onCompletion(() -> {
            emitters.remove(emitter);
            log.debug("SSE subscriber completed; pool size = {}", emitters.size());
        });
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            log.debug("SSE subscriber timed out; pool size = {}", emitters.size());
        });
        emitter.onError(ex -> {
            emitters.remove(emitter);
            log.debug("SSE subscriber errored ({}); pool size = {}", ex.getMessage(), emitters.size());
        });
        // 첫 메시지로 hello 보내 클라이언트가 onopen 수신 후 빈 화면 안 띄우도록.
        try {
            emitter.send(SseEmitter.event().name("hello").data("connected"));
        } catch (IOException e) {
            emitters.remove(emitter);
            emitter.completeWithError(e);
        }
        log.debug("SSE subscriber added; pool size = {}", emitters.size());
        return emitter;
    }

    /**
     * 새 실행 이벤트를 모든 구독자에게 push.
     * 실패한 emitter 는 풀에서 제거 — 한 클라이언트 끊김이 다른 구독자에게 영향 없도록 격리.
     */
    public void publish(QuantExecutionLog logEntry) {
        if (emitters.isEmpty()) return;
        long id = eventSeq.incrementAndGet();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(id))
                        .name("execution")
                        .data(logEntry));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
                emitter.completeWithError(e);
            }
        }
    }

    /** 테스트/관측용 — 현재 활성 구독자 수. */
    int subscriberCount() { return emitters.size(); }
}
