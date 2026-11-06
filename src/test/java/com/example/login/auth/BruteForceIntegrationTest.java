package com.example.login.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.example.login.TestDb;
import com.example.login.user.Role;
import com.example.login.user.UserService;

import com.fasterxml.jackson.databind.ObjectMapper;

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
import org.springframework.test.web.servlet.MvcResult;

/** Bloqueo progresivo de cuentas (los límites de rate limiting son holgados en esta clase). */
@SpringBootTest(properties = {"ADMIN_EMAIL=", "ADMIN_PASSWORD=", "JWT_SECRET=" + TestDb.JWT_SECRET})
@AutoConfigureMockMvc
class BruteForceIntegrationTest {

    private static final String EMAIL = "victima@example.com";
    private static final String PASSWORD = "una contraseña larga y segura";
    private static final String WRONG = "contraseña incorrecta!!";
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDb::newUrl);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService userService;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM users");
        userService.create(EMAIL, PASSWORD, Role.USER, true);
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("email", email, "password", password)))).andReturn();
    }

    private int status(String email, String password) throws Exception {
        return login(email, password).getResponse().getStatus();
    }

    private void fail(int times) throws Exception {
        for (int i = 0; i < times; i++) {
            assertThat(status(EMAIL, WRONG)).isEqualTo(401);
        }
    }

    private long lockSecondsRemaining() {
        String until = jdbc.queryForObject("SELECT locked_until FROM users WHERE email = ?", String.class, EMAIL);
        return until == null ? 0 : Duration.between(Instant.now(), Instant.parse(until)).toSeconds();
    }

    private int auditCount(String event) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE event = ?", Integer.class, event);
    }

    @Test
    void accountLocksAfterFiveFailuresAndRejectsEvenTheCorrectPassword() throws Exception {
        fail(4);
        assertThat(lockSecondsRemaining()).isZero();            // 4 fallos: aún sin bloqueo
        fail(1);                                                 // el 5.º dispara el bloqueo
        assertThat(lockSecondsRemaining()).isBetween(40L, 60L);
        assertThat(jdbc.queryForObject("SELECT lock_level FROM users", Integer.class)).isEqualTo(1);

        MvcResult withCorrectPassword = login(EMAIL, PASSWORD);
        assertThat(withCorrectPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(withCorrectPassword.getResponse().getHeader("Set-Cookie")).isNull();
        assertThat(withCorrectPassword.getResponse().getContentAsString()).contains("Credenciales inválidas.");
        assertThat(auditCount("ACCOUNT_LOCKED")).isEqualTo(1);
        assertThat(auditCount("LOGIN_BLOCKED")).isEqualTo(1);
    }

    @Test
    void lockedAccountResponseIsIndistinguishableFromWrongPasswordAndUnknownUser() throws Exception {
        fail(5);
        String locked = login(EMAIL, PASSWORD).getResponse().getContentAsString();
        String wrong = login(EMAIL, WRONG).getResponse().getContentAsString();
        String unknown = login("noexiste@example.com", PASSWORD).getResponse().getContentAsString();
        assertThat(locked).isEqualTo(wrong).isEqualTo(unknown);
    }

    @Test
    void lockDurationGrowsOnEachConsecutiveLock() throws Exception {
        fail(5);
        assertThat(lockSecondsRemaining()).isBetween(40L, 60L);              // 1.er bloqueo: 1 min

        jdbc.update("UPDATE users SET locked_until = ?", Instant.now().minusSeconds(5).toString()); // caduca
        fail(5);
        assertThat(lockSecondsRemaining()).isBetween(100L, 120L);            // 2.º bloqueo: 2 min
        assertThat(jdbc.queryForObject("SELECT lock_level FROM users", Integer.class)).isEqualTo(2);
    }

    @Test
    void successfulLoginAfterLockExpiresResetsEverything() throws Exception {
        fail(5);
        jdbc.update("UPDATE users SET locked_until = ?", Instant.now().minusSeconds(5).toString());

        assertThat(status(EMAIL, PASSWORD)).isEqualTo(200);
        Map<String, Object> row = jdbc.queryForMap("SELECT failed_attempts, lock_level, locked_until FROM users");
        assertThat(((Number) row.get("failed_attempts")).intValue()).isZero();
        assertThat(((Number) row.get("lock_level")).intValue()).isZero();
        assertThat(row.get("locked_until")).isNull();
    }

    @Test
    void successfulLoginResetsTheFailureCounter() throws Exception {
        fail(4);
        assertThat(status(EMAIL, PASSWORD)).isEqualTo(200);
        fail(4); // si el contador no se hubiera reiniciado, esto bloquearía la cuenta
        assertThat(status(EMAIL, PASSWORD)).isEqualTo(200);
    }

    @Test
    void lockingOneAccountDoesNotAffectAnother() throws Exception {
        userService.create("otra@example.com", PASSWORD, Role.USER, true);
        fail(5);
        assertThat(status("otra@example.com", PASSWORD)).isEqualTo(200);
    }

    @Test
    void failuresAgainstUnknownUserNeverCreateStateAndStayGeneric() throws Exception {
        for (int i = 0; i < 8; i++) {
            MvcResult r = login("fantasma@example.com", WRONG);
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
            assertThat(r.getResponse().getContentAsString()).contains("Credenciales inválidas.");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
        assertThat(auditCount("LOGIN_FAILED")).isEqualTo(8);
    }

    @Test
    void auditTrailHasNoSecretsOrTypedEmails() throws Exception {
        fail(5);
        login(EMAIL, PASSWORD);                       // bloqueado
        login("fantasma@example.com", WRONG);
        status(EMAIL, "a".repeat(5000));

        List<String> rows = jdbc.queryForList("SELECT event || '|' || ifnull(detail,'') || '|' || ifnull(ip,'') FROM audit_log",
                String.class);
        assertThat(rows).isNotEmpty();
        String everything = String.join("\n", rows);
        assertThat(everything).doesNotContain(PASSWORD).doesNotContain(WRONG)
                .doesNotContain("fantasma@example.com").doesNotContain(EMAIL).doesNotContain("argon2");
        // la IP y el usuario sí quedan registrados para poder investigar
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE user_id IS NOT NULL AND ip IS NOT NULL",
                Integer.class)).isPositive();
    }

    @Test
    void successfulLoginIsAudited() throws Exception {
        assertThat(status(EMAIL, PASSWORD)).isEqualTo(200);
        assertThat(auditCount("LOGIN_OK")).isEqualTo(1);
    }
}
