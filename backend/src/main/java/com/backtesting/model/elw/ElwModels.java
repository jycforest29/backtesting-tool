package com.backtesting.model.elw;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * ELW 스큐 스캐너 도메인 모델. 모든 결과 행은 freshness(asOf) + status 를 달고 전파 —
 * 실패/스테일 데이터를 조용히 숨기지 않는다.
 */
public final class ElwModels {

    private ElwModels() {}

    public enum OptionType { CALL, PUT }

    public record ElwContract(
            String symbol,
            String name,
            String underlyingCode,
            String underlyingName,
            OptionType type,
            double strike,
            LocalDate expiry,
            double marketPrice,
            String issuer,
            Instant asOf
    ) {}

    public record ElwIvRow(
            String symbol,
            String name,
            OptionType type,
            double strike,
            LocalDate expiry,
            Double iv,
            String ivStatus,
            Integer solverIterations,
            double underlyingPrice,
            double marketPrice,
            Instant asOf,
            String ivNote
    ) {}

    public record SkewPoint(
            LocalDate expiry,
            double strike,
            Double callIv,
            Double putIv,
            Double skew
    ) {}

    public record ElwSkewResponse(
            String underlyingCode,
            String circuitState,
            int contractsFetched,
            int ivConverged,
            int ivFailed,
            List<ElwIvRow> rows,
            List<SkewPoint> skew,
            Instant generatedAt,
            String degradedReason
    ) {}
}
