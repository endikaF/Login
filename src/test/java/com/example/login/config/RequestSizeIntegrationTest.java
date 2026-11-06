package com.example.login.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.login.TestDb;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"ADMIN_EMAIL=", "ADMIN_PASSWORD=", "JWT_SECRET=" + TestDb.JWT_SECRET})
@AutoConfigureMockMvc
class RequestSizeIntegrationTest {

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDb::newUrl);
    }

    @Autowired MockMvc mvc;

    @Test
    void oversizedBodyIsRejectedBeforeBeingParsed() throws Exception {
        String huge = "{\"email\":\"a@example.com\",\"password\":\"" + "a".repeat(70_000) + "\"}";
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(huge))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(huge))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void chunkedBodiesWithoutDeclaredSizeAreRejected() throws Exception {
        mvc.perform(post("/api/auth/login").header("Transfer-Encoding", "chunked")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isLengthRequired());
    }

    @Test
    void normalSizedBodiesAndGetRequestsAreUnaffected() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@example.com\",\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/index.html")).andExpect(status().isOk());
    }
}
