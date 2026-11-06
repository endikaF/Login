package com.example.login.audit;

import java.time.Instant;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Registro de auditoría en la tabla audit_log: quién (id de usuario, si se conoce), desde dónde (IP) y qué pasó.
 * Nunca se guardan contraseñas, tokens, hashes ni el email que alguien tecleó (puede ser basura o datos
 * personales de un tercero). Los "detalle" son literales fijos del código, no texto del usuario, lo que
 * además impide inyectar líneas falsas en el rastro (log injection).
 * Sirve para investigar incidentes y detectar ataques; es de solo escritura desde la aplicación.
 */
@Service
public class AuditService {

    private final NamedParameterJdbcTemplate jdbc;

    public AuditService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(AuditEvent event, Long userId, String ip, String detail) {
        jdbc.update("""
                INSERT INTO audit_log (ts, event, user_id, ip, detail)
                VALUES (:ts, :event, :userId, :ip, :detail)
                """,
                new MapSqlParameterSource()
                        .addValue("ts", Instant.now().toString())
                        .addValue("event", event.name())
                        .addValue("userId", userId)
                        .addValue("ip", ip)
                        .addValue("detail", detail));
    }
}
