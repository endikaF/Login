package com.example.login.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.example.login.security.TokenHasher;
import com.example.login.user.User;
import com.example.login.user.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refresh tokens opacos con rotación y detección de reutilización.
 * Cada login crea una "familia"; cada refresh consume el token actual y emite otro de la misma familia.
 * Si alguien presenta un token que ya se rotó, o bien lo robaron o bien el cliente está roto: en ambos casos
 * se revoca TODA la familia y el usuario tiene que volver a iniciar sesión.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    public record Rotation(User user, String newToken) {
    }

    private final RefreshTokenRepository tokens;
    private final UserRepository users;
    private final TransactionTemplate tx;
    private final Duration ttl;
    private final Duration maxSession;

    public RefreshTokenService(RefreshTokenRepository tokens, UserRepository users, TransactionTemplate tx,
                               @Value("${app.refresh.ttl-days}") long ttlDays,
                               @Value("${app.refresh.max-session-days}") long maxSessionDays) {
        this.tokens = tokens;
        this.users = users;
        this.tx = tx;
        this.ttl = Duration.ofDays(ttlDays);
        this.maxSession = Duration.ofDays(maxSessionDays);
    }

    public Duration ttl() {
        return ttl;
    }

    /** Nueva sesión (login): crea una familia nueva y devuelve el token en claro, que solo se envía por cookie. */
    public String startSession(long userId) {
        String token = TokenHasher.newToken();
        tokens.insert(userId, UUID.randomUUID().toString(), TokenHasher.sha256Hex(token), Instant.now().plus(ttl));
        return token;
    }

    public Rotation rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }
        var stored = tokens.findByHash(TokenHasher.sha256Hex(rawToken))
                .orElseThrow(InvalidRefreshTokenException::new);

        if (stored.rotatedAt() != null) {
            // Reutilización de un token ya rotado: se invalida la familia entera. (Sin datos sensibles en el log.)
            tokens.revokeFamily(stored.familyId());
            log.warn("Reutilización de refresh token detectada: userId={} familia={}", stored.userId(), stored.familyId());
            throw new RefreshTokenReuseException(stored.userId());
        }
        if (stored.revokedAt() != null || stored.expiresAt().isBefore(Instant.now())) {
            throw new InvalidRefreshTokenException();
        }
        User user = users.findById(stored.userId()).filter(User::enabled).orElse(null);
        if (user == null) {
            tokens.revokeFamily(stored.familyId());
            throw new InvalidRefreshTokenException();
        }
        // Vida absoluta de la sesión: la caducidad deslizante sola permitiría mantener una sesión robada
        // indefinidamente renovándola. Pasado el máximo, hay que volver a introducir la contraseña.
        Instant sessionEnd = tokens.familyStart(stored.familyId()).orElse(Instant.now()).plus(maxSession);
        if (!sessionEnd.isAfter(Instant.now())) {
            tokens.revokeFamily(stored.familyId());
            throw new InvalidRefreshTokenException();
        }
        Instant nextExpiry = Instant.now().plus(ttl);
        if (nextExpiry.isAfter(sessionEnd)) {
            nextExpiry = sessionEnd; // el token nuevo nunca vive más allá del final absoluto de la sesión
        }
        final Instant expiry = nextExpiry;

        String next = TokenHasher.newToken();
        // Transacción corta y solo con las dos escrituras: SQLite tiene un único escritor.
        Boolean rotated = tx.execute(status -> {
            if (!tokens.markRotated(stored.id())) {
                return false; // otra petición lo consumió justo antes: mismo caso que reutilización
            }
            tokens.insert(stored.userId(), stored.familyId(), TokenHasher.sha256Hex(next), expiry);
            return true;
        });
        if (!Boolean.TRUE.equals(rotated)) {
            tokens.revokeFamily(stored.familyId());
            log.warn("Reutilización concurrente de refresh token: userId={} familia={}", stored.userId(), stored.familyId());
            throw new RefreshTokenReuseException(stored.userId());
        }
        return new Rotation(user, next);
    }

    /** Logout: revoca la familia del token presentado (también los ya rotados). Idempotente y silencioso. */
    public Optional<Long> revokeSession(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        var stored = tokens.findByHash(TokenHasher.sha256Hex(rawToken));
        stored.ifPresent(t -> tokens.revokeFamily(t.familyId()));
        return stored.map(RefreshTokenRepository.StoredToken::userId); // para auditar quién cerró sesión
    }

    /** "Cerrar sesión en todos los dispositivos": revoca todos los refresh tokens del usuario. */
    public void revokeAll(long userId) {
        tokens.revokeAllForUser(userId);
    }
}
