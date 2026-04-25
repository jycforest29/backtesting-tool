package com.backtesting.service;

import com.backtesting.config.AlertProperties;
import com.backtesting.domain.Money;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 일일 실현손익 추적 + 신규 매수 주문 차단.
 *
 * 보호 대상: 국내(KR_STOCK) BUY 주문. 매도는 청산 허용 목적이라 제약 없음.
 * 누적 실현 손실이 limit 을 초과하면 {@link #isBuyAllowed()} 가 false.
 *
 * 동시성 설계:
 *  - 상태(day, net, alerted) 를 하나의 불변 {@link DayState} 레코드에 담고 CAS 로 전이.
 *  - 날짜 경계 (00:00 KST) 에 두 스레드가 동시 도착해도 CAS 는 하나만 성공 — 이중 리셋 없음.
 *  - @Scheduled 자정 리셋은 안전장치. 정상 경로에서도 첫 접근 시 lazy reset 이 일어난다.
 *
 * 테스트 편의:
 *  - Clock 주입. 테스트에서는 MutableClock 으로 "자정 건너뛰기" 재현 가능.
 *  - netRealized 는 {@link Money}(KRW) 로 노출 — 통화 안전성.
 */
@Slf4j
@Service
public class DailyLossGuard {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String CURRENCY_KRW = "KRW";

    private final AlertProperties alertProps;
    private final EmailService emailService;
    private final Clock clock;

    private final AtomicReference<DayState> state;

    public DailyLossGuard(AlertProperties alertProps, EmailService emailService, Clock clock) {
        this.alertProps = alertProps;
        this.emailService = emailService;
        // clock.withZone() 대신 today() 에서 매번 SEOUL 적용 — 테스트의 MutableClock 이
        // withZone 시 새 인스턴스를 반환해 advance 가 전파되지 않는 문제 회피.
        this.clock = clock;
        this.state = new AtomicReference<>(new DayState(today(), 0L, false));
    }

    /** clock 의 Instant 를 Asia/Seoul 타임존으로 해석한 오늘 날짜. */
    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), SEOUL);
    }

    /**
     * OCO 청산 등에서 호출. pnlKrw 는 양수면 수익, 음수면 손실. 원 단위 정수.
     * 한도 교차 시(false → true) 이메일 알림 1회.
     */
    public void recordRealizedPnl(long pnlKrw) {
        long limit = alertProps.getDailyLoss().getLimitKrw();
        LocalDate today = today();

        DayState previous;
        DayState updated;
        while (true) {
            previous = state.get();
            DayState base = today.equals(previous.day())
                    ? previous
                    : new DayState(today, 0L, false);   // lazy day-rollover

            long newNet = base.netRealizedKrw() + pnlKrw;
            boolean crossed = !base.alerted() && newNet <= -limit;
            updated = new DayState(base.day(), newNet, base.alerted() || crossed);

            if (state.compareAndSet(previous, updated)) break;
            // CAS 실패 — 다른 스레드가 동시에 업데이트. 재시도.
        }
        log.info("DailyLossGuard: pnl={} net={} alerted={}",
                pnlKrw, updated.netRealizedKrw(), updated.alerted());

        // 상태 전이가 확정된 이후에만 알림을 발송 — 리트라이 중 중복 발송 방지.
        if (!previous.alerted() && updated.alerted()) {
            sendLimitAlert(updated.netRealizedKrw(), limit);
        }
    }

    /** 신규 매수 주문 허용 여부. 매도는 제약 없음 (청산 허용). */
    public boolean isBuyAllowed() {
        long limit = alertProps.getDailyLoss().getLimitKrw();
        return freshNetKrw() > -limit;
    }

    /** 현재 누적 실현 PnL. 호출 시점에 lazy rollover 반영. */
    public long getNetRealizedKrw() { return freshNetKrw(); }

    public long getLimitKrw() { return alertProps.getDailyLoss().getLimitKrw(); }

    /** Money 형태로 노출 — 값 객체 호환 API. */
    public Money getNetRealized() { return Money.krw(freshNetKrw()); }
    public Money getLimit()        { return Money.krw(getLimitKrw()); }

    /** 자정 KST 스케줄 리셋. 정상 경로에서 recordRealizedPnl / isBuyAllowed 가 lazy 로 이미 처리. */
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    public void resetDaily() {
        LocalDate today = today();
        DayState fresh = new DayState(today, 0L, false);
        DayState prev = state.getAndSet(fresh);
        log.info("DailyLossGuard scheduled reset: prev={} new={}", prev, fresh);
    }

    // -------- helpers --------

    /** 날짜 경계가 넘어갔으면 lazy 리셋 후 현재 net 반환. */
    private long freshNetKrw() {
        LocalDate today = today();
        DayState cur = state.get();
        if (today.equals(cur.day())) return cur.netRealizedKrw();
        // 새 날 — CAS 로 한 번만 전이.
        DayState reset = new DayState(today, 0L, false);
        state.compareAndSet(cur, reset);
        return state.get().netRealizedKrw();
    }

    private void sendLimitAlert(long newNet, long limit) {
        emailService.sendHtml(
                "[일일 손실 한도 초과] 신규 매수 차단",
                "<div style='font-family:sans-serif'>"
                + "<h3>당일 실현 손실이 한도를 초과했습니다</h3>"
                + "<p>누적 손익: <b style='color:#B91C1C'>" + String.format("%+,d", newNet) + "원</b></p>"
                + "<p>한도: -" + String.format("%,d", limit) + "원</p>"
                + "<p>추가 매수 주문은 자동 거부됩니다. 내일 00시 KST 자동 리셋.</p>"
                + "</div>");
    }

    /** 불변 day-state. CAS 대상. */
    private record DayState(LocalDate day, long netRealizedKrw, boolean alerted) {}
}
