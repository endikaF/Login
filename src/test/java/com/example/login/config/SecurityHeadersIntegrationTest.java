package com.example.login.config;

import static com.example.login.AuthTestSupport.bearer;
import static com.example.login.AuthTestSupport.createAndLogin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import com.example.login.AuthTestSupport;
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

@SpringBootTest(properties = {"ADMIN_EMAIL=", "ADMIN_PASSWORD=", "JWT_SECRET=" + TestDb.JWT_SECRET})
@AutoConfigureMockMvc
class SecurityHeadersIntegrationTest {

    private static final String EVIL = "https://evil.example";
    private static final String OWN_ORIGIN = "http://localhost:8080"; // app.base-url
    private static final Path STATIC = Path.of("src/main/resources/static");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDb::newUrl);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService users;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM users");
    }

    // ---------- cabeceras ----------

    @Test
    void pagesAndApiCarryTheSecurityHeaders() throws Exception {
        for (String path : List.of("/index.html", "/api/notes")) { // una página pública y una respuesta 401 de la API
            MvcResult r = mvc.perform(get(path)).andReturn();
            assertThat(r.getResponse().getHeader("X-Content-Type-Options")).as(path).isEqualTo("nosniff");
            assertThat(r.getResponse().getHeader("X-Frame-Options")).as(path).isEqualTo("DENY");
            assertThat(r.getResponse().getHeader("Referrer-Policy")).as(path).isEqualTo("no-referrer");
            assertThat(r.getResponse().getHeader("Content-Security-Policy")).as(path).isNotBlank();
        }
    }

    @Test
    void cspIsStrict() throws Exception {
        String csp = mvc.perform(get("/index.html")).andReturn().getResponse().getHeader("Content-Security-Policy");
        assertThat(csp).contains("default-src 'none'", "script-src 'self'", "style-src 'self'",
                "frame-ancestors 'none'", "base-uri 'none'", "object-src 'none'", "form-action 'self'");
        assertThat(csp).doesNotContain("unsafe-inline").doesNotContain("unsafe-eval").doesNotContain("*");
    }

    @Test
    void hstsIsSentOverHttpsWithOneYearAndSubdomains() throws Exception {
        mvc.perform(get("/index.html").secure(true))
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"));
    }

    // ---------- el frontend cumple la CSP (nada en línea) y no usa APIs peligrosas ----------

    @Test
    void htmlPagesHaveNoInlineScriptsStylesOrHandlers() throws IOException {
        try (Stream<Path> files = Files.list(STATIC)) {
            List<Path> pages = files.filter(p -> p.toString().endsWith(".html")).toList();
            assertThat(pages).hasSizeGreaterThanOrEqualTo(6);
            for (Path page : pages) {
                String html = Files.readString(page);
                assertThat(html).as(page.toString()).doesNotContainPattern("(?i)<script(?![^>]*\\bsrc=)")
                        .doesNotContainPattern("(?i)\\son[a-z]+\\s*=")        // onclick=, onload=...
                        .doesNotContainPattern("(?i)\\sstyle\\s*=")
                        .doesNotContainPattern("(?i)<style")
                        .doesNotContainPattern("(?i)javascript:");
            }
        }
    }

    @Test
    void scriptsAvoidDangerousApisAndNeverPersistTokens() throws IOException {
        try (Stream<Path> files = Files.list(STATIC.resolve("js"))) {
            for (Path script : files.toList()) {
                String js = Files.readString(script);
                assertThat(js).as(script.toString()).doesNotContain("innerHTML").doesNotContain("outerHTML")
                        .doesNotContain("insertAdjacentHTML").doesNotContain("document.write")
                        .doesNotContain("eval(").doesNotContain("localStorage").doesNotContain("sessionStorage");
            }
        }
    }

    @Test
    void staticPagesAreServedWithoutAuthentication() throws Exception {
        for (String page : List.of("/", "/index.html", "/notes.html", "/admin.html", "/forgot-password.html",
                "/reset-password.html", "/verify-email.html", "/css/style.css", "/js/api.js")) {
            mvc.perform(get(page)).andExpect(status().isOk());
        }
    }

    // ---------- errores sin filtrar detalles ----------

    @Test
    void springErrorsAreGenericAndNeverLeakInternals() throws Exception {
        String token = createAndLogin(mvc, users, "ana@example.com", Role.USER);

        String notFound = mvc.perform(get("/api/no-existe").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Recurso no encontrado."))
                .andReturn().getResponse().getContentAsString();
        String wrongMethod = mvc.perform(get("/api/auth/login").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.message").value("Método no permitido."))
                .andReturn().getResponse().getContentAsString();
        String badType = mvc.perform(post("/api/auth/login").contentType(MediaType.TEXT_PLAIN).content("hola"))
                .andReturn().getResponse().getContentAsString();

        for (String body : List.of(notFound, wrongMethod, badType)) {
            assertThat(body).doesNotContain("Exception").doesNotContain("com.example").doesNotContain("org.springframework")
                    .doesNotContain("at java.").doesNotContain("sqlite");
        }
    }

    // ---------- CORS / CSRF ----------

    @Test
    void foreignOriginPreflightIsRejectedWithoutCorsHeaders() throws Exception {
        mvc.perform(options("/api/auth/login").header("Origin", EVIL)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void foreignOriginCannotPostEvenASimpleRequestAndGetsNoCookie() throws Exception {
        users.create("ana@example.com", AuthTestSupport.PASSWORD, Role.USER, true);
        MvcResult r = mvc.perform(post("/api/auth/login").header("Origin", EVIL).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ana@example.com\",\"password\":\"" + AuthTestSupport.PASSWORD + "\"}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(r.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void ownOriginIsAllowedAndCredentialsAreNeverEnabled() throws Exception {
        mvc.perform(options("/api/auth/login").header("Origin", OWN_ORIGIN)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", OWN_ORIGIN))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    void crossSiteRefreshWithAValidCookieIsRejectedAndDoesNotConsumeTheToken() throws Exception {
        users.create("ana@example.com", AuthTestSupport.PASSWORD, Role.USER, true);
        String refresh = AuthTestSupport.login(mvc, "ana@example.com").getResponse().getCookie("refresh_token").getValue();

        // CSRF: otro sitio intenta forzar el refresh (aunque un navegador real ni siquiera enviaría la cookie SameSite=Strict)
        assertThat(mvc.perform(post("/api/auth/refresh").header("Origin", EVIL).cookie(new Cookie("refresh_token", refresh)))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(post("/api/auth/logout").header("Origin", EVIL).cookie(new Cookie("refresh_token", refresh)))
                .andReturn().getResponse().getStatus()).isEqualTo(403);

        // el token sigue intacto y funciona desde el origen propio
        assertThat(mvc.perform(post("/api/auth/refresh").header("Origin", OWN_ORIGIN).cookie(new Cookie("refresh_token", refresh)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }
}
