package com.example.backend.controller;

import com.example.backend.dto.LoginRequest;
import com.example.backend.dto.RegisterRequest;
import com.example.backend.repository.RefreshTokenRepository;
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
    private RefreshTokenRepository refreshTokenRepository;

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
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").isNumber())
                .andExpect(jsonPath("$.refreshExpiresIn").isNumber())
                .andExpect(jsonPath("$.user.email").value("nhut@example.com"));

        String loginBody = objectMapper.writeValueAsString(
                new LoginRequest("nhut@example.com", "password123"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
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
        // Đăng ký user và lấy access token hợp lệ (chữ ký đúng, chưa hết hạn).
        String registerBody = objectMapper.writeValueAsString(
                new RegisterRequest("Nhut", "orphan@example.com", "password123", null));

        String responseBody = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String token = objectMapper.readTree(responseBody).get("accessToken").asText();

        // Xóa user khỏi DB: token vẫn hợp lệ về mặt chữ ký/hạn dùng, nhưng userId trong token
        // không còn khớp user nào trong DB nữa -> loadUserById sẽ ném UsernameNotFoundException.
        // refresh_tokens.user_id FK vào users -> phải xoá refresh token trước.
        refreshTokenRepository.deleteAll();
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

    @Test
    void refreshRotatesTokenAndRevokesThePrevious() throws Exception {
        String registerBody = objectMapper.writeValueAsString(
                new RegisterRequest("Nhut", "rotate@example.com", "password123", null));
        String registerResponseBody = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String refreshToken = objectMapper.readTree(registerResponseBody).get("refreshToken").asText();
        long userId = objectMapper.readTree(registerResponseBody).get("user").get("id").asLong();

        String refreshBody = objectMapper.writeValueAsString(new RefreshBody(refreshToken));

        String secondResponseBody = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String secondRefreshToken = objectMapper.readTree(secondResponseBody).get("refreshToken").asText();
        org.assertj.core.api.Assertions.assertThat(secondRefreshToken).isNotEqualTo(refreshToken);

        // Token cũ đã bị rotate -> dùng lại phải bị từ chối (reuse detection).
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody))
                .andExpect(status().isUnauthorized());

        // Khẳng định trực tiếp ở DB (không chỉ suy luận qua status code): reuse detection phải THỰC
        // SỰ revoke mọi refresh token còn active của user, không bị rollback bởi chính exception
        // 401 vừa ném ra (AuthService.refresh cần @Transactional(noRollbackFor = BadCredentialsException.class)).
        org.assertj.core.api.Assertions.assertThat(refreshTokenRepository.countByUser_IdAndRevokedAtIsNull(userId))
                .isZero();

        // Reuse detection thu hồi CẢ session - token thứ 2 (hợp lệ, chưa dùng) cũng phải bị chặn theo.
        String secondRefreshBody = objectMapper.writeValueAsString(new RefreshBody(secondRefreshToken));
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondRefreshBody))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsUnknownRefreshToken() throws Exception {
        String body = objectMapper.writeValueAsString(new RefreshBody("not-a-real-token"));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheRefreshTokenSession() throws Exception {
        String refreshToken = registerAndGetRefreshToken("logout@example.com");
        String body = objectMapper.writeValueAsString(new RefreshBody(refreshToken));

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutAllRevokesEverySessionForTheUser() throws Exception {
        String registerResponseBody = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("Nhut", "logoutall@example.com", "password123", null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(registerResponseBody).get("accessToken").asText();
        String firstRefreshToken = objectMapper.readTree(registerResponseBody).get("refreshToken").asText();

        // Đăng nhập thêm 1 lần nữa (thiết bị thứ 2) -> phiên thứ 2.
        String loginResponseBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("logoutall@example.com", "password123"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secondRefreshToken = objectMapper.readTree(loginResponseBody).get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/logout-all")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshBody(firstRefreshToken))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshBody(secondRefreshToken))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutAllRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/auth/logout-all"))
                .andExpect(status().isUnauthorized());
    }

    private String registerAndGetRefreshToken(String email) throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterRequest("Nhut", email, "password123", null));
        String responseBody = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(responseBody).get("refreshToken").asText();
    }

    private record RefreshBody(String refreshToken) {}
}
