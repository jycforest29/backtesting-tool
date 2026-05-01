package com.backtesting.model;

import lombok.Builder;

@Builder
public record OrderResult(
        boolean success,
        String orderNo,        // KRX_FWDG_ORD_ORGNO
        String orderTime,
        String message,
        String rawCode,
        String rawMessage
) {}
