package com.example.login.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RateLimitServiceTest {

    private final RateLimitService limiter = new RateLimitService(3, 2, 1, 5, 2, 4, 100);

    @Test
    void allowsUpToCapacityThenRejectsWithRetryAfter() {
        for (int i = 0; i < 3; i++) {
            limiter.checkLoginIp("1.1.1.1");
        }
        assertThatThrownBy(() -> limiter.checkLoginIp("1.1.1.1"))
                .isInstanceOfSatisfying(RateLimitService.RateLimitedException.class,
                        e -> assertThat(e.retryAfterSeconds()).isBetween(1L, 60L));
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < 3; i++) {
            limiter.checkLoginIp("1.1.1.1");
        }
        assertThatCode(() -> limiter.checkLoginIp("2.2.2.2")).doesNotThrowAnyException();
    }

    @Test
    void limitersAreIndependentPerPurpose() {
        limiter.checkRegisterIp("1.1.1.1");
        assertThatThrownBy(() -> limiter.checkRegisterIp("1.1.1.1")).isInstanceOf(RateLimitService.RateLimitedException.class);
        assertThatCode(() -> limiter.checkLoginIp("1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void trackedKeysAreBounded() {
        // Con tope de 100 claves, inundar con claves inventadas no hace crecer la memoria indefinidamente
        // y el servicio sigue funcionando.
        RateLimitService small = new RateLimitService(3, 2, 1, 5, 2, 4, 100);
        for (int i = 0; i < 10_000; i++) {
            small.checkLoginAccount("inventado" + i + "@example.com");
        }
        assertThatCode(() -> small.checkLoginAccount("otro@example.com")).doesNotThrowAnyException();
    }
}
