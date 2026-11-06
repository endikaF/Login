package com.example.login.notes;

import static com.example.login.AuthTestSupport.bearer;
import static com.example.login.AuthTestSupport.createAndLogin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.login.TestDb;
import com.example.login.user.Role;
import com.example.login.user.UserService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Propiedad del recurso: un usuario nunca ve ni toca datos de otro (ni siquiera un administrador). */
@SpringBootTest(properties = {"ADMIN_EMAIL=", "ADMIN_PASSWORD=", "JWT_SECRET=" + TestDb.JWT_SECRET})
@AutoConfigureMockMvc
class NotesOwnershipIntegrationTest {

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDb::newUrl);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService users;

    private String ana;
    private String ben;
    private String admin;

    @BeforeEach
    void seed() throws Exception {
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM users"); // las notas caen por ON DELETE CASCADE
        ana = createAndLogin(mvc, users, "ana@example.com", Role.USER);
        ben = createAndLogin(mvc, users, "ben@example.com", Role.USER);
        admin = createAndLogin(mvc, users, "root@example.com", Role.ADMIN);
    }

    private long createNote(String token, String title) throws Exception {
        String body = mvc.perform(post("/api/notes").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"body\":\"texto privado\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();
    }

    @Test
    void notesEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/notes")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/notes").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\",\"body\":\"y\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/notes/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void userOnlySeesOwnNotes() throws Exception {
        createNote(ana, "nota de ana");
        createNote(ben, "nota de ben");

        mvc.perform(get("/api/notes").header(HttpHeaders.AUTHORIZATION, bearer(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("nota de ana"));
    }

    @Test
    void userCannotDeleteSomeoneElsesNoteAndGetsSame404AsNonexistent() throws Exception {
        long benNote = createNote(ben, "nota de ben");

        String foreign = mvc.perform(delete("/api/notes/" + benNote).header(HttpHeaders.AUTHORIZATION, bearer(ana)))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String missing = mvc.perform(delete("/api/notes/999999").header(HttpHeaders.AUTHORIZATION, bearer(ana)))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        assertThat(foreign).isEqualTo(missing); // no se distingue "es de otro" de "no existe"
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notes WHERE id = ?", Integer.class, benNote)).isEqualTo(1);
    }

    @Test
    void ownerCanDeleteOwnNote() throws Exception {
        long id = createNote(ana, "mía");
        mvc.perform(delete("/api/notes/" + id).header(HttpHeaders.AUTHORIZATION, bearer(ana)))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notes", Integer.class)).isZero();
    }

    @Test
    void adminDoesNotGetAccessToOtherUsersNotes() throws Exception {
        long benNote = createNote(ben, "nota de ben");
        mvc.perform(get("/api/notes").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(delete("/api/notes/" + benNote).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerComesFromTheTokenNeverFromTheRequestBody() throws Exception {
        long benId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'ben@example.com'", Long.class);
        mvc.perform(post("/api/notes").header(HttpHeaders.AUTHORIZATION, bearer(ana))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"intruso\",\"body\":\"x\",\"userId\":" + benId + ",\"user_id\":" + benId + "}"))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT u.email FROM notes n JOIN users u ON u.id = n.user_id", String.class))
                .isEqualTo("ana@example.com");
    }

    @Test
    void invalidNotesAreRejected() throws Exception {
        for (String json : new String[] {
                "{\"title\":\"\",\"body\":\"x\"}",
                "{\"title\":\"" + "t".repeat(101) + "\",\"body\":\"x\"}",
                "{\"title\":\"ok\",\"body\":\"" + "b".repeat(5001) + "\"}",
                "{\"title\":\"ok\"}"}) {
            mvc.perform(post("/api/notes").header(HttpHeaders.AUTHORIZATION, bearer(ana))
                    .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void nonNumericIdIsABadRequestNotAServerError() throws Exception {
        mvc.perform(delete("/api/notes/abc").header(HttpHeaders.AUTHORIZATION, bearer(ana)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Solicitud no válida."));
    }

    @Test
    void sqlInjectionInTitleIsStoredAsPlainText() throws Exception {
        long id = createNote(ana, "x'); DROP TABLE notes;--");
        mvc.perform(get("/api/notes").header(HttpHeaders.AUTHORIZATION, bearer(ana)))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].title").value("x'); DROP TABLE notes;--"));
    }
}
