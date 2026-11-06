package com.example.login.auth;

import static com.example.login.AuthTestSupport.PASSWORD;
import static com.example.login.AuthTestSupport.accessToken;
import static com.example.login.AuthTestSupport.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.login.AuthTestSupport;
import com.example.login.RecordingEmailService;
import com.example.login.TestClock;
import com.example.login.TestDb;
import com.example.login.totp.Totp;
import com.example.login.user.Role;
import com.example.login.user.UserService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
class TotpIntegrationTest {

    private static final String EMAIL = "ana@example.com";
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDb::newUrl);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService users;
    @Autowired JwtService jwt;
    @Autowired TestClock clock;
    @Autowired RecordingEmailService mail;

    private String access;     // access token de ana antes de activar el 2FA
    private long anaId;

    @BeforeEach
    void seed() throws Exception {
        clock.reset();
        mail.clear();
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM users"); // user_totp y los códigos de recuperación caen por ON DELETE CASCADE
        users.create(EMAIL, PASSWORD, Role.USER, true);
        anaId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, EMAIL);
        access = accessToken(AuthTestSupport.login(mvc, EMAIL));
    }

    // ---------- helpers ----------

    private MvcResult json(String path, String token, Map<String, ?> body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, bearer(token));
        }
        return mvc.perform(request).andReturn();
    }

    private int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString());
    }

    private String currentCode(String secret) {
        return Totp.code(Totp.decodeBase32(secret), clock.instant());
    }

    /** Hace el alta completa y deja el 2FA activo. Devuelve {secreto, códigos de recuperación}. */
    private record Enrolled(String secret, List<String> recoveryCodes) {
    }

    private Enrolled enroll() throws Exception {
        String secret = body(json("/api/auth/totp/setup", access, Map.of("password", PASSWORD))).get("secret").asText();
        MvcResult enabled = json("/api/auth/totp/enable", access, Map.of("code", currentCode(secret)));
        assertThat(status(enabled)).isEqualTo(200);
        List<String> codes = new ArrayList<>();
        body(enabled).get("recoveryCodes").forEach(n -> codes.add(n.asText()));
        return new Enrolled(secret, codes);
    }

    private MvcResult loginStep1() throws Exception {
        return json("/api/auth/login", null, Map.of("email", EMAIL, "password", PASSWORD));
    }

    private String mfaToken() throws Exception {
        MvcResult r = loginStep1();
        assertThat(body(r).get("mfaRequired").asBoolean()).isTrue();
        return body(r).get("mfaToken").asText();
    }

    private MvcResult loginStep2(String mfaToken, String code) throws Exception {
        return json("/api/auth/login/totp", null, Map.of("mfaToken", mfaToken, "code", code));
    }

    private int meStatus(String token) throws Exception {
        return mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andReturn().getResponse().getStatus();
    }

    private int auditCount(String event) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE event = ?", Integer.class, event);
    }

    // ---------- alta ----------

    @Test
    void setupRequiresAnAuthenticatedSession() throws Exception {
        assertThat(status(json("/api/auth/totp/setup", null, Map.of("password", PASSWORD)))).isEqualTo(401);
        assertThat(status(json("/api/auth/totp/enable", null, Map.of("code", "123456")))).isEqualTo(401);
        assertThat(status(json("/api/auth/totp/disable", null, Map.of("password", PASSWORD, "code", "123456")))).isEqualTo(401);
    }

    @Test
    void setupDemandsThePasswordAndCountsAWrongOneAsAFailure() throws Exception {
        MvcResult r = json("/api/auth/totp/setup", access, Map.of("password", "contraseña equivocada!!"));
        assertThat(status(r)).isEqualTo(403);
        assertThat(r.getResponse().getContentAsString()).contains("Contraseña o código incorrectos.");
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_totp", Integer.class)).isZero();
        assertThat(auditCount("TOTP_FAILED")).isEqualTo(1);
    }

    @Test
    void setupStoresTheSecretEncryptedAndStillPending() throws Exception {
        JsonNode setup = body(json("/api/auth/totp/setup", access, Map.of("password", PASSWORD)));
        String secret = setup.get("secret").asText();

        assertThat(secret).hasSize(32).matches("[A-Z2-7]+");
        assertThat(setup.get("otpauthUri").asText()).startsWith("otpauth://totp/").contains("secret=" + secret);
        Map<String, Object> row = jdbc.queryForMap("SELECT secret_enc, enabled FROM user_totp");
        assertThat((String) row.get("secret_enc")).isNotBlank().doesNotContain(secret).matches("[0-9a-f]+");
        assertThat(((Number) row.get("enabled")).intValue()).isZero();
    }

    @Test
    void enablingWithAWrongCodeFailsAndLeavesItPending() throws Exception {
        String secret = body(json("/api/auth/totp/setup", access, Map.of("password", PASSWORD))).get("secret").asText();
        String wrong = String.format("%06d", (Integer.parseInt(currentCode(secret)) + 1) % 1_000_000);

        assertThat(status(json("/api/auth/totp/enable", access, Map.of("code", wrong)))).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT enabled FROM user_totp", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM users", Integer.class)).isEqualTo(1);
    }

    @Test
    void enablingActivatesTwoFactorAndReturnsEightRecoveryCodesStoredOnlyAsHashes() throws Exception {
        Enrolled e = enroll();

        assertThat(e.recoveryCodes()).hasSize(8).allMatch(c -> c.matches("[A-Z2-7]{4}(-[A-Z2-7]{4}){3}"));
        assertThat(e.recoveryCodes()).doesNotHaveDuplicates();
        List<String> hashes = jdbc.queryForList("SELECT code_hash FROM totp_recovery_codes", String.class);
        assertThat(hashes).hasSize(8).allMatch(h -> h.matches("[0-9a-f]{64}"));
        assertThat(String.join(",", hashes)).doesNotContain(e.recoveryCodes().get(0).replace("-", ""));
        assertThat(mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(access)))
                .andReturn().getResponse().getContentAsString()).contains("\"totpEnabled\":true");
        assertThat(mail.last().subject()).isEqualTo("Verificación en dos pasos activada");
        assertThat(mail.last().body()).doesNotContain(e.secret());
        assertThat(auditCount("TOTP_ENABLED")).isEqualTo(1);
    }

    @Test
    void settingUpAgainBeforeEnablingReplacesThePendingSecret() throws Exception {
        String first = body(json("/api/auth/totp/setup", access, Map.of("password", PASSWORD))).get("secret").asText();
        String second = body(json("/api/auth/totp/setup", access, Map.of("password", PASSWORD))).get("secret").asText();

        assertThat(second).isNotEqualTo(first);
        assertThat(status(json("/api/auth/totp/enable", access, Map.of("code", currentCode(first))))).isEqualTo(403);
        assertThat(status(json("/api/auth/totp/enable", access, Map.of("code", currentCode(second))))).isEqualTo(200);
    }

    @Test
    void setupAndEnableAreRejectedWhenAlreadyActive() throws Exception {
        Enrolled e = enroll();
        assertThat(status(json("/api/auth/totp/setup", access, Map.of("password", PASSWORD)))).isEqualTo(409);
        clock.advance(Duration.ofSeconds(30));
        assertThat(status(json("/api/auth/totp/enable", access, Map.of("code", currentCode(e.secret()))))).isEqualTo(409);
    }

    // ---------- login en dos pasos ----------

    @Test
    void passwordAloneNoLongerGivesAccessWhenTwoFactorIsOn() throws Exception {
        enroll();
        MvcResult r = loginStep1();

        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).get("mfaRequired").asBoolean()).isTrue();
        assertThat(body(r).has("accessToken")).isFalse();
        assertThat(r.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull(); // ni cookie de sesión
        assertThat(auditCount("LOGIN_MFA_REQUIRED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens", Integer.class)).isEqualTo(1); // solo la sesión previa
    }

    @Test
    void theTemporaryTokenIsUselessAsAnAccessToken() throws Exception {
        enroll();
        assertThat(meStatus(mfaToken())).isEqualTo(401);
    }

    @Test
    void anAccessTokenIsUselessAsTheTemporaryToken() throws Exception {
        Enrolled e = enroll();
        clock.advance(Duration.ofSeconds(30));
        assertThat(status(loginStep2(access, currentCode(e.secret())))).isEqualTo(401);
    }

    @Test
    void validCodeCompletesTheLoginAndTheSameCodeCannotBeReplayed() throws Exception {
        Enrolled e = enroll();
        clock.advance(Duration.ofSeconds(30)); // el código del alta ya está gastado; toca el siguiente intervalo
        String code = currentCode(e.secret());
        String token = mfaToken();

        MvcResult ok = loginStep2(token, code);
        assertThat(status(ok)).isEqualTo(200);
        assertThat(ok.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("refresh_token=", "HttpOnly", "SameSite=Strict");
        assertThat(meStatus(body(ok).get("accessToken").asText())).isEqualTo(200);

        assertThat(status(loginStep2(token, code))).isEqualTo(401);              // repetición del mismo código
        assertThat(status(loginStep2(mfaToken(), code))).isEqualTo(401);         // también con una sesión de login nueva
    }

    @Test
    void theCodeUsedToEnableCannotBeUsedToLogIn() throws Exception {
        Enrolled e = enroll();
        assertThat(status(loginStep2(mfaToken(), currentCode(e.secret())))).isEqualTo(401);
    }

    @Test
    void wrongCodesGetAGenericErrorAndLockTheAccountLikeWrongPasswords() throws Exception {
        Enrolled e = enroll();
        jdbc.update("UPDATE users SET failed_attempts = 0"); // el alta no deja contadores previos
        String token = mfaToken();

        for (int i = 0; i < 5; i++) {
            MvcResult r = loginStep2(token, "000000");
            assertThat(status(r)).isEqualTo(401);
            assertThat(r.getResponse().getContentAsString()).contains("Credenciales inválidas.");
        }
        assertThat(jdbc.queryForObject("SELECT locked_until FROM users", String.class)).isNotNull();
        assertThat(auditCount("ACCOUNT_LOCKED")).isEqualTo(1);

        clock.advance(Duration.ofSeconds(30));
        assertThat(status(loginStep2(token, currentCode(e.secret())))).isEqualTo(401); // bloqueada: ni el código bueno
        assertThat(status(loginStep1())).isEqualTo(401);                                // ni la contraseña
    }

    @Test
    void passwordStepDoesNotResetTheFailureCounterWhenTwoFactorIsOn() throws Exception {
        enroll();
        jdbc.update("UPDATE users SET failed_attempts = 3");
        loginStep1();
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM users", Integer.class)).isEqualTo(3);
    }

    @Test
    void expiredOrManipulatedTemporaryTokensAreRejected() throws Exception {
        Enrolled e = enroll();
        clock.advance(Duration.ofSeconds(30));
        String code = currentCode(e.secret());
        long version = jdbc.queryForObject("SELECT token_version FROM users", Long.class);

        String expired = jwt.issueMfa(anaId, version, Instant.now().minusSeconds(3600));
        assertThat(status(loginStep2(expired, code))).isEqualTo(401);
        assertThat(status(loginStep2(mfaToken() + "x", code))).isEqualTo(401);
        assertThat(status(loginStep2("basura", code))).isEqualTo(401);
    }

    @Test
    void temporaryTokenDiesIfThePasswordChangedOrTheAccountWasDisabledMeanwhile() throws Exception {
        Enrolled e = enroll();
        clock.advance(Duration.ofSeconds(30));
        String code = currentCode(e.secret());

        String stale = mfaToken();
        jdbc.update("UPDATE users SET token_version = token_version + 1");   // p. ej. se cambió la contraseña
        assertThat(status(loginStep2(stale, code))).isEqualTo(401);

        String fresh = mfaToken();
        jdbc.update("UPDATE users SET enabled = 0");
        assertThat(status(loginStep2(fresh, code))).isEqualTo(401);
    }

    // ---------- códigos de recuperación ----------

    @Test
    void recoveryCodeLogsInOnceRegardlessOfCaseAndDashes() throws Exception {
        Enrolled e = enroll();
        String code = e.recoveryCodes().get(0);

        MvcResult ok = loginStep2(mfaToken(), code.toLowerCase().replace("-", " "));
        assertThat(status(ok)).isEqualTo(200);
        assertThat(auditCount("TOTP_RECOVERY_USED")).isEqualTo(1);

        assertThat(status(loginStep2(mfaToken(), code))).isEqualTo(401);                    // un solo uso
        assertThat(status(loginStep2(mfaToken(), e.recoveryCodes().get(1)))).isEqualTo(200); // los demás siguen valiendo
    }

    // ---------- baja ----------

    @Test
    void disablingNeedsBothPasswordAndACode() throws Exception {
        Enrolled e = enroll();
        clock.advance(Duration.ofSeconds(30));
        String code = currentCode(e.secret());

        assertThat(status(json("/api/auth/totp/disable", access, Map.of("password", "mala contraseña!!", "code", code)))).isEqualTo(403);
        assertThat(status(json("/api/auth/totp/disable", access, Map.of("password", PASSWORD, "code", "000000")))).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_totp WHERE enabled = 1", Integer.class)).isEqualTo(1);

        assertThat(status(json("/api/auth/totp/disable", access, Map.of("password", PASSWORD, "code", code)))).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_totp", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM totp_recovery_codes", Integer.class)).isZero();
        assertThat(mail.last().subject()).isEqualTo("Verificación en dos pasos desactivada");
        assertThat(body(loginStep1()).has("accessToken")).isTrue(); // vuelve el login normal
    }

    @Test
    void disablingWorksWithARecoveryCodeToo() throws Exception {
        Enrolled e = enroll();
        assertThat(status(json("/api/auth/totp/disable", access,
                Map.of("password", PASSWORD, "code", e.recoveryCodes().get(2))))).isEqualTo(204);
    }

    @Test
    void disablingWhenNotEnabledIs409() throws Exception {
        assertThat(status(json("/api/auth/totp/disable", access, Map.of("password", PASSWORD, "code", "123456")))).isEqualTo(409);
    }

    // ---------- recuperación de cuenta y administración ----------

    @Test
    void resettingThePasswordByEmailDoesNotRemoveTheSecondFactor() throws Exception {
        enroll();
        assertThat(status(json("/api/auth/forgot-password", null, Map.of("email", EMAIL)))).isEqualTo(202);
        String link = mail.lastToken();
        assertThat(status(json("/api/auth/reset-password", null,
                Map.of("token", link, "newPassword", "una contraseña nueva y larga")))).isEqualTo(200);

        MvcResult r = json("/api/auth/login", null, Map.of("email", EMAIL, "password", "una contraseña nueva y larga"));
        assertThat(body(r).get("mfaRequired").asBoolean()).isTrue(); // un correo robado no anula el 2FA
    }

    @Test
    void adminCanResetTwoFactorForALockedOutUserButAUserCannot() throws Exception {
        enroll();
        users.create("root@example.com", PASSWORD, Role.ADMIN, true);
        String admin = accessToken(AuthTestSupport.login(mvc, "root@example.com"));

        assertThat(status(json("/api/admin/users/" + anaId + "/totp/reset", access, Map.of()))).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_totp", Integer.class)).isEqualTo(1);

        String list = mvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andReturn().getResponse().getContentAsString();
        assertThat(list).contains("\"totpEnabled\":true");

        assertThat(status(json("/api/admin/users/" + anaId + "/totp/reset", admin, Map.of()))).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_totp", Integer.class)).isZero();
        assertThat(body(loginStep1()).has("accessToken")).isTrue();
        assertThat(jdbc.queryForObject("SELECT detail FROM audit_log WHERE event = 'ADMIN_TOTP_RESET'", String.class))
                .startsWith("admin=");
        assertThat(status(json("/api/admin/users/999999/totp/reset", admin, Map.of()))).isEqualTo(404);
    }

    // ---------- auditoría ----------

    @Test
    void auditTrailNeverContainsSecretsCodesOrRecoveryCodes() throws Exception {
        Enrolled e = enroll();
        clock.advance(Duration.ofSeconds(30));
        String code = currentCode(e.secret());
        loginStep2(mfaToken(), "000000");
        loginStep2(mfaToken(), e.recoveryCodes().get(0));
        json("/api/auth/totp/disable", access, Map.of("password", PASSWORD, "code", code));

        String everything = String.join("\n", jdbc.queryForList(
                "SELECT event || '|' || ifnull(detail,'') || '|' || ifnull(ip,'') FROM audit_log", String.class));
        assertThat(everything).doesNotContain(e.secret()).doesNotContain(code).doesNotContain("000000")
                .doesNotContain(e.recoveryCodes().get(0)).doesNotContain(e.recoveryCodes().get(0).replace("-", ""))
                .doesNotContain(EMAIL).doesNotContain(PASSWORD);
    }
}
