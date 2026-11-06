package com.example.login.auth;

import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistencia de refresh tokens. Solo se guarda el hash SHA-256; el token en claro nunca toca la BD. */
@Repository
public class RefreshTokenRepository {

    public record StoredToken(long id, long userId, String familyId, Instant expiresAt,
                              Instant rotatedAt, Instant revokedAt) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public RefreshTokenRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long userId, String familyId, String tokenHash, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO refresh_tokens (user_id, family_id, token_hash, expires_at, created_at)
                VALUES (:userId, :familyId, :hash, :expiresAt, :now)
                """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("familyId", familyId)
                        .addValue("hash", tokenHash)
                        .addValue("expiresAt", expiresAt.toString())
                        .addValue("now", Instant.now().toString()));
    }

    public Optional<StoredToken> findByHash(String tokenHash) {
        return jdbc.query("""
                SELECT id, user_id, family_id, expires_at, rotated_at, revoked_at
                FROM refresh_tokens WHERE token_hash = :hash
                """,
                new MapSqlParameterSource("hash", tokenHash),
                (rs, i) -> new StoredToken(rs.getLong("id"), rs.getLong("user_id"), rs.getString("family_id"),
                        Instant.parse(rs.getString("expires_at")), parse(rs.getString("rotated_at")),
                        parse(rs.getString("revoked_at")))).stream().findFirst();
    }

    /**
     * Marca el token como usado, de forma atómica: el UPDATE solo afecta a la fila si nadie lo rotó
     * ni revocó antes. Devuelve false si otra petición se adelantó (dos usos del mismo token = reutilización).
     */
    public boolean markRotated(long id) {
        return jdbc.update("""
                UPDATE refresh_tokens SET rotated_at = :now
                WHERE id = :id AND rotated_at IS NULL AND revoked_at IS NULL
                """,
                new MapSqlParameterSource().addValue("id", id).addValue("now", Instant.now().toString())) == 1;
    }

    public void revokeFamily(String familyId) {
        jdbc.update("UPDATE refresh_tokens SET revoked_at = :now WHERE family_id = :family AND revoked_at IS NULL",
                new MapSqlParameterSource().addValue("family", familyId).addValue("now", Instant.now().toString()));
    }

    public void revokeAllForUser(long userId) {
        jdbc.update("UPDATE refresh_tokens SET revoked_at = :now WHERE user_id = :userId AND revoked_at IS NULL",
                new MapSqlParameterSource().addValue("userId", userId).addValue("now", Instant.now().toString()));
    }

    private static Instant parse(String value) {
        return value == null ? null : Instant.parse(value);
    }
}
