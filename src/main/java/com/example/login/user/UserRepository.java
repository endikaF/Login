package com.example.login.user;

import java.time.Instant;

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
