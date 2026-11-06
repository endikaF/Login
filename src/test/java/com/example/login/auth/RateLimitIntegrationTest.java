package com.example.login.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;

import com.example.login.TestDb;
import com.example.login.user.Role;
import com.example.login.user.UserService;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Rate limiting con límites bajos. El estado de los cubos vive en memoria, así que se recrea el contexto
 * tras cada test para que no se contaminen entre sí.
 */
@SpringBootTest(properties = {"ADMIN_EMAIL=", "ADMIN_PASSWORD=", "JWT_SECRET=" + TestDb.JWT_SECRET,
        "app.ratelimit.login-ip-per-minute=5", "app.ratelimit.login-account-per-15min=3",
        "app.ratelimit.register-ip-per-hour=2"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RateLimitIntegrationTest {

    private static final String PASSWORD = "una contraseña larga y segura";
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDb::newUrl);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService userService;

    private MvcResult send(String path, String email, String password) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("email", email, "password", password)))).andReturn();
    }

    private int login(String email, String password) throws Exception {
        return send("/api/auth/login", email, password).getResponse().getStatus();
    }

    @Test
    void accountLimitBlocksFourthAttemptEvenWithCorrectPassword() throws Exception {
        userService.create("ana@example.com", PASSWORD, Role.USER, true);
        for (int i = 0; i < 3; i++) {
            assertThat(login("ana@example.com", "incorrecta-incorrecta")).isEqualTo(401);
        }
        MvcResult blocked = send("/api/auth/login", "ana@example.com", PASSWORD);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(blocked.getResponse().getHeader("Retry-After"))).isPositive();
        assertThat(blocked.getResponse().getContentAsString()).contains("Demasiados intentos");
    }

    @Test
    void unknownAccountIsLimitedExactlyLikeAnExistingOne() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(login("fantasma@example.com", "incorrecta-incorrecta")).isEqualTo(401);
        }
        assertThat(login("fantasma@example.com", "incorrecta-incorrecta")).isEqualTo(429); // no revela si existe
    }

    @Test
    void accountLimitDoesNotAffectOtherAccounts() throws Exception {
        userService.create("otra@example.com", PASSWORD, Role.USER, true);
        for (int i = 0; i < 4; i++) {
            login("ana@example.com", "incorrecta-incorrecta");
        }
        assertThat(login("otra@example.com", PASSWORD)).isEqualTo(200);
    }

    @Test
    void ipLimitBlocksSixthAttemptAcrossDifferentAccounts() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(login("usuario" + i + "@example.com", "incorrecta-incorrecta")).isEqualTo(401);
        }
        MvcResult blocked = send("/api/auth/login", "usuario9@example.com", "incorrecta-incorrecta");
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(blocked.getResponse().getHeader("Retry-After")).isNotNull();
    }

    @Test
    void registrationIsLimitedPerIp() throws Exception {
        assertThat(send("/api/auth/register", "a1@example.com", PASSWORD).getResponse().getStatus()).isEqualTo(201);
        assertThat(send("/api/auth/register", "a2@example.com", PASSWORD).getResponse().getStatus()).isEqualTo(201);
        assertThat(send("/api/auth/register", "a3@example.com", PASSWORD).getResponse().getStatus()).isEqualTo(429);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(2);
    }

    @Test
    void rateLimitedAttemptsAreAudited() throws Exception {
        for (int i = 0; i < 4; i++) {
            login("ana@example.com", "incorrecta-incorrecta");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE event = 'RATE_LIMITED'",
                Integer.class)).isEqualTo(1);
    }
}
