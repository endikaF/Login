package com.example.login.token;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.example.login.security.TokenHasher;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tokens de un solo uso para verificar el email y recuperar la contraseña (un único mecanismo para ambos).
 *  - 256 bits aleatorios; en BD solo el SHA-256 (si se filtra la tabla, no se pueden usar los enlaces).
 *  - Caducidad corta, controlada por quien lo emite.
 *  - Un solo uso: consumir es un UPDATE atómico "WHERE used_at IS NULL"; dos peticiones simultáneas con el
 *    mismo token no pueden ganar las dos.
 *  - Ligado a un propósito: un token de verificación no sirve para resetear la contraseña ni al revés.
 *  - Emitir uno nuevo anula los anteriores sin usar del mismo usuario y propósito (solo vale el último enlace).
 */
@Service
public class OneTimeTokenService {

    public enum Purpose { VERIFY_EMAIL, RESET_PASSWORD }

    private static final int MAX_TOKEN_LENGTH = 200; // los reales miden 43; evita hashear entradas enormes

    private record Row(long id, long userId, Instant expiresAt, Instant usedAt) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public OneTimeTokenService(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Devuelve el token en claro; es la única vez que existe fuera del hash (va en el enlace del email). */
    public String issue(long userId, Purpose purpose, Duration ttl) {
        String raw = TokenHasher.newToken();
        Instant now = Instant.now();
        tx.executeWithoutResult(status -> {
            invalidateAll(userId, purpose);
            jdbc.update("""
                    INSERT INTO one_time_tokens (user_id, purpose, token_hash, expires_at, created_at)
                    VALUES (:userId, :purpose, :hash, :expiresAt, :now)
                    """,
                    new MapSqlParameterSource()
                            .addValue("userId", userId)
                            .addValue("purpose", purpose.name())
                            .addValue("hash", TokenHasher.sha256Hex(raw))
                            .addValue("expiresAt", now.plus(ttl).toString())
                            .addValue("now", now.toString()));
        });
        return raw;
    }

    /**
     * Consume el token si es válido (existe, mismo propósito, sin usar y sin caducar) y devuelve el id del
     * usuario. Si se llama dentro de una transacción mayor, el "usado" se deshace con ella si algo falla.
     */
    public Optional<Long> consume(String rawToken, Purpose purpose) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }
        Optional<Row> row = jdbc.query("""
                SELECT id, user_id, expires_at, used_at FROM one_time_tokens
                WHERE token_hash = :hash AND purpose = :purpose
                """,
                new MapSqlParameterSource().addValue("hash", TokenHasher.sha256Hex(rawToken))
                        .addValue("purpose", purpose.name()),
                (rs, i) -> new Row(rs.getLong("id"), rs.getLong("user_id"), Instant.parse(rs.getString("expires_at")),
                        rs.getString("used_at") == null ? null : Instant.parse(rs.getString("used_at"))))
                .stream().findFirst();

        Instant now = Instant.now();
        if (row.isEmpty() || row.get().usedAt() != null || !row.get().expiresAt().isAfter(now)) {
            return Optional.empty();
        }
        int updated = jdbc.update("UPDATE one_time_tokens SET used_at = :now WHERE id = :id AND used_at IS NULL",
                new MapSqlParameterSource().addValue("id", row.get().id()).addValue("now", now.toString()));
        return updated == 1 ? Optional.of(row.get().userId()) : Optional.empty();
    }

    public void invalidateAll(long userId, Purpose purpose) {
        jdbc.update("""
                UPDATE one_time_tokens SET used_at = :now
                WHERE user_id = :userId AND purpose = :purpose AND used_at IS NULL
                """,
                new MapSqlParameterSource().addValue("userId", userId).addValue("purpose", purpose.name())
                        .addValue("now", Instant.now().toString()));
    }
}
