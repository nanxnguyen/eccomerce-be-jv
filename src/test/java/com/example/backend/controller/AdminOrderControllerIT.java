package com.example.backend.controller;

import com.example.backend.dto.AddressRequest;
import com.example.backend.dto.CartItemAddRequest;
import com.example.backend.dto.CategoryRequest;
import com.example.backend.dto.CheckoutRequest;
import com.example.backend.dto.OrderStatusUpdateRequest;
import com.example.backend.dto.ProductRequest;
import com.example.backend.dto.ProductVariantRequest;
import com.example.backend.dto.RegisterRequest;
import com.example.backend.entity.OrderStatus;
import com.example.backend.entity.PaymentMethod;
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
import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminOrderControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private TestDataCleaner testDataCleaner;

    private String adminToken;

    @BeforeEach
    void setUp() {
        testDataCleaner.cleanAll();
        User admin = userRepository.save(User.builder()
                .name("Admin").email("admin@example.com")
                .passwordHash(passwordEncoder.encode("password123")).role(Role.ADMIN).build());
        adminToken = jwtService.generateToken(admin.getId(), admin.getRole().name());
    }

    @Test
    void nonAdminCannotListOrAdvanceOrders() throws Exception {
        String customerToken = registerUser("cust@example.com");

        mockMvc.perform(get("/api/admin/orders").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());

        String body = objectMapper.writeValueAsString(new OrderStatusUpdateRequest(OrderStatus.SHIPPED));
        mockMvc.perform(put("/api/admin/orders/{id}/status", 1L)
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminListsFiltersByStatusAndAdvancesConfirmedToShippedToDelivered() throws Exception {
        Long variantId = seedVariant(50);
        String customerToken = registerUser("cust2@example.com");
        Long addressId = createAddress(customerToken);
        Long cartItemId = addToCart(customerToken, variantId, 1);

        String checkoutBody = objectMapper.writeValueAsString(new CheckoutRequest(List.of(cartItemId), addressId, PaymentMethod.COD));
        String response = mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody))
                .andReturn().getResponse().getContentAsString();
        Long orderId = objectMapper.readTree(response).get("id").asLong();

        Instant now = Instant.now();
        mockMvc.perform(get("/api/cms/orders").header("Authorization", "Bearer " + adminToken)
                        .param("status", "CONFIRMED").param("paymentStatus", "SUCCESS")
                        .param("paymentMethod", "COD").param("buyerEmail", "cust2@example.com")
                        .param("from", now.minusSeconds(60).toString()).param("to", now.plusSeconds(60).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(orderId));

        mockMvc.perform(get("/api/cms/orders").header("Authorization", "Bearer " + adminToken)
                        .param("buyerEmail", "other@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/admin/orders").param("status", "CONFIRMED")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(orderId));

        String toShipped = objectMapper.writeValueAsString(new OrderStatusUpdateRequest(OrderStatus.SHIPPED));
        mockMvc.perform(put("/api/admin/orders/{id}/status", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toShipped))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));

        String toDelivered = objectMapper.writeValueAsString(new OrderStatusUpdateRequest(OrderStatus.DELIVERED));
        mockMvc.perform(put("/api/admin/orders/{id}/status", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toDelivered))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    @Test
    void adminCannotSkipShippedStage() throws Exception {
        Long variantId = seedVariant(50);
        String customerToken = registerUser("cust3@example.com");
        Long addressId = createAddress(customerToken);
        Long cartItemId = addToCart(customerToken, variantId, 1);

        String checkoutBody = objectMapper.writeValueAsString(new CheckoutRequest(List.of(cartItemId), addressId, PaymentMethod.COD));
        String response = mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody))
                .andReturn().getResponse().getContentAsString();
        Long orderId = objectMapper.readTree(response).get("id").asLong();

        String toDelivered = objectMapper.writeValueAsString(new OrderStatusUpdateRequest(OrderStatus.DELIVERED));
        mockMvc.perform(put("/api/admin/orders/{id}/status", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toDelivered))
                .andExpect(status().isConflict());
    }

    private String registerUser(String email) throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterRequest("Buyer", email, "password123", null));
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }

    private Long createAddress(String token) throws Exception {
        String body = objectMapper.writeValueAsString(
                new AddressRequest("Buyer", "0900000000", "123 Main St", null, null, null));
        String response = mockMvc.perform(post("/api/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private Long addToCart(String token, Long variantId, int quantity) throws Exception {
        String body = objectMapper.writeValueAsString(new CartItemAddRequest(variantId, quantity));
        String response = mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("items").get(0).get("id").asLong();
    }

    private Long seedVariant(int stock) throws Exception {
        String categoryBody = objectMapper.writeValueAsString(
                new CategoryRequest("Fashion", "fashion-" + System.nanoTime(), "Clothes"));
        String categoryResponse = mockMvc.perform(post("/api/categories")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(categoryBody))
                .andReturn().getResponse().getContentAsString();
        Long categoryId = objectMapper.readTree(categoryResponse).get("id").asLong();

        String productBody = objectMapper.writeValueAsString(
                new ProductRequest(categoryId, "T-Shirt", "t-shirt-" + System.nanoTime(), "Basic tee", null));
        String productResponse = mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody))
                .andReturn().getResponse().getContentAsString();
        Long productId = objectMapper.readTree(productResponse).get("id").asLong();

        String variantBody = objectMapper.writeValueAsString(
                new ProductVariantRequest("TSHIRT-" + System.nanoTime(), "M", "Black", new BigDecimal("19.99"), stock));
        String variantResponse = mockMvc.perform(post("/api/products/{id}/variants", productId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(variantBody))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(variantResponse).get("variants").get(0).get("id").asLong();
    }
}
