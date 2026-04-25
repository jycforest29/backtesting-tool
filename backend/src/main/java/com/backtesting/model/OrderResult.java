package com.backtesting.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrderResult {
    private boolean success;
    private String orderNo;       // KRX_FWDG_ORD_ORGNO
    private String orderTime;
    private String message;
    private String rawCode;
    private String rawMessage;
}
