package com.example.backend.controller;

import com.example.backend.dto.AddressRequest;
import com.example.backend.dto.CartItemAddRequest;
import com.example.backend.dto.CategoryRequest;
import com.example.backend.dto.CheckoutRequest;
import com.example.backend.dto.ProductRequest;
import com.example.backend.dto.ProductVariantRequest;
import com.example.backend.dto.RegisterRequest;
import com.example.backend.entity.Order;
import com.example.backend.entity.OrderStatus;
import com.example.backend.entity.PaymentMethod;
import com.example.backend.entity.PaymentStatus;
import com.example.backend.entity.ProductVariant;
import com.example.backend.entity.Role;
import com.example.backend.entity.User;
import com.example.backend.repository.OrderRepository;
import com.example.backend.repository.ProductVariantRepository;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentWebhookControllerIT {

    // Trùng vnpay.hash-secret trong src/test/resources/application.properties. Test ký request IPN
    // giả lập bằng thuật toán ĐỘC LẬP (không gọi lại VnPayGateway.buildQuery/hmacSha512 - package-
    // private ở package khác) để verify hành vi đúng từ góc nhìn 1 client ngoài thật (VNPay sandbox)
    // sẽ gọi, không phải verify implementation detail nội bộ.
    private static final String VNPAY_HASH_SECRET = "TEST_HASH_SECRET_AT_LEAST_THIS_LONG";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductVariantRepository productVariantRepository;
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
    void validWebhookConfirmsOrderAndCommitsStockThenIgnoresSecondCall() throws Exception {
        Long variantId = seedVariant(50);
        String token = registerUser("buyer@example.com");
        Long addressId = createAddress(token);
        Long cartItemId = addToCart(token, variantId, 3);
        Long orderId = checkoutVnPay(token, cartItemId, addressId);

        ProductVariant beforeWebhook = productVariantRepository.findById(variantId).orElseThrow();
        assertThat(beforeWebhook.getReservedQuantity()).isEqualTo(3);
        assertThat(beforeWebhook.getStockQuantity()).isEqualTo(50);

        Map<String, String> params = new TreeMap<>();
        params.put("vnp_TxnRef", orderId.toString());
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_TransactionNo", "VNP123");
        String hash = sign(params);

        mockMvc.perform(post("/api/payments/webhooks/vnpay")
                        .param("vnp_TxnRef", orderId.toString())
                        .param("vnp_ResponseCode", "00")
                        .param("vnp_TransactionNo", "VNP123")
                        .param("vnp_SecureHash", hash))
                .andExpect(status().isOk());

        Order confirmed = orderRepository.findById(orderId).orElseThrow();
        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.getPayment().getStatus()).isEqualTo(PaymentStatus.SUCCESS);

        ProductVariant afterWebhook = productVariantRepository.findById(variantId).orElseThrow();
        assertThat(afterWebhook.getStockQuantity()).isEqualTo(47);
        assertThat(afterWebhook.getReservedQuantity()).isZero();

        // VNPay có thể retry IPN - order đã CONFIRMED nên lần gọi thứ 2 phải no-op, KHÔNG trừ thêm
        // stock lần 2 (đây chính là hành vi idempotent bắt buộc theo spec §6).
        mockMvc.perform(post("/api/payments/webhooks/vnpay")
                        .param("vnp_TxnRef", orderId.toString())
                        .param("vnp_ResponseCode", "00")
                        .param("vnp_TransactionNo", "VNP123")
                        .param("vnp_SecureHash", hash))
                .andExpect(status().isOk());

        ProductVariant afterSecondCall = productVariantRepository.findById(variantId).orElseThrow();
        assertThat(afterSecondCall.getStockQuantity()).isEqualTo(47);
    }

    @Test
    void webhookWithInvalidSignatureReturns400AndDoesNotTouchOrder() throws Exception {
        Long variantId = seedVariant(50);
        String token = registerUser("buyer2@example.com");
        Long addressId = createAddress(token);
        Long cartItemId = addToCart(token, variantId, 1);
        Long orderId = checkoutVnPay(token, cartItemId, addressId);

        mockMvc.perform(post("/api/payments/webhooks/vnpay")
                        .param("vnp_TxnRef", orderId.toString())
                        .param("vnp_ResponseCode", "00")
                        .param("vnp_SecureHash", "not-a-real-hash"))
                .andExpect(status().isBadRequest());

        Order stillPending = orderRepository.findById(orderId).orElseThrow();
        assertThat(stillPending.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    private Long checkoutVnPay(String token, Long cartItemId, Long addressId) throws Exception {
        String body = objectMapper.writeValueAsString(
                new CheckoutRequest(List.of(cartItemId), addressId, PaymentMethod.VNPAY));
        String response = mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private String sign(Map<String, String> params) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (query.length() > 0) query.append('&');
            query.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                 .append('=')
                 .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        try {
            Mac hmac512 = Mac.getInstance("HmacSHA512");
            hmac512.init(new SecretKeySpec(VNPAY_HASH_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] result = hmac512.doFinal(query.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : result) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
