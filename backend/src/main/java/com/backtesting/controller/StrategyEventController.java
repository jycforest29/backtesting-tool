package com.backtesting.controller;

import com.backtesting.model.quant.StrategyAggregateState;
import com.backtesting.model.quant.StrategyDomainEvent;
import com.backtesting.service.quant.StrategyEventStore;
import com.backtesting.service.quant.StrategyProjection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

/**
 * 이벤트 소싱 기반 전략 aggregate API.
 *
 *  POST /api/strategy-events/{aggId}           — 도메인 이벤트 append (OCC)
 *  GET  /api/strategy-events/{aggId}/state     — 최신 state (snapshot + delta)
 *  GET  /api/strategy-events/{aggId}/state?asOf=... — time-travel
 */
@RestController
@RequestMapping("/api/strategy-events")
@Validated
@RequiredArgsConstructor
public class StrategyEventController {

    private final StrategyEventStore eventStore;
    private final StrategyProjection projection;

    @PostMapping("/{aggregateId}")
    public Map<String, Object> append(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._\\-]+$") String aggregateId,
            @RequestParam long expectedVersion,
            @Valid @RequestBody StrategyDomainEvent event) {
        try {
            long newVersion = eventStore.append(aggregateId, expectedVersion, event);
            projection.maybeSnapshot(aggregateId, newVersion);
            return Map.of(
                    "aggregateId", aggregateId,
                    "newVersion", newVersion,
                    "eventType", event.eventType()
            );
        } catch (StrategyEventStore.OptimisticConcurrency e) {
            // OCC 는 도메인 정상 경합 — 409 로 매핑. 별도 예외 매핑 대신 ResponseStatusException 사용.
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    @GetMapping("/{aggregateId}/state")
    public StrategyAggregateState state(
            @PathVariable @NotBlank @Size(max = 64) String aggregateId,
            @RequestParam(required = false) Instant asOf) {
        return asOf != null
                ? projection.projectAt(aggregateId, asOf)
                : projection.current(aggregateId);
    }

    @GetMapping("/{aggregateId}/version")
    public Map<String, Object> version(
            @PathVariable @NotBlank @Size(max = 64) String aggregateId) {
        return Map.of(
                "aggregateId", aggregateId,
                "version", eventStore.currentVersion(aggregateId)
        );
    }
}
