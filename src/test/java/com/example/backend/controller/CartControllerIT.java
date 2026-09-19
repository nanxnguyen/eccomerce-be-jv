package com.example.backend.controller;

import com.example.backend.dto.CartItemAddRequest;
import com.example.backend.dto.CartItemQuantityRequest;
import com.example.backend.dto.CategoryRequest;
import com.example.backend.dto.ProductRequest;
import com.example.backend.dto.ProductVariantRequest;
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

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CartControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private TestDataCleaner testDataCleaner;

    private String token;
    private String adminToken;
    private Long variantId;

    // Dọn TOÀN BỘ bảng liên quan trước mỗi test - cả suite dùng chung 1 H2 instance nên rác từ
    // class KHÁC (Address, Product...) cũng có thể chặn deleteAll() ở đây. Xem TestDataCleaner.
    @BeforeEach
    void setUp() throws Exception {
        testDataCleaner.cleanAll();
        User customer = userRepository.save(User.builder()
                .name("Nhut").email("nhut@example.com")
                .passwordHash(passwordEncoder.encode("password123")).role(Role.CUSTOMER).build());
        token = jwtService.generateToken(customer.getId(), customer.getRole().name());

        User admin = userRepository.save(User.builder()
                .name("Admin").email("admin@example.com")
                .passwordHash(passwordEncoder.encode("password123")).role(Role.ADMIN).build());
        adminToken = jwtService.generateToken(admin.getId(), admin.getRole().name());

        variantId = seedVariant();
    }

    @Test
    void addingSameVariantTwiceIncrementsQuantityInsteadOfDuplicating() throws Exception {
        addItem(variantId, 2);
        addItem(variantId, 3);

        mockMvc.perform(get("/api/cart").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(5))
                // Viết literal 99.95 thay vì biểu thức "5 * 19.99": phép nhân double trong Java cho
                // 99.94999999999999 (lỗi làm tròn số thực dấu phẩy động), trong khi server tính bằng
                // BigDecimal ra đúng 99.95 - jsonPath so khớp double nên literal đúng mới match.
                .andExpect(jsonPath("$.totalAmount").value(99.95));
    }

    @Test
    void updatesAndRemovesItem() throws Exception {
        Long itemId = addItem(variantId, 2);

        String updateBody = objectMapper.writeValueAsString(new CartItemQuantityRequest(5));
        mockMvc.perform(put("/api/cart/items/{itemId}", itemId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(5));

        mockMvc.perform(delete("/api/cart/items/{itemId}", itemId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void addingUnknownVariantReturns404() throws Exception {
        String body = objectMapper.writeValueAsString(new CartItemAddRequest(999_999L, 1));
        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    private Long addItem(Long variantId, int quantity) throws Exception {
        String body = objectMapper.writeValueAsString(new CartItemAddRequest(variantId, quantity));
        String response = mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("items").get(0).get("id").asLong();
    }

    private Long seedVariant() throws Exception {
        String categoryBody = objectMapper.writeValueAsString(new CategoryRequest("Fashion", "fashion", "Clothes"));
        String categoryResponse = mockMvc.perform(post("/api/categories")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(categoryBody))
                .andReturn().getResponse().getContentAsString();
        Long categoryId = objectMapper.readTree(categoryResponse).get("id").asLong();

        String productBody = objectMapper.writeValueAsString(
                new ProductRequest(categoryId, "T-Shirt", "t-shirt", "Basic tee", null));
        String productResponse = mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody))
                .andReturn().getResponse().getContentAsString();
        Long productId = objectMapper.readTree(productResponse).get("id").asLong();

        String variantBody = objectMapper.writeValueAsString(
                new ProductVariantRequest("TSHIRT-BLK-M", "M", "Black", new BigDecimal("19.99"), 50));
        String variantResponse = mockMvc.perform(post("/api/products/{id}/variants", productId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(variantBody))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(variantResponse).get("variants").get(0).get("id").asLong();
    }
}
