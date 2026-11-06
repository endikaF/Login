package com.example.login.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class LoginAttemptServiceTest {

    private final LoginAttemptService service = new LoginAttemptService(null, null, 5, 1, 30);

    @Test
    void lockDurationDoublesEachLevel() {
        assertThat(service.lockDuration(0)).isEqualTo(Duration.ofMinutes(1));
        assertThat(service.lockDuration(1)).isEqualTo(Duration.ofMinutes(2));
        assertThat(service.lockDuration(2)).isEqualTo(Duration.ofMinutes(4));
        assertThat(service.lockDuration(4)).isEqualTo(Duration.ofMinutes(16));
    }

    @Test
    void lockDurationIsCapped() {
        assertThat(service.lockDuration(5)).isEqualTo(Duration.ofMinutes(30));
        assertThat(service.lockDuration(1_000)).isEqualTo(Duration.ofMinutes(30)); // sin desbordamiento
    }
}
