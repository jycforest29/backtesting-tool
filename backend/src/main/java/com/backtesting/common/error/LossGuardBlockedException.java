package com.backtesting.common.error;

/**
 * 일일 손실 한도 초과로 신규 매수 주문이 차단되었을 때 발생.
 *
 * 트레이딩 도메인의 1차 도메인 예외. HTTP 전송 계층이 아닌 서비스/컨트롤러 경계에서
 * 던지고, 전역 핸들러가 423 LOCKED + ErrorCode.LOSS_GUARD_BLOCKED 로 변환한다.
 *
 * 423 LOCKED 선택 이유: 403 은 권한 문제로 혼동, 429 는 레이트리밋 의미로 오인,
 * 422 는 request unprocessable 이나 이 경우는 요청 자체는 valid 하고 계좌 상태로 인해
 * 일시 차단된 상태이므로 423 이 가장 의미에 부합한다.
 */
public class LossGuardBlockedException extends RuntimeException {

    private final long netRealizedKrw;
    private final long limitKrw;

    public LossGuardBlockedException(long netRealizedKrw, long limitKrw) {
        super(String.format("일일 손실 한도 초과 — 누적 %+,d원, 한도 -%,d원. 자정 리셋.",
                netRealizedKrw, limitKrw));
        this.netRealizedKrw = netRealizedKrw;
        this.limitKrw = limitKrw;
    }

    public long getNetRealizedKrw() { return netRealizedKrw; }
    public long getLimitKrw() { return limitKrw; }
}
