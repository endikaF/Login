package com.example.login.admin;

import static com.example.login.AuthTestSupport.accessToken;
import static com.example.login.AuthTestSupport.bearer;
import static com.example.login.AuthTestSupport.createAndLogin;
import static com.example.login.AuthTestSupport.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.example.login.TestDb;
import com.example.login.user.Role;
import com.example.login.user.UserService;

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
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {"ADMIN_EMAIL=", "ADMIN_PASSWORD=", "JWT_SECRET=" + TestDb.JWT_SECRET})
@AutoConfigureMockMvc
class AdminIntegrationTest {

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDb::newUrl);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService users;

    private String admin;
    private String ben;
    private long adminId;
    private long benId;

    @BeforeEach
    void seed() throws Exception {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM users");
        admin = createAndLogin(mvc, users, "root@example.com", Role.ADMIN);
        ben = createAndLogin(mvc, users, "ben@example.com", Role.USER);
        adminId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'root@example.com'", Long.class);
        benId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'ben@example.com'", Long.class);
    }

    private ResultActions as(String token, String method, String path, String json) throws Exception {
        var request = "GET".equals(method) ? get(path) : post(path);
        request.header(HttpHeaders.AUTHORIZATION, bearer(token));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request);
    }

    private int meStatus(String token) throws Exception {
        return as(token, "GET", "/api/auth/me", null).andReturn().getResponse().getStatus();
    }

    // ---------- control de acceso por rol ----------

    @Test
    void adminCanListUsersAndNoSecretsAreExposed() throws Exception {
        String body = as(admin, "GET", "/api/admin/users", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("ben@example.com").doesNotContain("argon2").doesNotContain("password")
                .doesNotContain("token");
    }

    @Test
    void regularUserCannotUseAnyAdminEndpoint() throws Exception {
        as(ben, "GET", "/api/admin/users", null).andExpect(status().isForbidden());
        as(ben, "POST", "/api/admin/users/" + adminId + "/enabled", "{\"enabled\":false}").andExpect(status().isForbidden());
        as(ben, "POST", "/api/admin/users/" + benId + "/role", "{\"role\":\"ADMIN\"}").andExpect(status().isForbidden());
        as(ben, "POST", "/api/admin/users/" + adminId + "/logout", null).andExpect(status().isForbidden());

        // y no ha pasado nada
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, benId)).isEqualTo("USER");
        assertThat(jdbc.queryForObject("SELECT enabled FROM users WHERE id = ?", Integer.class, adminId)).isEqualTo(1);
    }

    @Test
    void anonymousUserGets401OnAdminEndpoints() throws Exception {
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/users/1/logout")).andExpect(status().isUnauthorized());
    }

    @Test
    void promotionTakesEffectImmediatelyBecauseRoleIsReadFromTheDatabase() throws Exception {
        as(ben, "GET", "/api/admin/users", null).andExpect(status().isForbidden());
        as(admin, "POST", "/api/admin/users/" + benId + "/role", "{\"role\":\"ADMIN\"}").andExpect(status().isNoContent());
        as(ben, "GET", "/api/admin/users", null).andExpect(status().isOk()); // mismo token, rol nuevo

        as(admin, "POST", "/api/admin/users/" + benId + "/role", "{\"role\":\"USER\"}").andExpect(status().isNoContent());
        as(ben, "GET", "/api/admin/users", null).andExpect(status().isForbidden());
    }

    // ---------- activar / desactivar ----------

    @Test
    void disablingAnAccountEndsItsSessionsAndBlocksLogin() throws Exception {
        MvcResult session = login(mvc, "ben@example.com");
        String refresh = session.getResponse().getCookie("refresh_token").getValue();

        as(admin, "POST", "/api/admin/users/" + benId + "/enabled", "{\"enabled\":false}").andExpect(status().isNoContent());

        assertThat(meStatus(ben)).isEqualTo(401);
        assertThat(login(mvc, "ben@example.com").getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", refresh)))
                .andReturn().getResponse().getStatus()).isEqualTo(401);

        as(admin, "POST", "/api/admin/users/" + benId + "/enabled", "{\"enabled\":true}").andExpect(status().isNoContent());
        assertThat(login(mvc, "ben@example.com").getResponse().getStatus()).isEqualTo(200);
    }

    // ---------- cierre forzado ----------

    @Test
    void forcedLogoutRevokesRefreshAndAccessTokensButNotTheAccount() throws Exception {
        MvcResult session = login(mvc, "ben@example.com");
        String refresh = session.getResponse().getCookie("refresh_token").getValue();
        String access = accessToken(session);

        as(admin, "POST", "/api/admin/users/" + benId + "/logout", null).andExpect(status().isNoContent());

        assertThat(meStatus(access)).isEqualTo(401);
        assertThat(mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", refresh)))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(login(mvc, "ben@example.com").getResponse().getStatus()).isEqualTo(200); // puede volver a entrar
    }

    // ---------- reglas de autoprotección ----------

    @Test
    void adminCannotDisableThemselves() throws Exception {
        as(admin, "POST", "/api/admin/users/" + adminId + "/enabled", "{\"enabled\":false}")
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT enabled FROM users WHERE id = ?", Integer.class, adminId)).isEqualTo(1);
        assertThat(meStatus(admin)).isEqualTo(200);
    }

    @Test
    void adminCannotRemoveTheirOwnRole() throws Exception {
        as(admin, "POST", "/api/admin/users/" + adminId + "/role", "{\"role\":\"USER\"}").andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, adminId)).isEqualTo("ADMIN");
    }

    @Test
    void harmlessSelfActionsAreStillAllowed() throws Exception {
        as(admin, "POST", "/api/admin/users/" + adminId + "/enabled", "{\"enabled\":true}").andExpect(status().isNoContent());
        as(admin, "POST", "/api/admin/users/" + adminId + "/role", "{\"role\":\"ADMIN\"}").andExpect(status().isNoContent());
    }

    // ---------- entradas inválidas ----------

    @Test
    void unknownUserIs404AndBadInputIs400() throws Exception {
        as(admin, "POST", "/api/admin/users/999999/enabled", "{\"enabled\":false}").andExpect(status().isNotFound());
        as(admin, "POST", "/api/admin/users/999999/logout", null).andExpect(status().isNotFound());
        as(admin, "POST", "/api/admin/users/" + benId + "/role", "{\"role\":\"SUPERADMIN\"}").andExpect(status().isBadRequest());
        as(admin, "POST", "/api/admin/users/" + benId + "/enabled", "{}").andExpect(status().isBadRequest());
        as(admin, "POST", "/api/admin/users/abc/logout", null).andExpect(status().isBadRequest());
    }

    // ---------- auditoría ----------

    @Test
    void adminActionsAreAuditedWithAdminAndTargetAndNoSecrets() throws Exception {
        as(admin, "POST", "/api/admin/users/" + benId + "/enabled", "{\"enabled\":false}");
        as(admin, "POST", "/api/admin/users/" + benId + "/enabled", "{\"enabled\":true}");
        as(admin, "POST", "/api/admin/users/" + benId + "/role", "{\"role\":\"ADMIN\"}");
        as(admin, "POST", "/api/admin/users/" + benId + "/logout", null);

        List<String> rows = jdbc.queryForList("""
                SELECT event || '|' || user_id || '|' || detail FROM audit_log WHERE event LIKE 'ADMIN_%' ORDER BY id
                """, String.class);
        assertThat(rows).containsExactly(
                "ADMIN_USER_DISABLED|" + benId + "|admin=" + adminId,
                "ADMIN_USER_ENABLED|" + benId + "|admin=" + adminId,
                "ADMIN_ROLE_CHANGED|" + benId + "|admin=" + adminId + ",role=ADMIN",
                "ADMIN_FORCE_LOGOUT|" + benId + "|admin=" + adminId);
    }
}
