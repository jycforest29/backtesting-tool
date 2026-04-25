package com.backtesting.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.security.MessageDigest;
import java.util.*;

/**
 * 부팅 시 SecurityProperties 에서 키를 읽어 constant-time 비교 가능한 형태로 보관.
 *
 * 설계 노트:
 *  - 입력된 API 키는 SHA-256 해시로 저장. 원문은 메모리에 남기지 않는다.
 *  - 조회는 {@link #resolve(String)} — 입력을 해시한 뒤 MessageDigest.isEqual 로 비교 (타이밍 공격 방어).
 *  - 빈(blank) 키 항목은 로드 시 제외된다 — env 미설정으로 인한 accidental-anonymous 방지.
 *  - 중복 키는 거부 (부팅 실패). 운영상 키 로테이션 시 한 쪽을 먼저 제거.
 */
@Slf4j
public final class ApiKeyRegistry {

    public record Principal(String name, List<GrantedAuthority> authorities) {}

    private final Map<String, Principal> byHash;

    public ApiKeyRegistry(List<SecurityProperties.ApiKey> keys) {
        Map<String, Principal> map = new HashMap<>();
        int skipped = 0;
        for (SecurityProperties.ApiKey k : keys) {
            if (k.getKey() == null || k.getKey().isBlank()) { skipped++; continue; }
            if (k.getRoles() == null || k.getRoles().isEmpty()) {
                throw new IllegalStateException(
                        "API key principal=" + k.getPrincipal() + " has no roles — refusing to start");
            }
            String hash = sha256(k.getKey());
            if (map.containsKey(hash)) {
                throw new IllegalStateException(
                        "Duplicate API key configured for principals "
                                + map.get(hash).name() + " and " + k.getPrincipal());
            }
            List<GrantedAuthority> authorities = k.getRoles().stream()
                    .map(r -> (GrantedAuthority) new SimpleGrantedAuthority(r.authority()))
                    .toList();
            map.put(hash, new Principal(k.getPrincipal(), authorities));
        }
        this.byHash = Map.copyOf(map);
        log.info("ApiKeyRegistry loaded {} key(s) ({} blank entries skipped)", byHash.size(), skipped);
    }

    /** @return non-null if the presented key is known. */
    public Optional<Principal> resolve(String presented) {
        if (presented == null || presented.isBlank()) return Optional.empty();
        String hash = sha256(presented);
        // 일단 get() 으로 후보 하나를 뽑고, equal 검사는 MessageDigest.isEqual 로 수행해 타이밍 방어.
        Principal candidate = byHash.get(hash);
        if (candidate == null) return Optional.empty();
        // Map get 은 equals 로 끝났지만, 선형 스캔 경로에서도 timing-safe 하도록 전 엔트리에 isEqual 호출.
        byte[] presentedHash = hashBytes(presented);
        boolean match = false;
        for (String storedHex : byHash.keySet()) {
            if (MessageDigest.isEqual(hexToBytes(storedHex), presentedHash)) match = true;
        }
        return match ? Optional.of(candidate) : Optional.empty();
    }

    public int size() { return byHash.size(); }

    private static String sha256(String s) {
        byte[] b = hashBytes(s);
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] hashBytes(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
