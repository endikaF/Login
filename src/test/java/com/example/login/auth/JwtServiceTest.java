package com.example.login.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Base64;

import com.example.login.TestDb;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtServiceTest {

    private final JwtService jwt = new JwtService(TestDb.JWT_SECRET, 10);

    @Test
    void roundTripKeepsSubjectAndVersion() {
        var decoded = jwt.decode(jwt.issue(42, 3));
        assertThat(decoded.getSubject()).isEqualTo("42");
        assertThat(((Number) decoded.getClaim("ver")).longValue()).isEqualTo(3);
        assertThat(decoded.getClaimAsString("iss")).isEqualTo("login-app");
        assertThat(decoded.getId()).isNotBlank();
    }

    @Test
    void expiredTokenIsRejected() {
        String old = jwt.issue(1, 0, Instant.now().minusSeconds(3600));
        assertThatThrownBy(() -> jwt.decode(old)).isInstanceOf(JwtException.class);
    }

    @Test
    void tamperedPayloadIsRejected() {
        String[] parts = jwt.issue(1, 0).split("\\.");
        String forged = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"999\",\"iss\":\"login-app\",\"ver\":0}".getBytes());
        assertThatThrownBy(() -> jwt.decode(parts[0] + "." + forged + "." + parts[2]))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        String foreign = new JwtService("otra-clave-distinta-de-sobra-0123456789abc", 10).issue(1, 0);
        assertThatThrownBy(() -> jwt.decode(foreign)).isInstanceOf(JwtException.class);
    }

    @Test
    void algNoneTokenIsRejected() {
        var enc = Base64.getUrlEncoder().withoutPadding();
        String unsigned = enc.encodeToString("{\"alg\":\"none\"}".getBytes()) + "."
                + enc.encodeToString(("{\"sub\":\"1\",\"iss\":\"login-app\",\"ver\":0,\"exp\":"
                + (Instant.now().getEpochSecond() + 600) + "}").getBytes()) + ".";
        assertThatThrownBy(() -> jwt.decode(unsigned)).isInstanceOf(JwtException.class);
    }

    @Test
    void garbageIsRejected() {
        assertThatThrownBy(() -> jwt.decode("no-es-un-jwt")).isInstanceOf(JwtException.class);
    }

    @Test
    void refusesToStartWithMissingOrShortSecret() {
        assertThatThrownBy(() -> new JwtService("", 10)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtService("corta", 10)).isInstanceOf(IllegalStateException.class);
    }
}
