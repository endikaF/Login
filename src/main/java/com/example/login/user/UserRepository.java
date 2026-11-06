package com.example.login.user;

import java.time.Instant;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Acceso a datos solo con JDBC y parámetros con nombre: el SQL nunca se construye concatenando entrada. */
@Repository
public class UserRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public UserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsByEmail(String email) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = :email",
                new MapSqlParameterSource("email", email), Integer.class);
        return count != null && count > 0;
    }

    public Optional<User> findByEmail(String email) {
        return findOne("WHERE email = :value", email);
    }

    public Optional<User> findById(long id) {
        return findOne("WHERE id = :value", id);
    }

    // El fragmento WHERE es siempre un literal del código; el valor viaja como parámetro.
    private Optional<User> findOne(String where, Object value) {
        return jdbc.query("""
                SELECT id, email, password_hash, role, enabled, email_verified, token_version, locked_until
                FROM users
                """ + where,
                new MapSqlParameterSource("value", value),
                (rs, i) -> new User(rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"),
                        Role.valueOf(rs.getString("role")), rs.getInt("enabled") == 1,
                        rs.getInt("email_verified") == 1, rs.getLong("token_version"),
                        rs.getString("locked_until") == null ? null : Instant.parse(rs.getString("locked_until"))))
                .stream().findFirst();
    }

    /** Suma un fallo y devuelve {fallos acumulados, nivel de bloqueo actual}. Se usa dentro de una transacción. */
    public long[] addFailedAttempt(long userId) {
        var params = new MapSqlParameterSource("id", userId);
        jdbc.update("UPDATE users SET failed_attempts = failed_attempts + 1 WHERE id = :id", params);
        return jdbc.queryForObject("SELECT failed_attempts, lock_level FROM users WHERE id = :id", params,
                (rs, i) -> new long[] {rs.getLong("failed_attempts"), rs.getLong("lock_level")});
    }

    /** Bloquea hasta {@code until}, sube el nivel (para duplicar el siguiente bloqueo) y reinicia el contador. */
    public void lock(long userId, Instant until, long newLevel) {
        jdbc.update("""
                UPDATE users SET locked_until = :until, lock_level = :level, failed_attempts = 0 WHERE id = :id
                """,
                new MapSqlParameterSource().addValue("id", userId).addValue("until", until.toString())
                        .addValue("level", newLevel));
    }

    public void updatePassword(long userId, String passwordHash) {
        jdbc.update("UPDATE users SET password_hash = :hash WHERE id = :id",
                new MapSqlParameterSource().addValue("id", userId).addValue("hash", passwordHash));
    }

    public void setEnabled(long userId, boolean enabled) {
        jdbc.update("UPDATE users SET enabled = :enabled WHERE id = :id",
                new MapSqlParameterSource().addValue("id", userId).addValue("enabled", enabled ? 1 : 0));
    }

    public void setRole(long userId, Role role) {
        jdbc.update("UPDATE users SET role = :role WHERE id = :id",
                new MapSqlParameterSource().addValue("id", userId).addValue("role", role.name()));
    }

    public void markEmailVerified(long userId) {
        jdbc.update("UPDATE users SET email_verified = 1 WHERE id = :id", new MapSqlParameterSource("id", userId));
    }

    /** Login correcto: se olvida todo el historial de fallos y bloqueos. */
    public void clearLockState(long userId) {
        jdbc.update("UPDATE users SET failed_attempts = 0, lock_level = 0, locked_until = NULL WHERE id = :id",
                new MapSqlParameterSource("id", userId));
    }

    /** Invalida de golpe todos los access token del usuario (el filtro JWT compara la versión). */
    public void incrementTokenVersion(long userId) {
        jdbc.update("UPDATE users SET token_version = token_version + 1 WHERE id = :id",
                new MapSqlParameterSource("id", userId));
    }

    /**
     * Inserta un usuario. Un solo INSERT: transacción mínima, el escritor único de SQLite se libera enseguida.
     *
     * @throws EmailAlreadyRegisteredException si el email ya existe (la restricción UNIQUE de la BD es
     *                                         la defensa real frente a registros concurrentes).
     */
    public void insert(String email, String passwordHash, Role role, boolean emailVerified) {
        try {
            jdbc.update("""
                    INSERT INTO users (email, password_hash, role, email_verified, created_at)
                    VALUES (:email, :hash, :role, :verified, :createdAt)
                    """,
                    new MapSqlParameterSource()
                            .addValue("email", email)
                            .addValue("hash", passwordHash)
                            .addValue("role", role.name())
                            .addValue("verified", emailVerified ? 1 : 0)
                            .addValue("createdAt", Instant.now().toString()));
        } catch (DataAccessException e) {
            String cause = e.getMostSpecificCause().getMessage();
            if (cause != null && cause.contains("UNIQUE constraint failed: users.email")) {
                throw new EmailAlreadyRegisteredException();
            }
            throw e;
        }
    }
}
