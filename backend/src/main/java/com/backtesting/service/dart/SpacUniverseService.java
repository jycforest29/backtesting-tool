package com.backtesting.service.dart;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * KRX 상장 SPAC 동적 유니버스.
 * DartCorpCodeService가 로드한 전체 상장기업 이름에서 "스팩" 패턴을 필터.
 * SPAC은 상장·합병·상장폐지가 잦아 하드코딩이 부적절 → 동적 발굴.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpacUniverseService {

    private final DartCorpCodeService corpCodeService;

    private volatile List<SpacEntry> cached;
    private volatile Map<String, SpacEntry> cachedIndex;
    private final ReentrantLock lock = new ReentrantLock();

    public record SpacEntry(String stockCode, String corpCode, String name) {}

    public List<SpacEntry> listSpacs() {
        ensureLoaded();
        return cached;
    }

    /** stockCode로 SPAC 엔트리 조회 (corpCode 필요 시 사용). */
    public SpacEntry find(String stockCode) {
        ensureLoaded();
        return cachedIndex.get(stockCode);
    }

    public void forceReload() {
        lock.lock();
        try {
            cached = null;
            cachedIndex = null;
        } finally {
            lock.unlock();
        }
    }

    private void ensureLoaded() {
        if (cached != null) return;
        lock.lock();
        try {
            if (cached != null) return;
            List<SpacEntry> out = new ArrayList<>();
            Map<String, SpacEntry> idx = new HashMap<>();
            for (DartCorpCodeService.CorpEntry e : corpCodeService.listAll()) {
                if (e.name() == null || !e.name().contains("스팩")) continue;
                SpacEntry s = new SpacEntry(e.stockCode(), e.corpCode(), e.name());
                out.add(s);
                idx.put(s.stockCode(), s);
            }
            cached = List.copyOf(out);
            cachedIndex = Map.copyOf(idx);
            log.info("SPAC universe discovered: {} tickers", cached.size());
        } finally {
            lock.unlock();
        }
    }
}
