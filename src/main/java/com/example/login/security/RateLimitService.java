package com.example.login.security;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Rate limiting con Bucket4j (algoritmo "token bucket"): cada clave (IP o cuenta) tiene un cubo con N fichas
 * que se rellenan poco a poco; cada intento gasta una y, si no quedan, se responde 429.
 * Frena la fuerza bruta y el abuso de endpoints costosos (Argon2 consume CPU y memoria).
 * [TRADE-OFF] Todo vive en memoria del proceso: simple, pero se pierde al reiniciar y no se comparte entre
 * instancias. En producción: Redis/JCache con Bucket4j, o rate limiting en el proxy/gateway.
 * [TRADE-OFF] Se usa getRemoteAddr() y no X-Forwarded-For: esa cabecera la puede falsear cualquiera salvo que
 * un proxy de confianza la reescriba. Detrás de un proxy habría que configurarlo explícitamente.
 */
@Service
public class RateLimitService {

    public static class RateLimitedException extends RuntimeException {
        private final long retryAfterSeconds;

        public RateLimitedException(long retryAfterSeconds) {
            super("rate limited");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    /** Mapa de cubos con tope de tamaño (LRU): un atacante no puede agotar la memoria inventando claves. */
    private static final class KeyedLimiter {
        private final Map<String, Bucket> buckets;
        private final long capacity;
        private final Duration period;

        KeyedLimiter(long capacity, Duration period, int maxKeys) {
            this.capacity = capacity;
            this.period = period;
            this.buckets = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                    return size() > maxKeys;
                }
            });
        }

        void consume(String key) {
            Bucket bucket = buckets.computeIfAbsent(key, k -> Bucket.builder()
                    .addLimit(Bandwidth.builder().capacity(capacity).refillGreedy(capacity, period).build())
                    .build());
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            if (!probe.isConsumed()) {
                // Redondeo hacia arriba: nunca se anuncia un tiempo de espera menor que el real.
                throw new RateLimitedException(Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L));
            }
        }
    }

    private final KeyedLimiter loginIp;
    private final KeyedLimiter loginAccount;
    private final KeyedLimiter registerIp;
    private final KeyedLimiter mailIp;
    private final KeyedLimiter mailAccount;
    private final KeyedLimiter tokenIp;

    public RateLimitService(@Value("${app.ratelimit.login-ip-per-minute}") long loginIpPerMinute,
                            @Value("${app.ratelimit.login-account-per-15min}") long loginAccountPer15Min,
                            @Value("${app.ratelimit.register-ip-per-hour}") long registerIpPerHour,
                            @Value("${app.ratelimit.mail-ip-per-hour}") long mailIpPerHour,
                            @Value("${app.ratelimit.mail-account-per-hour}") long mailAccountPerHour,
                            @Value("${app.ratelimit.token-ip-per-minute}") long tokenIpPerMinute,
                            @Value("${app.ratelimit.max-tracked-keys}") int maxKeys) {
        this.loginIp = new KeyedLimiter(loginIpPerMinute, Duration.ofMinutes(1), maxKeys);
        this.loginAccount = new KeyedLimiter(loginAccountPer15Min, Duration.ofMinutes(15), maxKeys);
        this.registerIp = new KeyedLimiter(registerIpPerHour, Duration.ofHours(1), maxKeys);
        this.mailIp = new KeyedLimiter(mailIpPerHour, Duration.ofHours(1), maxKeys);
        this.mailAccount = new KeyedLimiter(mailAccountPerHour, Duration.ofHours(1), maxKeys);
        this.tokenIp = new KeyedLimiter(tokenIpPerMinute, Duration.ofMinutes(1), maxKeys);
    }

    /** Solicitudes de correo (recuperar contraseña, reenviar verificación) por IP: frena el envío masivo. */
    public void checkMailIp(String ip) {
        mailIp.consume(ip);
    }

    /** Por destinatario (exista o no): impide inundar de correos a una víctima. */
    public void checkMailAccount(String normalizedEmail) {
        mailAccount.consume(normalizedEmail);
    }

    /** Canje de enlaces (verificar/resetear) por IP: el reset ejecuta Argon2, que es costoso. */
    public void checkTokenIp(String ip) {
        tokenIp.consume(ip);
    }

    public void checkLoginIp(String ip) {
        loginIp.consume(ip);
    }

    /** La clave es el email normalizado exista o no la cuenta: así el comportamiento no revela si existe. */
    public void checkLoginAccount(String normalizedEmail) {
        loginAccount.consume(normalizedEmail);
    }

    public void checkRegisterIp(String ip) {
        registerIp.consume(ip);
    }
}
