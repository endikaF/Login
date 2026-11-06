package com.example.login.auth;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.example.login.audit.AuditEvent;
import com.example.login.audit.AuditService;
import com.example.login.security.LoginAttemptService;
import com.example.login.security.PasswordPolicy;
import com.example.login.security.RateLimitService;
import com.example.login.user.User;
import com.example.login.user.UserRepository;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    /** Resultado de login/refresh: access token (JSON) y refresh token (solo cookie). */
    public record Tokens(String accessToken, long expiresInSeconds, String refreshToken) {
    }

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final RefreshTokenService refreshTokens;
    private final RateLimitService rateLimit;
    private final LoginAttemptService attempts;
    private final AuditService audit;
    /**
     * Hash Argon2id de una contraseña que nadie conoce. Se compara cuando el usuario NO existe para que el
     * login tarde aproximadamente lo mismo exista o no la cuenta (evita enumerar usuarios midiendo tiempos).
     */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt,
                       RefreshTokenService refreshTokens, RateLimitService rateLimit,
                       LoginAttemptService attempts, AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.rateLimit = rateLimit;
        this.attempts = attempts;
        this.audit = audit;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    public Tokens login(String email, String rawPassword, String ip) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);

        // 1) Rate limiting ANTES de tocar la BD o Argon2: es lo más barato y protege los recursos caros.
        //    Se aplica por IP y por cuenta (exista o no), así no revela qué emails están registrados.
        try {
            rateLimit.checkLoginIp(ip);
            rateLimit.checkLoginAccount(normalized);
        } catch (RateLimitService.RateLimitedException e) {
            audit.record(AuditEvent.RATE_LIMITED, null, ip, "login");
            throw e;
        }

        // Contraseñas absurdamente largas: se descartan sin hashear (anti-DoS). Misma respuesta genérica.
        if (rawPassword == null || rawPassword.length() > PasswordPolicy.MAX_LENGTH) {
            audit.record(AuditEvent.LOGIN_FAILED, null, ip, "invalid_input");
            throw new InvalidCredentialsException();
        }
        User user = users.findByEmail(normalized).orElse(null);

        // Siempre se ejecuta una verificación Argon2, exista el usuario, esté bloqueado o no.
        boolean passwordOk = encoder.matches(rawPassword, user != null ? user.passwordHash() : dummyHash);

        if (user == null) {
            audit.record(AuditEvent.LOGIN_FAILED, null, ip, "unknown_user");
            throw new InvalidCredentialsException();
        }
        // 2) Cuenta bloqueada: se rechaza AUNQUE la contraseña sea correcta (si no, el bloqueo no serviría de nada)
        //    y con la misma respuesta genérica, para no revelar que la cuenta existe ni que está bloqueada.
        if (user.isLocked(Instant.now())) {
            audit.record(AuditEvent.LOGIN_BLOCKED, user.id(), ip, "account_locked");
            throw new InvalidCredentialsException();
        }
        // 3) Contraseña incorrecta: suma fallo y, si toca, bloquea.
        if (!passwordOk) {
            boolean lockedNow = attempts.recordFailure(user.id());
            audit.record(AuditEvent.LOGIN_FAILED, user.id(), ip, "bad_password");
            if (lockedNow) {
                audit.record(AuditEvent.ACCOUNT_LOCKED, user.id(), ip, "too_many_failures");
            }
            throw new InvalidCredentialsException();
        }
        if (!user.enabled()) {
            audit.record(AuditEvent.LOGIN_FAILED, user.id(), ip, "account_disabled");
            throw new InvalidCredentialsException();
        }

        attempts.recordSuccess(user.id());
        audit.record(AuditEvent.LOGIN_OK, user.id(), ip, null);
        return new Tokens(jwt.issue(user.id(), user.tokenVersion()), jwt.accessTtl().toSeconds(),
                refreshTokens.startSession(user.id()));
    }

    public Tokens refresh(String refreshToken, String ip) {
        try {
            var rotation = refreshTokens.rotate(refreshToken);
            User user = rotation.user();
            return new Tokens(jwt.issue(user.id(), user.tokenVersion()), jwt.accessTtl().toSeconds(),
                    rotation.newToken());
        } catch (RefreshTokenReuseException e) {
            // Evento de seguridad crítico: alguien usó un token ya rotado. La familia ya está revocada.
            audit.record(AuditEvent.TOKEN_REUSE, e.userId(), ip, "family_revoked");
            throw e;
        }
    }

    public void logout(String refreshToken, String ip) {
        refreshTokens.revokeSession(refreshToken)
                .ifPresent(userId -> audit.record(AuditEvent.LOGOUT, userId, ip, null));
    }

    /**
     * Cierre global: revoca todos los refresh tokens y sube token_version, con lo que los access token
     * ya emitidos (aún no caducados) también dejan de valer en la siguiente petición.
     */
    public void logoutAll(long userId, String ip) {
        refreshTokens.revokeAll(userId);
        users.incrementTokenVersion(userId);
        audit.record(AuditEvent.LOGOUT_ALL, userId, ip, null);
    }
}
