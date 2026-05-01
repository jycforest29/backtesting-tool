package com.backtesting.service.kis;

import com.backtesting.config.KisProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R8 — KRX 영업일 캘린더 단위 테스트.
 *
 *  - 캐시 hit 시 KIS 재호출 안 함 — 동일 날짜 반복 조회는 1회 fetch
 *  - 영업일 / 휴장일 응답 (opnd_yn = Y/N) 정확히 매핑
 *  - KIS 미설정 → false (보수적 휴장 간주)
 *  - HTTP 실패 → false (보수적 휴장 간주)
 *  - 빈 output → 캐시 비어 있음 + 후속 isOpenDay 도 false
 *  - PostConstruct warmup 실패는 부팅 흐름에 예외 안 던짐
 *  - 잘못된 row 1건이 다른 row 캐싱을 방해하지 않음 (격리)
 */
class KrxCalendarServiceTest {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private KisAuthService auth;
    private KisHttpCaller http;
    private KisProperties kisProps;
    private KrxCalendarService svc;

    @BeforeEach
    void setUp() {
        auth = mock(KisAuthService.class);
        http = mock(KisHttpCaller.class);
        kisProps = mock(KisProperties.class);

        when(kisProps.isConfigured()).thenReturn(true);
        when(kisProps.getBaseUrl()).thenReturn("https://kis-test");
        when(auth.getProps()).thenReturn(kisProps);
        when(auth.getAccessToken()).thenReturn("TOKEN");
        when(http.baseHeaders(any(), any(), anyString())).thenReturn(Map.of());

        svc = new KrxCalendarService(auth, http, kisProps);
    }

    private JsonNode mockResponse(Map<LocalDate, Boolean> entries) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ArrayNode arr = root.putArray("output");
        entries.forEach((d, open) -> {
            ObjectNode r = arr.addObject();
            r.put("bass_dt", d.format(YMD));
            r.put("opnd_yn", open ? "Y" : "N");
        });
        return root;
    }

    private Map<LocalDate, Boolean> ordered(Object... pairs) {
        // LinkedHashMap 유지 — JSON 응답 순서와 무관하게 cache 채움 검증.
        Map<LocalDate, Boolean> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((LocalDate) pairs[i], (Boolean) pairs[i + 1]);
        }
        return m;
    }

    @Test
    void mapsOpndYnToOpenDayCorrectly() throws Exception {
        when(http.get(anyString(), any(), any())).thenReturn(mockResponse(ordered(
                LocalDate.of(2026, 5, 1), false,   // 근로자의 날
                LocalDate.of(2026, 5, 4), true,
                LocalDate.of(2026, 5, 5), false    // 어린이날
        )));

        assertThat(svc.isOpenDay(LocalDate.of(2026, 5, 1))).isFalse();
        assertThat(svc.isOpenDay(LocalDate.of(2026, 5, 4))).isTrue();
        assertThat(svc.isOpenDay(LocalDate.of(2026, 5, 5))).isFalse();
    }

    @Test
    void cacheHitAvoidsRefetch() throws Exception {
        when(http.get(anyString(), any(), any())).thenReturn(mockResponse(ordered(
                LocalDate.of(2026, 5, 4), true
        )));

        svc.isOpenDay(LocalDate.of(2026, 5, 4));
        svc.isOpenDay(LocalDate.of(2026, 5, 4));
        svc.isOpenDay(LocalDate.of(2026, 5, 4));

        verify(http, times(1)).get(anyString(), any(), any());
    }

    @Test
    void singleFetchPopulatesCacheForAllReturnedDates() throws Exception {
        when(http.get(anyString(), any(), any())).thenReturn(mockResponse(ordered(
                LocalDate.of(2026, 5, 4), true,
                LocalDate.of(2026, 5, 5), false,
                LocalDate.of(2026, 5, 6), true,
                LocalDate.of(2026, 5, 7), true
        )));

        // 첫 호출에서 4개 row 다 캐시 — 다음 조회들은 추가 호출 안 함.
        svc.isOpenDay(LocalDate.of(2026, 5, 4));
        svc.isOpenDay(LocalDate.of(2026, 5, 5));
        svc.isOpenDay(LocalDate.of(2026, 5, 6));
        svc.isOpenDay(LocalDate.of(2026, 5, 7));

        verify(http, times(1)).get(anyString(), any(), any());
        assertThat(svc.cacheSize()).isEqualTo(4);
    }

    @Test
    void httpFailureFallsBackToHoliday() throws Exception {
        when(http.get(anyString(), any(), any())).thenThrow(new RuntimeException("KIS down"));
        // 보수적 정책: fetch 실패 = 휴장 간주 (잘못 발화 회피).
        assertThat(svc.isOpenDay(LocalDate.of(2026, 5, 4))).isFalse();
    }

    @Test
    void kisNotConfiguredReturnsFalseWithoutHttpCall() throws Exception {
        when(kisProps.isConfigured()).thenReturn(false);
        // 새 인스턴스 — props 변경 후 재생성.
        svc = new KrxCalendarService(auth, http, kisProps);

        assertThat(svc.isOpenDay(LocalDate.of(2026, 5, 4))).isFalse();
        verify(http, never()).get(anyString(), any(), any());
    }

    @Test
    void warmupSwallowsExceptionToAllowBoot() throws Exception {
        when(http.get(anyString(), any(), any())).thenThrow(new RuntimeException("boot-time KIS down"));

        // PostConstruct 로 호출되는 메서드. 부팅 흐름이 끊기면 안 됨.
        assertThatNoException().isThrownBy(() -> svc.warmup());
    }

    @Test
    void emptyOutputLeavesCacheEmpty() throws Exception {
        ObjectNode empty = JsonNodeFactory.instance.objectNode();
        empty.putArray("output");
        when(http.get(anyString(), any(), any())).thenReturn(empty);

        boolean result = svc.isOpenDay(LocalDate.of(2026, 5, 4));

        // 응답에 해당 날짜 없음 → cache miss 가 아니라 cache 자체에 entry 없음 → false (보수적).
        assertThat(result).isFalse();
        assertThat(svc.cacheSize()).isZero();
    }

    @Test
    void malformedRowDoesNotPoisonOtherRows() throws Exception {
        // 한 row 의 bass_dt 가 깨졌어도 다른 row 들은 정상 캐싱.
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ArrayNode arr = root.putArray("output");
        arr.addObject().put("bass_dt", "BADDATE").put("opnd_yn", "Y");
        arr.addObject().put("bass_dt", "20260504").put("opnd_yn", "Y");
        arr.addObject().put("bass_dt", "20260505").put("opnd_yn", "N");
        when(http.get(anyString(), any(), any())).thenReturn(root);

        assertThat(svc.isOpenDay(LocalDate.of(2026, 5, 4))).isTrue();
        assertThat(svc.isOpenDay(LocalDate.of(2026, 5, 5))).isFalse();
    }

    @Test
    void refreshUsesCorrectTrIdAndEndpoint() throws Exception {
        when(http.get(anyString(), any(), any())).thenReturn(mockResponse(ordered(
                LocalDate.of(2026, 5, 4), true
        )));

        svc.refresh();

        // 정확한 tr_id 와 endpoint 호출 확인 — KIS chk-holiday 시세성 API.
        verify(http).baseHeaders(eq(kisProps), eq("TOKEN"), eq("CTCA0903R"));
        verify(http).get(eq("https://kis-test/uapi/domestic-stock/v1/quotations/chk-holiday"), any(), any());
    }
}
