package com.backtesting.common.error;

import com.backtesting.common.RequestContext;
import com.backtesting.service.IdempotencyService;
import com.backtesting.service.kis.KisRateLimitedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GlobalExceptionHandler 매핑 행렬 검증. Standalone MockMvc — 스프링 컨텍스트 로딩 없이
 * 컨트롤러 + 어드바이스만 엮어 빠르게 실행.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        // standaloneSetup 에서는 MethodValidationPostProcessor 가 안 붙어 @Validated/@NotBlank
        // on @RequestParam 이 동작하지 않는다. 파라미터 검증은 @SpringBootTest 급 통합 테스트에서 커버.
        // 이 테스트는 body 기반 검증(@Valid @RequestBody) 에 집중.
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        RequestContext.set(RequestContext.withTimeout(30_000, "test-trace-id"));
    }

    @AfterEach
    void tearDown() { RequestContext.clear(); }

    @Test
    void bodyValidationFailureYields400WithFields() throws Exception {
        MvcResult r = mvc.perform(post("/t/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"qty\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.traceId").value("test-trace-id"))
                .andExpect(jsonPath("$.path").value("/t/body"))
                .andExpect(jsonPath("$.details").isArray())
                .andReturn();
        JsonNode json = mapper.readTree(r.getResponse().getContentAsString());
        assertThat(json.get("details").size()).isEqualTo(2);
    }

    @Test
    void malformedBodyYields400BadRequest() throws Exception {
        mvc.perform(post("/t/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void idempotencyConflictYields409() throws Exception {
        mvc.perform(get("/t/throw/idempotency-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void idempotencyInFlightYields409() throws Exception {
        mvc.perform(get("/t/throw/idempotency-inflight"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_FLIGHT"));
    }

    @Test
    void lossGuardBlockedYields423() throws Exception {
        mvc.perform(get("/t/throw/loss-guard"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("LOSS_GUARD_BLOCKED"));
    }

    @Test
    void kisRateLimitYields429WithRetryAfter() throws Exception {
        mvc.perform(get("/t/throw/kis-rate"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED_UPSTREAM"));
    }

    @Test
    void deadlineExceededYields504() throws Exception {
        mvc.perform(get("/t/throw/deadline"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("DEADLINE_EXCEEDED"));
    }

    @Test
    void illegalArgumentYields400() throws Exception {
        mvc.perform(get("/t/throw/illegal-arg"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void unexpectedThrowableYields500Masked() throws Exception {
        mvc.perform(get("/t/throw/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                // 메시지가 누수되지 않아야 한다 (마스킹 확인).
                .andExpect(jsonPath("$.message").value("서버 내부 오류"));
    }

    // ------------------ 테스트 전용 컨트롤러 ------------------

    @RestController
    @RequestMapping("/t")
    @org.springframework.validation.annotation.Validated
    static class TestController {
        @PostMapping("/body")
        String body(@Valid @RequestBody Payload p) { return "ok"; }

        @GetMapping("/throw/idempotency-conflict")
        String ic() { throw new IdempotencyService.Conflict("conflict"); }

        @GetMapping("/throw/idempotency-inflight")
        String iif() { throw new IdempotencyService.InFlight("in flight"); }

        @GetMapping("/throw/loss-guard")
        String lg() { throw new LossGuardBlockedException(-200_000L, 100_000L); }

        @GetMapping("/throw/kis-rate")
        String kr() { throw new KisRateLimitedException(2_500); }

        @GetMapping("/throw/deadline")
        String dl() { throw new RequestContext.DeadlineExceededException("timed out"); }

        @GetMapping("/throw/illegal-arg")
        String ia() { throw new IllegalArgumentException("bad arg"); }

        @GetMapping("/throw/boom")
        String boom() { throw new RuntimeException("internal detail"); }

        @Data
        static class Payload {
            @NotBlank String name;
            @Positive int qty;
        }
    }
}
