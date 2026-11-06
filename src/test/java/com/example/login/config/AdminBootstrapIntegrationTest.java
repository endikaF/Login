package com.example.login.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.example.login.TestDb;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {"ADMIN_EMAIL=Boss@Example.com", "ADMIN_PASSWORD=contraseña-de-admin-test",
        "JWT_SECRET=" + TestDb.JWT_SECRET})
class AdminBootstrapIntegrationTest {

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDb::newUrl);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired AdminBootstrap bootstrap;

    @Test
    void createsAdminFromEnvironmentWithHashedPassword() {
        Map<String, Object> row = jdbc.queryForMap("SELECT email, password_hash, role, email_verified FROM users");
        assertThat(row.get("email")).isEqualTo("boss@example.com");
        assertThat(row.get("role")).isEqualTo("ADMIN");
        assertThat((String) row.get("password_hash")).startsWith("$argon2id$");
    }

    @Test
    void isIdempotentOnSecondRun() throws Exception {
        bootstrap.run(null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
    }
}
