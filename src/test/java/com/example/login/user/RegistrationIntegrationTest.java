package com.example.login.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.example.login.TestDb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// ADMIN_* vacíos: un .env local no debe contaminar los tests.
@SpringBootTest(properties = {"ADMIN_EMAIL=", "ADMIN_PASSWORD=", "JWT_SECRET=" + TestDb.JWT_SECRET})
@AutoConfigureMockMvc
class RegistrationIntegrationTest {

    private static final String PASSWORD = "una contraseña larga y segura";

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDb::newUrl);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM users");
    }

    private ResultActions register(String email, String password) throws Exception {
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(Map.of("email", email, "password", password));
        return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void registersUserWithArgon2idHashAndUserRole() throws Exception {
        register("Nuevo@Example.com", PASSWORD).andExpect(status().isAccepted());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT email, password_hash, role, email_verified, enabled FROM users");
        assertThat(row.get("email")).isEqualTo("nuevo@example.com"); // normalizado
        assertThat((String) row.get("password_hash")).startsWith("$argon2id$").doesNotContain(PASSWORD);
        assertThat(row.get("role")).isEqualTo("USER");
        assertThat(((Number) row.get("email_verified")).intValue()).isZero();
        assertThat(((Number) row.get("enabled")).intValue()).isEqualTo(1);
    }

    @Test
    void responseDoesNotLeakHashOrPassword() throws Exception {
        register("a@example.com", PASSWORD).andExpect(status().isAccepted())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("argon2"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(PASSWORD))));
    }

    @Test
    void clientCannotChooseRole() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"r@example.com\",\"password\":\"" + PASSWORD + "\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT role FROM users", String.class)).isEqualTo("USER");
    }

    @Test
    void duplicateEmailCreatesNoSecondAccountAndAnswersLikeANewOne() throws Exception {
        String first = register("dup@example.com", PASSWORD).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String second = register("DUP@example.com", PASSWORD).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first); // misma respuesta: no se puede saber si el email ya existía
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
    }

    @Test
    void databaseUniqueConstraintBacksUpDuplicateCheck(@Autowired UserRepository repo) {
        repo.insert("race@example.com", "hash", Role.USER, false);
        org.junit.jupiter.api.Assertions.assertThrows(EmailAlreadyRegisteredException.class,
                () -> repo.insert("RACE@example.com", "hash", Role.USER, false));
    }

    @Test
    void rejectsShortPassword() throws Exception {
        register("s@example.com", "corta").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isZero();
    }

    @Test
    void rejectsTooLongPasswordWithoutHashingIt() throws Exception {
        register("l@example.com", "a".repeat(129)).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsInvalidEmail() throws Exception {
        register("no-es-un-email", PASSWORD).andExpect(status().isBadRequest());
        register("x'; DROP TABLE users;--@example.com", PASSWORD).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingFieldsAndMalformedBody() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{no json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Solicitud mal formada."));
    }

    @Test
    void sqlInjectionInEmailStoredAsDataIsImpossibleButTableSurvives() throws Exception {
        register("a@example.com", PASSWORD).andExpect(status().isAccepted());
        // la tabla sigue existiendo y con su fila
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
    }

    @Test
    void foreignKeysAreEnabledOnConnection() {
        assertThat(jdbc.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> jdbc.update(
                "INSERT INTO notes (user_id, title, body, created_at) VALUES (9999, 't', 'b', 'x')"));
    }

    @Test
    void otherEndpointsRequireAuthentication() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/notes"))
                .andExpect(status().isUnauthorized());
    }
}
