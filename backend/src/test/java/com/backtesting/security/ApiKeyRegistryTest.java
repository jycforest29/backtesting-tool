package com.backtesting.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyRegistryTest {

    @Test
    void blankKeysAreSkipped() {
        SecurityProperties.ApiKey blank = key("", "ghost", SecurityProperties.Role.VIEWER);
        SecurityProperties.ApiKey valid = key("secret-123", "viewer", SecurityProperties.Role.VIEWER);
        ApiKeyRegistry reg = new ApiKeyRegistry(List.of(blank, valid));
        assertThat(reg.size()).isEqualTo(1);
    }

    @Test
    void resolveReturnsPrincipalForKnownKey() {
        ApiKeyRegistry reg = new ApiKeyRegistry(List.of(
                key("alpha-key", "viewer", SecurityProperties.Role.VIEWER),
                key("beta-key",  "trader", SecurityProperties.Role.TRADER, SecurityProperties.Role.VIEWER)));

        Optional<ApiKeyRegistry.Principal> p = reg.resolve("beta-key");
        assertThat(p).isPresent();
        assertThat(p.get().name()).isEqualTo("trader");
        assertThat(p.get().authorities()).extracting("authority")
                .containsExactlyInAnyOrder("ROLE_TRADER", "ROLE_VIEWER");
    }

    @Test
    void resolveReturnsEmptyForUnknownKey() {
        ApiKeyRegistry reg = new ApiKeyRegistry(List.of(
                key("alpha", "viewer", SecurityProperties.Role.VIEWER)));
        assertThat(reg.resolve("wrong")).isEmpty();
        assertThat(reg.resolve(null)).isEmpty();
        assertThat(reg.resolve("")).isEmpty();
        assertThat(reg.resolve(" ")).isEmpty();
    }

    @Test
    void duplicateKeysAreRejected() {
        SecurityProperties.ApiKey a = key("same", "one", SecurityProperties.Role.VIEWER);
        SecurityProperties.ApiKey b = key("same", "two", SecurityProperties.Role.ADMIN);
        assertThatThrownBy(() -> new ApiKeyRegistry(List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void emptyRolesAreRejected() {
        SecurityProperties.ApiKey k = key("k", "p");  // no roles
        assertThatThrownBy(() -> new ApiKeyRegistry(List.of(k)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no roles");
    }

    private static SecurityProperties.ApiKey key(String keyValue, String principal, SecurityProperties.Role... roles) {
        SecurityProperties.ApiKey k = new SecurityProperties.ApiKey();
        k.setKey(keyValue);
        k.setPrincipal(principal);
        k.setRoles(roles.length == 0 ? null : Set.of(roles));
        return k;
    }
}
