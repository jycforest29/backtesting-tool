package com.backtesting.common.error;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link GlobalExceptionHandler} 가 {@link ConfigurationMissingException} 을
 * 503 + DEPENDENCY_NOT_CONFIGURED + 운영자 메시지 노출로 매핑하는지 검증.
 *
 * 풀 SpringBootTest 회피 — standaloneSetup 으로 advice + 스텁 컨트롤러만 wire.
 * 진짜 서비스 빈 (KIS/Kafka/Redis) 부팅 비용 없이 계약(상태코드/스키마) 만 확인.
 */
class ConfigurationMissingExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new StubController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("ConfigurationMissingException → 503 + code=DEPENDENCY_NOT_CONFIGURED + 메시지 그대로 노출")
    void mapsToServiceUnavailableWithExposedMessage() throws Exception {
        mvc.perform(get("/test/missing-kis"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_NOT_CONFIGURED"))
                // 메시지가 마스킹("서버 내부 오류") 되지 않고 운영자 actionable 한 안내가 그대로 전달돼야 한다.
                .andExpect(jsonPath("$.message").value(
                        "KIS API 키가 설정되지 않았습니다. HANTOO_API_KEY / HANTOO_API_SECRET 환경변수를 설정하세요."))
                .andExpect(jsonPath("$.path").value("/test/missing-kis"))
                // 503 은 자동 retry 의도가 아니라 운영자가 환경변수 채워야 하는 사건 — Retry-After 헤더 X.
                .andExpect(header().doesNotExist("Retry-After"));
    }

    // ────────────────────────────────────────────────────────────────────────

    @RestController
    static class StubController {
        @GetMapping("/test/missing-kis")
        String simulateMissingKis() {
            throw new ConfigurationMissingException("KIS API",
                    "KIS API 키가 설정되지 않았습니다. HANTOO_API_KEY / HANTOO_API_SECRET 환경변수를 설정하세요.");
        }
    }
}
