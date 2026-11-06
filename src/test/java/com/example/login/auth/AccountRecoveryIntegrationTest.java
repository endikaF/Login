package com.example.login.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.example.login.RecordingEmailService;
import com.example.login.TestDb;
import com.example.login.user.Role;
import com.example.login.user.UserService;

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
class AccountRecoveryIntegrationTest {

    private static final String EMAIL = "ana@example.com";
    private static final String PASSWORD = "una contraseña larga y segura";
    private static final String NEW_PASSWORD = "otra contraseña distinta y larga";
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDb::newUrl);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService userService;
    @Autowired RecordingEmailService mail;

    @BeforeEach
    void clean() {
        mail.clear();
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM users"); // las filas de one_time_tokens caen por ON DELETE CASCADE
    }

    // ---------- helpers ----------

    private MvcResult send(String path, Map<String, String> body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body))).andReturn();
    }

    private int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private MvcResult login(String password) throws Exception {
        return send("/api/auth/login", Map.of("email", EMAIL, "password", password));
    }

    private void registerAndGetMail() throws Exception {
        assertThat(status(send("/api/auth/register", Map.of("email", EMAIL, "password", PASSWORD)))).isEqualTo(202);
    }

    private int verify(String token) throws Exception {
        return status(send("/api/auth/verify-email", Map.of("token", token)));
    }

    private int reset(String token, String password) throws Exception {
        return status(send("/api/auth/reset-password", Map.of("token", token, "newPassword", password)));
    }

    private void forgot(String email) throws Exception {
        assertThat(status(send("/api/auth/forgot-password", Map.of("email", email)))).isEqualTo(202);
    }

    // ---------- verificación de email ----------

    @Test
    void registrationSendsVerificationLinkAndLoginIsBlockedUntilVerified() throws Exception {
        registerAndGetMail();
        assertThat(mail.last().to()).isEqualTo(EMAIL);
        assertThat(mail.last().body()).contains("/verify-email.html#token="); // fragmento, no query

        MvcResult blocked = login(PASSWORD);
        assertThat(status(blocked)).isEqualTo(403);
        assertThat(blocked.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(blocked.getResponse().getContentAsString()).contains("verificar tu email");

        assertThat(verify(mail.lastToken())).isEqualTo(200);
        assertThat(status(login(PASSWORD))).isEqualTo(200);
    }

    @Test
    void unverifiedAccountWithWrongPasswordStillGetsGenericError() throws Exception {
        registerAndGetMail();
        MvcResult r = login("contraseña equivocada!!");
        assertThat(status(r)).isEqualTo(401); // el aviso de "verifica tu email" no se da sin la contraseña correcta
        assertThat(r.getResponse().getContentAsString()).contains("Credenciales inválidas.");
    }

    @Test
    void tokenIsStoredOnlyAsHashAndExpiresInTheFuture() throws Exception {
        registerAndGetMail();
        String raw = mail.lastToken();
        Map<String, Object> row = jdbc.queryForMap("SELECT token_hash, purpose, expires_at, used_at FROM one_time_tokens");
        assertThat((String) row.get("token_hash")).isNotEqualTo(raw).hasSize(64).matches("[0-9a-f]+");
        assertThat(row.get("purpose")).isEqualTo("VERIFY_EMAIL");
        assertThat(row.get("used_at")).isNull();
        assertThat(Instant.parse((String) row.get("expires_at"))).isAfter(Instant.now().plus(Duration.ofHours(23)));
    }

    @Test
    void verificationTokenWorksOnlyOnce() throws Exception {
        registerAndGetMail();
        String token = mail.lastToken();
        assertThat(verify(token)).isEqualTo(200);
        assertThat(verify(token)).isEqualTo(400);
    }

    @Test
    void expiredAndGarbageVerificationTokensAreRejected() throws Exception {
        registerAndGetMail();
        String token = mail.lastToken();
        jdbc.update("UPDATE one_time_tokens SET expires_at = ?", Instant.now().minusSeconds(5).toString());
        assertThat(verify(token)).isEqualTo(400);
        assertThat(verify("token-inventado")).isEqualTo(400);
        assertThat(verify("a".repeat(5000))).isEqualTo(400);
        assertThat(status(login(PASSWORD))).isEqualTo(403); // sigue sin verificar
    }

    @Test
    void registeringExistingEmailCreatesNothingAndSendsNoToken() throws Exception {
        userService.create(EMAIL, PASSWORD, Role.USER, true);
        registerAndGetMail();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM one_time_tokens", Integer.class)).isZero();
        assertThat(mail.last().body()).doesNotContain("#token="); // aviso "ya tienes cuenta", sin enlace secreto
    }

    @Test
    void resendVerificationIssuesNewLinkAndInvalidatesTheOldOne() throws Exception {
        registerAndGetMail();
        String old = mail.lastToken();
        assertThat(status(send("/api/auth/resend-verification", Map.of("email", EMAIL)))).isEqualTo(202);
        String fresh = mail.lastToken();

        assertThat(fresh).isNotEqualTo(old);
        assertThat(verify(old)).isEqualTo(400);
        assertThat(verify(fresh)).isEqualTo(200);
    }

    @Test
    void resendVerificationAnswersTheSameForUnknownAndAlreadyVerified() throws Exception {
        userService.create(EMAIL, PASSWORD, Role.USER, true);
        MvcResult verified = send("/api/auth/resend-verification", Map.of("email", EMAIL));
        MvcResult unknown = send("/api/auth/resend-verification", Map.of("email", "fantasma@example.com"));

        assertThat(status(verified)).isEqualTo(202);
        assertThat(verified.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
        assertThat(mail.all()).isEmpty();
    }

    // ---------- recuperación de contraseña ----------

    @Test
    void forgotPasswordSendsOneTimeLinkWithShortExpiry() throws Exception {
        userService.create(EMAIL, PASSWORD, Role.USER, true);
        forgot(EMAIL);

        assertThat(mail.last().to()).isEqualTo(EMAIL);
        assertThat(mail.last().body()).contains("/reset-password.html#token=");
        Map<String, Object> row = jdbc.queryForMap("SELECT token_hash, purpose, expires_at FROM one_time_tokens");
        assertThat((String) row.get("token_hash")).isNotEqualTo(mail.lastToken()).hasSize(64);
        assertThat(row.get("purpose")).isEqualTo("RESET_PASSWORD");
        Duration ttl = Duration.between(Instant.now(), Instant.parse((String) row.get("expires_at")));
        assertThat(ttl).isBetween(Duration.ofMinutes(15), Duration.ofMinutes(30)); // requisito: 15-30 min
    }

    @Test
    void forgotPasswordResponseIsIdenticalForExistingUnknownAndDisabledAccounts() throws Exception {
        userService.create(EMAIL, PASSWORD, Role.USER, true);
        userService.create("baja@example.com", PASSWORD, Role.USER, true);
        jdbc.update("UPDATE users SET enabled = 0 WHERE email = 'baja@example.com'");

        MvcResult existing = send("/api/auth/forgot-password", Map.of("email", EMAIL));
        mail.clear();
        MvcResult unknown = send("/api/auth/forgot-password", Map.of("email", "fantasma@example.com"));
        MvcResult disabled = send("/api/auth/forgot-password", Map.of("email", "baja@example.com"));

        assertThat(status(existing)).isEqualTo(status(unknown)).isEqualTo(status(disabled)).isEqualTo(202);
        assertThat(existing.getResponse().getContentAsString())
                .isEqualTo(unknown.getResponse().getContentAsString())
                .isEqualTo(disabled.getResponse().getContentAsString());
        assertThat(mail.all()).isEmpty(); // a inexistentes y desactivadas no se les envía nada
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM one_time_tokens", Integer.class)).isEqualTo(1);
    }

    @Test
    void resetChangesPasswordAndTokenWorksOnlyOnce() throws Exception {
        userService.create(EMAIL, PASSWORD, Role.USER, true);
        forgot(EMAIL);
        String token = mail.lastToken();

        assertThat(reset(token, NEW_PASSWORD)).isEqualTo(200);
        assertThat(status(login(NEW_PASSWORD))).isEqualTo(200);
        assertThat(status(login(PASSWORD))).isEqualTo(401);       // la antigua ya no sirve
        assertThat(reset(token, "una tercera contraseña larga")).isEqualTo(400); // un solo uso
    }

    @Test
    void resetRevokesEverySessionAndAccessToken() throws Exception {
        userService.create(EMAIL, PASSWORD, Role.USER, true);
        MvcResult session = login(PASSWORD);
        String access = JSON.readTree(session.getResponse().getContentAsString()).get("accessToken").asText();
        String refresh = session.getResponse().getCookie("refresh_token").getValue();

        forgot(EMAIL);
        assertThat(reset(mail.lastToken(), NEW_PASSWORD)).isEqualTo(200);

        assertThat(status(mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", refresh)))
                .andReturn())).isEqualTo(401);
        assertThat(mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE revoked_at IS NULL",
                Integer.class)).isZero();
    }

    @Test
    void resetClearsLockoutAndVerifiesEmail() throws Exception {
        userService.create(EMAIL, PASSWORD, Role.USER, false);
        for (int i = 0; i < 5; i++) {
            login("incorrecta-incorrecta");
        }
        assertThat(jdbc.queryForObject("SELECT locked_until FROM users", String.class)).isNotNull();

        forgot(EMAIL);
        assertThat(reset(mail.lastToken(), NEW_PASSWORD)).isEqualTo(200);

        assertThat(jdbc.queryForObject("SELECT locked_until FROM users", String.class)).isNull();
        assertThat(status(login(NEW_PASSWORD))).isEqualTo(200); // el enlace demostró el control del email
    }

    @Test
    void weakNewPasswordIsRejectedWithoutBurningTheToken() throws Exception {
        userService.create(EMAIL, PASSWORD, Role.USER, true);
        forgot(EMAIL);
        String token = mail.lastToken();

        assertThat(reset(token, "corta")).isEqualTo(400);
        assertThat(reset(token, "a".repeat(129))).isEqualTo(400);
        assertThat(reset(token, NEW_PASSWORD)).isEqualTo(200); // el mismo enlace sigue valiendo
    }

    @Test
    void expiredAndInventedResetTokensAreRejected() throws Exception {
        userService.create(EMAIL, PASSWORD, Role.USER, true);
        forgot(EMAIL);
        String token = mail.lastToken();
        jdbc.update("UPDATE one_time_tokens SET expires_at = ?", Instant.now().minusSeconds(5).toString());

        assertThat(reset(token, NEW_PASSWORD)).isEqualTo(400);
        assertThat(reset("inventado", NEW_PASSWORD)).isEqualTo(400);
        assertThat(status(login(PASSWORD))).isEqualTo(200); // la contraseña no cambió
    }

    @Test
    void newRequestInvalidatesThePreviousLink() throws Exception {
        userService.create(EMAIL, PASSWORD, Role.USER, true);
        forgot(EMAIL);
        String first = mail.lastToken();
        forgot(EMAIL);
        String second = mail.lastToken();

        assertThat(reset(first, NEW_PASSWORD)).isEqualTo(400);
        assertThat(reset(second, NEW_PASSWORD)).isEqualTo(200);
    }

    @Test
    void tokensAreBoundToTheirPurpose() throws Exception {
        registerAndGetMail();
        String verificationToken = mail.lastToken();
        assertThat(reset(verificationToken, NEW_PASSWORD)).isEqualTo(400); // verificación ≠ reseteo

        userService.create("otra@example.com", PASSWORD, Role.USER, true);
        forgot("otra@example.com");
        assertThat(verify(mail.lastToken())).isEqualTo(400);               // reseteo ≠ verificación
    }

    @Test
    void resetIsRejectedIfAccountWasDisabledAfterRequestingTheLink() throws Exception {
        userService.create(EMAIL, PASSWORD, Role.USER, true);
        forgot(EMAIL);
        jdbc.update("UPDATE users SET enabled = 0");

        assertThat(reset(mail.lastToken(), NEW_PASSWORD)).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT used_at FROM one_time_tokens", String.class)).isNull(); // rollback
    }

    // ---------- auditoría ----------

    @Test
    void recoveryEventsAreAuditedWithoutTokensOrPasswords() throws Exception {
        userService.create(EMAIL, PASSWORD, Role.USER, true);
        forgot(EMAIL);
        String token = mail.lastToken();
        reset("inventado", NEW_PASSWORD);
        reset(token, NEW_PASSWORD);

        assertThat(jdbc.queryForList("SELECT event FROM audit_log ORDER BY id", String.class))
                .contains("PASSWORD_RESET_REQUESTED", "PASSWORD_RESET_FAILED", "PASSWORD_RESET_DONE");
        String everything = String.join("\n", jdbc.queryForList(
                "SELECT event || '|' || ifnull(detail,'') || '|' || ifnull(ip,'') FROM audit_log", String.class));
        assertThat(everything).doesNotContain(token).doesNotContain(NEW_PASSWORD).doesNotContain("inventado")
                .doesNotContain(EMAIL);
    }
}
