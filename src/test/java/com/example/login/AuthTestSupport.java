package com.example.login;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;

import com.example.login.user.Role;
import com.example.login.user.UserService;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Ayudas para los tests: crear usuarios verificados e iniciar sesión. */
public final class AuthTestSupport {

    public static final String PASSWORD = "una contraseña larga y segura";
    private static final ObjectMapper JSON = new ObjectMapper();

    private AuthTestSupport() {
    }

    public static MvcResult login(MockMvc mvc, String email) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("email", email, "password", PASSWORD)))).andReturn();
    }

    public static String accessToken(MvcResult login) throws Exception {
        return JSON.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }

    /** Crea el usuario (email verificado) y devuelve su access token. */
    public static String createAndLogin(MockMvc mvc, UserService users, String email, Role role) throws Exception {
        users.create(email, PASSWORD, role, true);
        return accessToken(login(mvc, email));
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }
}
