package com.backtesting.model.quant;

import com.backtesting.model.AssetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 전략에서 다루는 자산 1개. 한국 계좌로 실매매하므로 KRX 상장 ETF가 기본.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuantAsset {

    @NotBlank
    @Size(max = 20)
    @Pattern(regexp = "^[A-Za-z0-9.\\-^]{1,20}$")
    private String symbol;

    @Size(max = 100)
    private String name;

    @NotNull
    private AssetType market;

    private AssetRole role;

    @Pattern(regexp = "^(NAS|NYS|AMS|TSE|HKS|SHS|SZS)?$")
    private String exchange;

    public enum AssetRole {
        OFFENSIVE,      // VAA/DAA 공격 자산
        DEFENSIVE,      // VAA/DAA 방어 자산
        CANARY,         // DAA 카나리아 (위험 감지용)
        STATIC,         // LAA 정적 자산
        VARIABLE        // LAA 가변 자산 (조건에 따라 전환)
    }
}
