package com.example.backend.controller;

import com.example.backend.dto.RegisterRequest;
import com.example.backend.support.TestDataCleaner;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UserControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestDataCleaner testDataCleaner;

    // @SpringBootTest dùng chung 1 context/DB cho mọi test method trong class NÀY, và cho cả các
    // class IT khác chạy tuần tự trong cùng 1 lần Surefire -> phải dọn sạch TOÀN BỘ bảng liên quan
    // (không chỉ users) trước mỗi test, xem TestDataCleaner.
    @BeforeEach
    void cleanUp() {
        testDataCleaner.cleanAll();
    }

    @Test
    void returnsCurrentUserWithValidToken() throws Exception {
        String registerBody = objectMapper.writeValueAsString(
                new RegisterRequest("Nhut", "me@example.com", "password123", null));

        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andReturn().getResponse().getContentAsString();

        String token = objectMapper.readTree(response).get("token").asText();

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("me@example.com"));
    }

    @Test
    void rejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }
}
