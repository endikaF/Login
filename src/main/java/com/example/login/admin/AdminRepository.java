package com.example.login.admin;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Vista de lectura para el panel. Selecciona columnas concretas: el hash de la contraseña nunca sale de la BD. */
@Repository
public class AdminRepository {

    public record AdminUserView(long id, String email, String role, boolean enabled, boolean emailVerified,
                                boolean locked, String createdAt, boolean totpEnabled) {
    }

    private final JdbcTemplate jdbc;

    public AdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AdminUserView> findAllUsers() {
        Instant now = Instant.now();
        return jdbc.query("""
                SELECT u.id, u.email, u.role, u.enabled, u.email_verified, u.locked_until, u.created_at,
                       EXISTS (SELECT 1 FROM user_totp t WHERE t.user_id = u.id AND t.enabled = 1) AS totp
                FROM users u ORDER BY u.id
                """,
                (rs, i) -> {
                    String until = rs.getString("locked_until");
                    return new AdminUserView(rs.getLong("id"), rs.getString("email"), rs.getString("role"),
                            rs.getInt("enabled") == 1, rs.getInt("email_verified") == 1,
                            until != null && Instant.parse(until).isAfter(now), rs.getString("created_at"),
                            rs.getInt("totp") == 1);
                });
    }
}
