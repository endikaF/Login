package com.example.login.notes;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Notas privadas. La PROPIEDAD se impone aquí, en la propia consulta: toda lectura y borrado lleva
 * "AND user_id = :userId", y ese id sale del token ya verificado, nunca de datos que envíe el cliente.
 * Así, aunque el cliente adivine el id de una nota ajena, la base de datos simplemente no la encuentra
 * (previene IDOR / "Broken Object Level Authorization", el fallo nº 1 de la OWASP API Top 10).
 */
@Repository
public class NoteRepository {

    public record Note(long id, String title, String body, String createdAt) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public NoteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Note> findAllByOwner(long userId) {
        return jdbc.query("""
                SELECT id, title, body, created_at FROM notes WHERE user_id = :userId ORDER BY id DESC
                """,
                new MapSqlParameterSource("userId", userId),
                (rs, i) -> new Note(rs.getLong("id"), rs.getString("title"), rs.getString("body"),
                        rs.getString("created_at")));
    }

    public Note insert(long userId, String title, String body) {
        String now = Instant.now().toString();
        jdbc.update("""
                INSERT INTO notes (user_id, title, body, created_at) VALUES (:userId, :title, :body, :now)
                """,
                new MapSqlParameterSource().addValue("userId", userId).addValue("title", title)
                        .addValue("body", body).addValue("now", now));
        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", new MapSqlParameterSource(), Long.class);
        return new Note(id, title, body, now);
    }

    /** @return true si la nota existía Y era del usuario; false en cualquier otro caso (sin distinguirlos). */
    public boolean deleteByOwner(long id, long userId) {
        return jdbc.update("DELETE FROM notes WHERE id = :id AND user_id = :userId",
                new MapSqlParameterSource().addValue("id", id).addValue("userId", userId)) == 1;
    }
}
