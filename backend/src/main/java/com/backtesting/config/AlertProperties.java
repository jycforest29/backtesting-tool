package com.backtesting.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "alert")
public class AlertProperties {
    private String recipient;
    private Scanner scanner = new Scanner();
    private DailyLoss dailyLoss = new DailyLoss();
    private Dart dart = new Dart();

    @Data
    public static class Scanner {
        private double retailSellMultiplier = 2.3;
        private double prevDayMultiplier = 1.5;
        private long minTurnoverBillion = 100;
        private int minCriteriaPassed = 2;
        private int topN = 3;
        private int candidatePoolSize = 50;
    }

    @Data
    public static class DailyLoss {
        private long limitKrw = 30_000;
    }

    @Data
    public static class Dart {
        private String apiKey;
        private int maxItems = 15;
    }

    private VolumeSpike volumeSpike = new VolumeSpike();

    @Data
    public static class VolumeSpike {
        private boolean enabled = true;
        private int scanIntervalSeconds = 120;
        private int recentMinutes = 1;
        private int compareMinutes = 5;
        private double thresholdPercent = 300;
        private int cooldownMinutes = 10;
    }
}
