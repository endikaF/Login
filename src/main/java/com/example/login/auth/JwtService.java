package com.example.login.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/**
 * Emite y valida access token JWT firmados con HMAC-SHA256 (HS256).
 * No se implementa ninguna criptografía a mano: firma y verificación las hace Nimbus (vía Spring Security).
 * [TRADE-OFF] HS256 (clave simétrica) es lo más simple para una sola aplicación; con varios servicios
 * se usaría una clave asimétrica (RS256/EdDSA) para que quien verifica no pueda también firmar.
 */
@Service
public class JwtService {

    static final String ISSUER = "login-app";
    static final int MIN_SECRET_LENGTH = 32; // 256 bits: tamaño mínimo recomendado para HS256

    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final Duration accessTtl;

    public JwtService(@Value("${JWT_SECRET:}") String secret,
                      @Value("${app.jwt.access-ttl-minutes}") long accessTtlMinutes) {
        // Fail fast: sin clave (o con una trivial) la app no debe arrancar; nunca hay clave por defecto.
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "JWT_SECRET no definida o demasiado corta (mínimo " + MIN_SECRET_LENGTH + " caracteres).");
        }
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        // macAlgorithm(HS256) fija el algoritmo esperado: rechaza "alg":"none" y la confusión de algoritmos.
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        // Valida caducidad (tolerancia de 5 s por desfase de relojes) y emisor.
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ofSeconds(5)), new JwtIssuerValidator(ISSUER)));
        this.accessTtl = Duration.ofMinutes(accessTtlMinutes);
    }

    public Duration accessTtl() {
        return accessTtl;
    }

    public String issue(long userId, long tokenVersion) {
        return issue(userId, tokenVersion, Instant.now());
    }

    // Visible para que los tests puedan fabricar tokens ya caducados.
    String issue(long userId, long tokenVersion, Instant issuedAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(Long.toString(userId))
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(accessTtl))
                .id(UUID.randomUUID().toString())
                // "ver" se compara con users.token_version en cada petición. El rol NO va en el token:
                // se lee de la BD, así un cambio de rol o una desactivación surten efecto al instante.
                .claim("ver", tokenVersion)
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    /** @throws org.springframework.security.oauth2.jwt.JwtException si la firma, caducidad o emisor fallan. */
    public Jwt decode(String token) {
        return decoder.decode(token);
    }
}
