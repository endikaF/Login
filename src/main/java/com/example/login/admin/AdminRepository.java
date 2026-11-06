package com.example.login.admin;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Vista de lectura para el panel. Selecciona columnas concretas: el hash de la contraseña nunca sale de la BD. */
@Repository
public class AdminRepository {

    public record AdminUserView(long id, String email, String role, boolean enabled, boolean emailVerified,
                                boolean locked, String createdAt) {
    }

    private final JdbcTemplate jdbc;

    public AdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AdminUserView> findAllUsers() {
        Instant now = Instant.now();
        return jdbc.query("""
                SELECT id, email, role, enabled, email_verified, locked_until, created_at FROM users ORDER BY id
                """,
                (rs, i) -> {
                    String until = rs.getString("locked_until");
                    return new AdminUserView(rs.getLong("id"), rs.getString("email"), rs.getString("role"),
                            rs.getInt("enabled") == 1, rs.getInt("email_verified") == 1,
                            until != null && Instant.parse(until).isAfter(now), rs.getString("created_at"));
                });
    }
}
