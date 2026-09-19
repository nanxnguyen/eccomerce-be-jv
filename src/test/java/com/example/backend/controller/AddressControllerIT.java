package com.example.backend.controller;

import com.example.backend.dto.AddressRequest;
import com.example.backend.entity.Role;
import com.example.backend.entity.User;
import com.example.backend.repository.UserRepository;
import com.example.backend.security.JwtService;
import com.example.backend.support.TestDataCleaner;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AddressControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private TestDataCleaner testDataCleaner;

    private String token;

    // Dọn TOÀN BỘ bảng liên quan trước mỗi test (không chỉ addresses+users) - cả suite dùng chung
    // 1 H2 instance nên rác từ class KHÁC (Cart, Product...) cũng có thể chặn deleteAll() ở đây.
    // Xem TestDataCleaner.
    @BeforeEach
    void setUp() {
        testDataCleaner.cleanAll();
        User user = userRepository.save(User.builder()
                .name("Nhut").email("nhut@example.com")
                .passwordHash(passwordEncoder.encode("password123")).role(Role.CUSTOMER).build());
        token = jwtService.generateToken(user.getId(), user.getRole().name());
    }

    @Test
    void firstAddressBecomesDefaultAutomatically() throws Exception {
        String body = objectMapper.writeValueAsString(
                new AddressRequest("Nhut", "0900000000", "123 Main St", "Ward 1", "District 1", "HCMC"));

        mockMvc.perform(post("/api/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isDefault").value(true));

        String secondBody = objectMapper.writeValueAsString(
                new AddressRequest("Nhut", "0900000000", "456 Side St", null, null, null));

        mockMvc.perform(post("/api/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isDefault").value(false));

        mockMvc.perform(get("/api/addresses")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void settingNewDefaultUnsetsThePreviousOne() throws Exception {
        Long first = createAddress("123 Main St");
        Long second = createAddress("456 Side St");

        mockMvc.perform(put("/api/addresses/{id}/default", second)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));

        mockMvc.perform(get("/api/addresses")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$[?(@.id == " + first + ")].isDefault").value(false))
                .andExpect(jsonPath("$[?(@.id == " + second + ")].isDefault").value(true));
    }

    @Test
    void cannotUpdateOrDeleteAnotherUsersAddress() throws Exception {
        Long addressId = createAddress("123 Main St");

        User other = userRepository.save(User.builder()
                .name("Other").email("other@example.com")
                .passwordHash(passwordEncoder.encode("password123")).role(Role.CUSTOMER).build());
        String otherToken = jwtService.generateToken(other.getId(), other.getRole().name());

        String body = objectMapper.writeValueAsString(
                new AddressRequest("Hacker", "0999999999", "999 Nowhere", null, null, null));

        mockMvc.perform(put("/api/addresses/{id}", addressId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/addresses/{id}", addressId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesAddress() throws Exception {
        Long addressId = createAddress("123 Main St");

        mockMvc.perform(delete("/api/addresses/{id}", addressId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/addresses")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(0));
    }

    private Long createAddress(String addressLine) throws Exception {
        String body = objectMapper.writeValueAsString(
                new AddressRequest("Nhut", "0900000000", addressLine, null, null, null));
        String response = mockMvc.perform(post("/api/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }
}
