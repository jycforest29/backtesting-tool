package com.backtesting.model;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 캐시 직렬화 회귀 방지.
 *
 * Spring Data Redis 의 GenericJackson2JsonRedisSerializer 와 동일한 ObjectMapper 구성
 * (DefaultTyping = LaminatedTypingNonFinal, "@class" 프로퍼티에 타입 기록)을 흉내 내
 * Jackson 만으로 round-trip 가능한지 검증.
 *
 * 회귀 케이스: BalanceResult/Holding 에 @NoArgsConstructor 가 빠지면 역직렬화 시
 * "Cannot construct instance ... no Creators" 로 실패한다 — 그러면 캐시 첫 hit 부터
 * 모든 잔고 호출이 500 으로 죽는다 (실제 발생 사례).
 */
class BalanceResultSerializationTest {

    private static ObjectMapper redisLikeMapper() {
        ObjectMapper m = new ObjectMapper();
        // GenericJackson2JsonRedisSerializer 가 켜는 옵션과 동일한 효과:
        //  - 모든 비-final 클래스에 "@class" 정보를 함께 직렬화
        m.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder().allowIfBaseType(Object.class).build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);
        m.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return m;
    }

    @Test
    @DisplayName("BalanceResult — Builder 로 생성한 인스턴스가 Redis-style round-trip 통과")
    void roundTripPreservesAllFields() throws Exception {
        BalanceResult original = BalanceResult.builder()
                .market(AssetType.KR_STOCK)
                .deposit(new BigDecimal("1143001"))
                .totalEvalAmount(new BigDecimal("1603501"))
                .totalPnl(new BigDecimal("-12000"))
                .totalPnlRate(new BigDecimal("0.0000"))
                .holdings(List.of(
                        BalanceResult.Holding.builder()
                                .symbol("373220")
                                .name("LG에너지솔루션")
                                .quantity(1)
                                .avgPrice(new BigDecimal("472500"))
                                .currentPrice(new BigDecimal("460500"))
                                .evalAmount(new BigDecimal("460500"))
                                .pnl(new BigDecimal("-12000"))
                                .pnlRate(new BigDecimal("-2.5300"))
                                .build()
                ))
                .build();

        ObjectMapper mapper = redisLikeMapper();
        String json = mapper.writeValueAsString(original);
        BalanceResult restored = mapper.readValue(json, BalanceResult.class);

        // 필드 단위 등치성 (Lombok @Data 의 equals 가 모든 필드 비교)
        assertThat(restored).isEqualTo(original);
        assertThat(restored.getHoldings()).hasSize(1);
        assertThat(restored.getHoldings().get(0).getName()).isEqualTo("LG에너지솔루션");
        assertThat(restored.getHoldings().get(0).getQuantity()).isEqualTo(1L);
    }

    @Test
    @DisplayName("BalanceResult — holdings 가 빈 리스트여도 round-trip OK")
    void roundTripWithEmptyHoldings() throws Exception {
        BalanceResult original = BalanceResult.builder()
                .market(AssetType.US_STOCK)
                .deposit(new BigDecimal("0"))
                .totalEvalAmount(new BigDecimal("0"))
                .totalPnl(new BigDecimal("0"))
                .totalPnlRate(new BigDecimal("0"))
                .holdings(List.of())
                .build();

        ObjectMapper mapper = redisLikeMapper();
        String json = mapper.writeValueAsString(original);
        BalanceResult restored = mapper.readValue(json, BalanceResult.class);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.getHoldings()).isEmpty();
    }
}
