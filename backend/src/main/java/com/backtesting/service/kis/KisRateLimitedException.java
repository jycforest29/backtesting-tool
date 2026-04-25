package com.backtesting.service.kis;

public class KisRateLimitedException extends RuntimeException {
    private final long retryAfterMs;

    public KisRateLimitedException(long retryAfterMs) {
        super("KIS rate limit exceeded, retry after " + retryAfterMs + "ms");
        this.retryAfterMs = retryAfterMs;
    }

    public long getRetryAfterMs() {
        return retryAfterMs;
    }
}
