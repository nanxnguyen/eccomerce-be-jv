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
import com.example.backend.entity.InventoryMovementType;
import com.example.backend.entity.PaymentMethod;
import com.example.backend.entity.Role;
import com.example.backend.entity.User;
import com.example.backend.repository.OrderRepository;
import com.example.backend.repository.InventoryMovementRepository;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OrderControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private ProductVariantRepository productVariantRepository;
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
    void codCheckoutCreatesConfirmedOrderAndDeductsStockDirectly() throws Exception {
        Long variantId = seedVariant(50);
        String token = registerUser("buyer@example.com");
        Long addressId = createAddress(token);
        Long cartItemId = addToCart(token, variantId, 2);

        String body = objectMapper.writeValueAsString(new CheckoutRequest(List.of(cartItemId), addressId, PaymentMethod.COD));

        var response = mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentMethod").value("COD"))
                .andExpect(jsonPath("$.totalAmount").value(39.98))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.paymentInit").isEmpty())
                .andReturn();
        Long orderId = objectMapper.readTree(response.getResponse().getContentAsString()).get("id").asLong();
        var movements = inventoryMovementRepository.findAll();
        assertThat(movements.stream().filter(movement -> movement.getType() == InventoryMovementType.OPENING_STOCK)
                .map(movement -> movement.getStockDelta()).toList()).containsExactly(50);
        assertThat(movements.stream()
                .filter(movement -> movement.getType() == InventoryMovementType.SALE)
                .map(movement -> movement.getOrderId()).toList()).containsExactly(orderId);
    }

    @Test
    void vnpayCheckoutReservesStockAndReturnsRedirectUrl() throws Exception {
        Long variantId = seedVariant(50);
        String token = registerUser("buyer2@example.com");
        Long addressId = createAddress(token);
        Long cartItemId = addToCart(token, variantId, 3);

        String body = objectMapper.writeValueAsString(new CheckoutRequest(List.of(cartItemId), addressId, PaymentMethod.VNPAY));

        mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.paymentInit.redirectUrl").value(containsString("vnp_TxnRef=")));
    }

    @Test
    void checkoutWithInsufficientStockReturns409() throws Exception {
        Long variantId = seedVariant(1);
        String token = registerUser("buyer3@example.com");
        Long addressId = createAddress(token);
        Long cartItemId = addToCart(token, variantId, 5);

        String body = objectMapper.writeValueAsString(new CheckoutRequest(List.of(cartItemId), addressId, PaymentMethod.COD));

        mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    // Bài test BẮT BUỘC theo spec §13: 2 user cùng giành mua đơn vị hàng CUỐI CÙNG (stock=1) của
    // CÙNG 1 variant, bắn request gần như đồng thời bằng 2 thread thật. Đây là bài test duy nhất
    // thực sự "vận hành" cơ chế PESSIMISTIC_WRITE (findByIdForUpdate) - không có nó,
    // insufficientStockThrows... (OrderServiceTest) chỉ chứng minh logic đúng tuần tự, KHÔNG chứng
    // minh khoá chống race thật khi 2 request chạy song song.
    @Test
    void concurrentCheckoutOnLastUnitOnlyOneSucceeds() throws Exception {
        Long variantId = seedVariant(1);

        String tokenA = registerUser("racer-a@example.com");
        String tokenB = registerUser("racer-b@example.com");
        Long addressA = createAddress(tokenA);
        Long addressB = createAddress(tokenB);
        Long cartItemA = addToCart(tokenA, variantId, 1);
        Long cartItemB = addToCart(tokenB, variantId, 1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Callable<Integer> checkoutA = () -> performCheckout(tokenA, cartItemA, addressA);
        Callable<Integer> checkoutB = () -> performCheckout(tokenB, cartItemB, addressB);

        List<Future<Integer>> futures = executor.invokeAll(List.of(checkoutA, checkoutB));
        executor.shutdown();

        List<Integer> statusCodes = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statusCodes.add(future.get());
        }

        assertThat(statusCodes).containsExactlyInAnyOrder(201, 409);
    }

    @Test
    void listsOnlyOwnOrdersAndGetsDetailAndPayment() throws Exception {
        Long variantId = seedVariant(50);
        String tokenA = registerUser("owner@example.com");
        String tokenB = registerUser("stranger@example.com");
        Long addressA = createAddress(tokenA);
        Long cartItemA = addToCart(tokenA, variantId, 1);

        String body = objectMapper.writeValueAsString(new CheckoutRequest(List.of(cartItemA), addressA, PaymentMethod.COD));
        String response = mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        Long orderId = objectMapper.readTree(response).get("id").asLong();

        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(orderId));

        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        mockMvc.perform(get("/api/orders/{id}", orderId).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId));

        mockMvc.perform(get("/api/orders/{id}", orderId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/orders/{id}/payment", orderId).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateway").value("COD"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        byte[] invoice = mockMvc.perform(get("/api/orders/{id}/invoice.pdf", orderId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(invoice, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        mockMvc.perform(get("/api/orders/{id}/invoice.pdf", orderId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        byte[] packingSlip = mockMvc.perform(get("/api/cms/orders/{id}/packing-slip.pdf", orderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(packingSlip, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void cancelsPendingPaymentOrderAndReleasesReservation() throws Exception {
        Long variantId = seedVariant(50);
        String token = registerUser("canceler@example.com");
        Long addressId = createAddress(token);
        Long cartItemId = addToCart(token, variantId, 3);

        String body = objectMapper.writeValueAsString(new CheckoutRequest(List.of(cartItemId), addressId, PaymentMethod.VNPAY));
        String response = mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        Long orderId = objectMapper.readTree(response).get("id").asLong();

        assertThat(productVariantRepository.findById(variantId).orElseThrow().getReservedQuantity()).isEqualTo(3);

        mockMvc.perform(post("/api/orders/{id}/cancel", orderId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(productVariantRepository.findById(variantId).orElseThrow().getReservedQuantity()).isZero();
        assertThat(productVariantRepository.findById(variantId).orElseThrow().getStockQuantity()).isEqualTo(50);
    }

    @Test
    void cancelsConfirmedCodOrderAndRestoresStock() throws Exception {
        Long variantId = seedVariant(50);
        String token = registerUser("canceler2@example.com");
        Long addressId = createAddress(token);
        Long cartItemId = addToCart(token, variantId, 4);

        String body = objectMapper.writeValueAsString(new CheckoutRequest(List.of(cartItemId), addressId, PaymentMethod.COD));
        String response = mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        Long orderId = objectMapper.readTree(response).get("id").asLong();

        assertThat(productVariantRepository.findById(variantId).orElseThrow().getStockQuantity()).isEqualTo(46);

        mockMvc.perform(post("/api/orders/{id}/cancel", orderId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(productVariantRepository.findById(variantId).orElseThrow().getStockQuantity()).isEqualTo(50);
    }

    @Test
    void cannotCancelShippedOrder() throws Exception {
        Long variantId = seedVariant(50);
        String token = registerUser("canceler3@example.com");
        Long addressId = createAddress(token);
        Long cartItemId = addToCart(token, variantId, 1);

        String body = objectMapper.writeValueAsString(new CheckoutRequest(List.of(cartItemId), addressId, PaymentMethod.COD));
        String response = mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        Long orderId = objectMapper.readTree(response).get("id").asLong();

        // Nhảy thẳng status = SHIPPED bằng repository (endpoint admin advanceStatus chưa tồn tại
        // lúc này) - test này chỉ quan tâm hành vi cancel(), không quan tâm advance().
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.SHIPPED);
        orderRepository.save(order);

        mockMvc.perform(post("/api/orders/{id}/cancel", orderId).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    private int performCheckout(String token, Long cartItemId, Long addressId) throws Exception {
        String body = objectMapper.writeValueAsString(
                new CheckoutRequest(List.of(cartItemId), addressId, PaymentMethod.COD));
        return mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getStatus();
    }

    private String registerUser(String email) throws Exception {
        String body = objectMapper.writeValueAsString(
                new RegisterRequest("Buyer", email, "password123", null));
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

        String slug = "t-shirt-" + System.nanoTime();
        String productBody = objectMapper.writeValueAsString(
                new ProductRequest(categoryId, "T-Shirt", slug, "Basic tee", null));
        String productResponse = mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody))
                .andReturn().getResponse().getContentAsString();
        Long productId = objectMapper.readTree(productResponse).get("id").asLong();

        String sku = "TSHIRT-" + System.nanoTime();
        String variantBody = objectMapper.writeValueAsString(
                new ProductVariantRequest(sku, "M", "Black", new BigDecimal("19.99"), stock));
        String variantResponse = mockMvc.perform(post("/api/products/{id}/variants", productId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(variantBody))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(variantResponse).get("variants").get(0).get("id").asLong();
    }
}
