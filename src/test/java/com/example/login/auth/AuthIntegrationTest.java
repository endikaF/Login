package com.example.login.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.example.login.TestDb;
import com.example.login.user.Role;
import com.example.login.user.UserService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

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
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {"ADMIN_EMAIL=", "ADMIN_PASSWORD=", "JWT_SECRET=" + TestDb.JWT_SECRET})
@AutoConfigureMockMvc
class AuthIntegrationTest {

    private static final String EMAIL = "ana@example.com";
    private static final String PASSWORD = "una contraseña larga y segura";
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDb::newUrl);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService userService;
    @Autowired JwtService jwt;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM users");
        userService.create(EMAIL, PASSWORD, Role.USER, true);
    }

    // ---------- helpers ----------

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("email", email, "password", password)))).andReturn();
    }

    private String accessToken(MvcResult login) throws Exception {
        return JSON.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String refreshToken(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie("refresh_token");
        return cookie == null ? null : cookie.getValue();
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", refreshToken))).andReturn();
    }

    private int me(String accessToken) throws Exception {
        return mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andReturn().getResponse().getStatus();
    }

    // ---------- login ----------

    @Test
    void loginReturnsShortLivedJwtAndHardenedRefreshCookie() throws Exception {
        MvcResult result = login(EMAIL, PASSWORD);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.get("expiresIn").asLong()).isEqualTo(600);
        assertThat(body.has("refreshToken")).isFalse(); // el refresh token NO va en el JSON

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("refresh_token=", "HttpOnly", "Secure", "SameSite=Strict", "Path=/api/auth");
        assertThat(me(body.get("accessToken").asText())).isEqualTo(200);
    }

    @Test
    void refreshTokenIsStoredOnlyAsSha256Hash() throws Exception {
        String raw = refreshToken(login(EMAIL, PASSWORD));
        String stored = jdbc.queryForObject("SELECT token_hash FROM refresh_tokens", String.class);
        assertThat(stored).isNotEqualTo(raw).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void wrongPasswordUnknownUserAndDisabledUserGetIdenticalResponse() throws Exception {
        jdbc.update("UPDATE users SET enabled = 0 WHERE email = ?", EMAIL);
        userService.create("activo@example.com", PASSWORD, Role.USER, true);

        MvcResult disabled = login(EMAIL, PASSWORD);                       // cuenta desactivada, contraseña correcta
        MvcResult wrong = login("activo@example.com", "contraseña equivocada!!");
        MvcResult unknown = login("noexiste@example.com", PASSWORD);

        for (MvcResult r : List.of(disabled, wrong, unknown)) {
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
            assertThat(r.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
        }
        assertThat(disabled.getResponse().getContentAsString())
                .isEqualTo(wrong.getResponse().getContentAsString())
                .isEqualTo(unknown.getResponse().getContentAsString())
                .contains("Credenciales inválidas.");
    }

    @Test
    void loginEmailIsCaseInsensitive() throws Exception {
        assertThat(login("ANA@Example.com", PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void overlongPasswordIsRejectedWithGenericError() throws Exception {
        MvcResult r = login(EMAIL, "a".repeat(5000));
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(r.getResponse().getContentAsString()).contains("Credenciales inválidas.");
    }

    @Test
    void malformedLoginBodyIsBadRequest() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- access token ----------

    @Test
    void protectedEndpointWithoutTokenIs401() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedExpiredAndForeignTokensAreRejected() throws Exception {
        String valid = accessToken(login(EMAIL, PASSWORD));
        long userId = jdbc.queryForObject("SELECT id FROM users", Long.class);

        assertThat(me(valid + "x")).isEqualTo(401);                                       // firma alterada
        assertThat(me("basura")).isEqualTo(401);
        assertThat(me(jwt.issue(userId, 0, Instant.now().minusSeconds(3600)))).isEqualTo(401); // caducado
        assertThat(me(new JwtService("otra-clave-distinta-de-sobra-0123456789abc", 10).issue(userId, 0)))
                .isEqualTo(401);                                                          // otra clave
    }

    @Test
    void tokenOfDisabledUserStopsWorkingImmediately() throws Exception {
        String token = accessToken(login(EMAIL, PASSWORD));
        assertThat(me(token)).isEqualTo(200);
        jdbc.update("UPDATE users SET enabled = 0");
        assertThat(me(token)).isEqualTo(401);
    }

    @Test
    void roleIsReadFromDatabaseNotFromToken() throws Exception {
        String token = accessToken(login(EMAIL, PASSWORD));
        jdbc.update("UPDATE users SET role = 'ADMIN'");
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    // ---------- refresh rotatorio ----------

    @Test
    void refreshRotatesTokenAndIssuesNewAccessToken() throws Exception {
        String first = refreshToken(login(EMAIL, PASSWORD));
        MvcResult rotated = refresh(first);
        assertThat(rotated.getResponse().getStatus()).isEqualTo(200);

        String second = refreshToken(rotated);
        assertThat(second).isNotBlank().isNotEqualTo(first);
        assertThat(me(accessToken(rotated))).isEqualTo(200);
        assertThat(refresh(second).getResponse().getStatus()).isEqualTo(200); // el nuevo sí vale
    }

    @Test
    void reusingRotatedRefreshTokenRevokesWholeFamily() throws Exception {
        String first = refreshToken(login(EMAIL, PASSWORD));
        String second = refreshToken(refresh(first));          // rotación legítima

        assertThat(refresh(first).getResponse().getStatus()).isEqualTo(401);   // reutilización detectada
        assertThat(refresh(second).getResponse().getStatus()).isEqualTo(401);  // ...y cae toda la familia
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE revoked_at IS NULL", Integer.class)).isZero();
    }

    @Test
    void reuseInOneFamilyDoesNotAffectAnotherDevice() throws Exception {
        String phone = refreshToken(login(EMAIL, PASSWORD));
        String laptop = refreshToken(login(EMAIL, PASSWORD));
        refresh(phone);
        refresh(phone); // reutilización en la familia del móvil

        assertThat(refresh(laptop).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void expiredRefreshTokenIsRejected() throws Exception {
        String raw = refreshToken(login(EMAIL, PASSWORD));
        jdbc.update("UPDATE refresh_tokens SET expires_at = ?", Instant.now().minusSeconds(60).toString());
        assertThat(refresh(raw).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void unknownOrMissingRefreshTokenIsRejectedAndCookieCleared() throws Exception {
        MvcResult unknown = refresh("token-inventado");
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknown.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");

        mvc.perform(post("/api/auth/refresh")).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshForDisabledUserIsRejected() throws Exception {
        String raw = refreshToken(login(EMAIL, PASSWORD));
        jdbc.update("UPDATE users SET enabled = 0");
        assertThat(refresh(raw).getResponse().getStatus()).isEqualTo(401);
    }

    // ---------- logout ----------

    @Test
    void logoutRevokesRefreshTokenOnServerAndClearsCookie() throws Exception {
        String raw = refreshToken(login(EMAIL, PASSWORD));
        MvcResult out = mvc.perform(post("/api/auth/logout").cookie(new Cookie("refresh_token", raw)))
                .andExpect(status().isNoContent()).andReturn();

        assertThat(out.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("refresh_token=", "Max-Age=0");
        assertThat(refresh(raw).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void logoutRevokesTokensAlreadyRotatedInTheSameSession() throws Exception {
        String first = refreshToken(login(EMAIL, PASSWORD));
        String current = refreshToken(refresh(first));
        mvc.perform(post("/api/auth/logout").cookie(new Cookie("refresh_token", current)));
        assertThat(refresh(current).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void logoutWithoutSessionIsHarmless() throws Exception {
        mvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
    }

    @Test
    void logoutAllRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/auth/logout-all")).andExpect(status().isUnauthorized());
    }

    // ---------- auditoría ----------

    @Test
    void sessionEventsAreAuditedWithoutSecrets() throws Exception {
        MvcResult first = login(EMAIL, PASSWORD);
        String old = refreshToken(first);
        String current = refreshToken(refresh(old));
        refresh(old);                                                    // reutilización
        mvc.perform(post("/api/auth/logout").cookie(new Cookie("refresh_token", refreshToken(login(EMAIL, PASSWORD)))));
        mvc.perform(post("/api/auth/logout-all")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(login(EMAIL, PASSWORD))));

        List<String> events = jdbc.queryForList("SELECT event FROM audit_log ORDER BY id", String.class);
        assertThat(events).contains("LOGIN_OK", "TOKEN_REUSE", "LOGOUT", "LOGOUT_ALL");

        String everything = String.join("\n", jdbc.queryForList(
                "SELECT event || '|' || ifnull(detail,'') || '|' || ifnull(ip,'') FROM audit_log", String.class));
        assertThat(everything).doesNotContain(old).doesNotContain(current).doesNotContain(PASSWORD)
                .doesNotContain(accessToken(first));
    }

    @Test
    void logoutAllEndsEverySessionAndInvalidatesAccessTokens() throws Exception {
        MvcResult phone = login(EMAIL, PASSWORD);
        MvcResult laptop = login(EMAIL, PASSWORD);
        String accessPhone = accessToken(phone);

        mvc.perform(post("/api/auth/logout-all").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessPhone))
                .andExpect(status().isNoContent());

        assertThat(refresh(refreshToken(phone)).getResponse().getStatus()).isEqualTo(401);
        assertThat(refresh(refreshToken(laptop)).getResponse().getStatus()).isEqualTo(401);
        assertThat(me(accessPhone)).isEqualTo(401);                  // el JWT aún no caducado también cae
        assertThat(me(accessToken(login(EMAIL, PASSWORD)))).isEqualTo(200); // y se puede volver a entrar
    }
}
