package com.example.backend.controller;

import com.example.backend.dto.LoginRequest;
import com.example.backend.dto.RegisterRequest;
import com.example.backend.repository.UserRepository;
import com.example.backend.support.TestDataCleaner;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestDataCleaner testDataCleaner;

    // Dọn TOÀN BỘ bảng liên quan (không chỉ users) trước mỗi test - xem TestDataCleaner.
    @BeforeEach
    void cleanUp() {
        testDataCleaner.cleanAll();
    }

    @Test
    void registersAndLogsIn() throws Exception {
        String registerBody = objectMapper.writeValueAsString(
                new RegisterRequest("Nhut", "nhut@example.com", "password123", null));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty());

        String loginBody = objectMapper.writeValueAsString(
                new LoginRequest("nhut@example.com", "password123"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void rejectsDuplicateRegistration() throws Exception {
        String body = objectMapper.writeValueAsString(
                new RegisterRequest("Nhut", "dup@example.com", "password123", null));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void ignoresOrphanedTokenInsteadOfCrashing() throws Exception {
        // Đăng ký user và lấy token hợp lệ (chữ ký đúng, chưa hết hạn).
        String registerBody = objectMapper.writeValueAsString(
                new RegisterRequest("Nhut", "orphan@example.com", "password123", null));

        String responseBody = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String token = objectMapper.readTree(responseBody).get("token").asText();

        // Xóa user khỏi DB: token vẫn hợp lệ về mặt chữ ký/hạn dùng, nhưng email trong token
        // không còn khớp user nào trong DB nữa -> loadUserByUsername sẽ ném UsernameNotFoundException.
        userRepository.deleteAll();

        // Gửi token "mồ côi" này tới MỘT ENDPOINT PUBLIC (/api/auth/register). JwtAuthFilter chạy
        // trên MỌI request kể cả request tới endpoint public, nên nếu filter không tự bắt lỗi này,
        // request sẽ vọt lỗi 500 dù endpoint không hề yêu cầu xác thực. Kỳ vọng: vẫn xử lý bình
        // thường (201), không phải 500.
        String secondRegisterBody = objectMapper.writeValueAsString(
                new RegisterRequest("Someone Else", "someone@example.com", "password123", null));

        mockMvc.perform(post("/api/auth/register")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondRegisterBody))
                .andExpect(status().isCreated());
    }

    @Test
    void rejectsWrongPassword() throws Exception {
        String registerBody = objectMapper.writeValueAsString(
                new RegisterRequest("Nhut", "wrong@example.com", "password123", null));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated());

        String loginBody = objectMapper.writeValueAsString(
                new LoginRequest("wrong@example.com", "wrong-password"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isUnauthorized());
    }
}
