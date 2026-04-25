package com.backtesting.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 시간 의존성 추상화. 모든 시간 관련 코드는 Instant.now() 대신 주입된 Clock 을 사용.
 *
 * 효과:
 *  - 테스트: Clock.fixed(..) / MutableClock 으로 Thread.sleep 없이 시간 제어
 *  - DST(deterministic simulation): 같은 Clock 을 tick 으로 전진시키며 재현 가능한 fault 시나리오
 *  - 프로덕션: 시스템 시계(Asia/Seoul) — 기존 동작과 동일
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
