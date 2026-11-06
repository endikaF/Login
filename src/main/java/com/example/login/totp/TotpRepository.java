package com.example.login.totp;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TotpRepository {

    public record Record(long userId, String secretEnc, boolean enabled, long lastUsedStep) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public TotpRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Record> find(long userId) {
        return jdbc.query("SELECT user_id, secret_enc, enabled, last_used_step FROM user_totp WHERE user_id = :id",
                new MapSqlParameterSource("id", userId),
                (rs, i) -> new Record(rs.getLong("user_id"), rs.getString("secret_enc"),
                        rs.getInt("enabled") == 1, rs.getLong("last_used_step"))).stream().findFirst();
    }

    public boolean isEnabled(long userId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM user_totp WHERE user_id = :id AND enabled = 1",
                new MapSqlParameterSource("id", userId), Integer.class);
        return n != null && n > 0;
    }

    /** Guarda (o sustituye) un secreto PENDIENTE de confirmar. Un setup repetido descarta el anterior sin activar. */
    public void savePending(long userId, String secretEnc) {
        jdbc.update("""
                INSERT INTO user_totp (user_id, secret_enc, enabled, last_used_step, created_at)
                VALUES (:id, :secret, 0, 0, :now)
                ON CONFLICT(user_id) DO UPDATE SET secret_enc = :secret, enabled = 0, last_used_step = 0, created_at = :now
                """,
                new MapSqlParameterSource().addValue("id", userId).addValue("secret", secretEnc)
                        .addValue("now", Instant.now().toString()));
    }

    public void enable(long userId, long step) {
        jdbc.update("UPDATE user_totp SET enabled = 1, last_used_step = :step WHERE user_id = :id",
                new MapSqlParameterSource().addValue("id", userId).addValue("step", step));
    }

    /** Reserva el intervalo de forma atómica: si dos peticiones usan el mismo código a la vez, solo una gana. */
    public boolean claimStep(long userId, long step) {
        return jdbc.update("UPDATE user_totp SET last_used_step = :step WHERE user_id = :id AND last_used_step < :step",
                new MapSqlParameterSource().addValue("id", userId).addValue("step", step)) == 1;
    }

    public void replaceRecoveryCodes(long userId, List<String> hashes) {
        var id = new MapSqlParameterSource("id", userId);
        jdbc.update("DELETE FROM totp_recovery_codes WHERE user_id = :id", id);
        String now = Instant.now().toString();
        for (String hash : hashes) {
            jdbc.update("INSERT INTO totp_recovery_codes (user_id, code_hash, created_at) VALUES (:id, :hash, :now)",
                    new MapSqlParameterSource().addValue("id", userId).addValue("hash", hash).addValue("now", now));
        }
    }

    /** Canje atómico de un código de recuperación (un solo uso). */
    public boolean consumeRecoveryCode(long userId, String hash) {
        return jdbc.update("""
                UPDATE totp_recovery_codes SET used_at = :now
                WHERE user_id = :id AND code_hash = :hash AND used_at IS NULL
                """,
                new MapSqlParameterSource().addValue("id", userId).addValue("hash", hash)
                        .addValue("now", Instant.now().toString())) == 1;
    }

    /** Elimina por completo el segundo factor del usuario (secreto y códigos). */
    public void deleteAll(long userId) {
        var id = new MapSqlParameterSource("id", userId);
        jdbc.update("DELETE FROM totp_recovery_codes WHERE user_id = :id", id);
        jdbc.update("DELETE FROM user_totp WHERE user_id = :id", id);
    }
}
