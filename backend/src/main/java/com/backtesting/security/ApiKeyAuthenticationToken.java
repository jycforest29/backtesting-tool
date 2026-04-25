package com.backtesting.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * 인증 완료된 API 키 주체. 한 번 set 되면 credential 은 버린다 (메모리에 원문 API 키 비보관).
 */
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final String principal;

    public ApiKeyAuthenticationToken(String principal, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        super.setAuthenticated(true);
    }

    @Override public Object getCredentials() { return null; }
    @Override public Object getPrincipal()   { return principal; }
    @Override public String  getName()       { return principal; }
}
