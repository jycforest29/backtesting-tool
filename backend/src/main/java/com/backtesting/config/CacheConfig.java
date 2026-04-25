package com.backtesting.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

/**
 * Redis 캐시 구성.
 * 캐시별 TTL 다르게 지정:
 *   - kisQuote: 5초 (시세는 아주 짧게)
 *   - kisBalance: 30초 (자주 갱신)
 *   - kisChart: 5분 (일봉 → 장중 변화 있어도 리밸런스엔 영향 적음)
 *   - dartFundamentals: 24시간 (재무제표는 분기/연 단위)
 *   - kospiUniverse: 1시간 (큐레이션 리스트 거의 안 바뀜)
 */
@Configuration
@EnableCaching
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = false)
public class CacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory cf) {
        // @class 타입 정보 유지 — 없으면 POJO가 LinkedHashMap으로 역직렬화되어
        // BalanceResult 등 캐시 조회 시 ClassCastException 발생.
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.backtesting.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.lang.")
                .allowIfSubType("java.math.")
                .allowIfSubType("java.time.")
                .build();
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        GenericJackson2JsonRedisSerializer jsonSer = new GenericJackson2JsonRedisSerializer(om);

        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(60))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonSer));

        Map<String, RedisCacheConfiguration> perCache = Map.of(
                "kisQuote",        base.entryTtl(Duration.ofSeconds(5)),
                "kisBalance",      base.entryTtl(Duration.ofSeconds(30)),
                "kisChart",        base.entryTtl(Duration.ofMinutes(5)),
                "dartFundamentals", base.entryTtl(Duration.ofHours(24)),
                "kospiUniverse",   base.entryTtl(Duration.ofHours(1))
        );

        return RedisCacheManager.builder(cf)
                .cacheDefaults(base)
                .withInitialCacheConfigurations(perCache)
                .build();
    }
}
