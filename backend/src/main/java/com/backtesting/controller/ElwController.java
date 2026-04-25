package com.backtesting.controller;

import com.backtesting.model.elw.ElwModels.ElwSkewResponse;
import com.backtesting.service.elw.ElwChainProvider;
import com.backtesting.service.elw.ElwCircuitBreaker;
import com.backtesting.service.elw.ElwSkewService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/elw")
@Validated
@RequiredArgsConstructor
public class ElwController {

    private final ElwSkewService skewService;
    private final ElwChainProvider chainProvider;
    private final ElwCircuitBreaker breaker;

    /** 기초자산별 ELW 스큐 스캔. degraded 응답에도 200 — 클라이언트가 circuitState/degradedReason 으로 판단. */
    @GetMapping("/skew")
    public ElwSkewResponse skew(@RequestParam @NotBlank
                                 @Pattern(regexp = "^[A-Za-z0-9.\\-^]{1,20}$") String underlying) {
        return skewService.scan(underlying);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "provider", chainProvider.label(),
                "providerAvailable", chainProvider.isAvailable(),
                "circuitState", breaker.state().name()
        );
    }
}
