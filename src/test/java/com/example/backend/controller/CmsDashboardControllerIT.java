package com.example.backend.controller;

import com.example.backend.entity.Order;
import com.example.backend.entity.OrderStatus;
import com.example.backend.entity.Category;
import com.example.backend.entity.Payment;
import com.example.backend.entity.PaymentMethod;
import com.example.backend.entity.PaymentStatus;
import com.example.backend.entity.Role;
import com.example.backend.entity.Product;
import com.example.backend.entity.ProductStatus;
import com.example.backend.entity.ProductVariant;
import com.example.backend.entity.User;
import com.example.backend.repository.OrderRepository;
import com.example.backend.repository.CategoryRepository;
import com.example.backend.repository.ProductRepository;
import com.example.backend.repository.ProductVariantRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.security.JwtService;
import com.example.backend.support.TestDataCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CmsDashboardControllerIT {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-02-01T00:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository productVariantRepository;
    @Autowired private JwtService jwtService;
    @Autowired private TestDataCleaner testDataCleaner;

    private String adminToken;

    @BeforeEach
    void setUp() {
        testDataCleaner.cleanAll();
        User admin = userRepository.save(User.builder().name("Admin").email("dashboard-admin@example.com")
                .passwordHash("test").role(Role.ADMIN).build());
        adminToken = jwtService.generateToken(admin.getId(), admin.getRole().name());
    }

    @Test
    void summaryCountsOnlySuccessfulPaymentsWithinHalfOpenInterval() throws Exception {
        paidOrder("12.34", PaymentStatus.SUCCESS, FROM.plusSeconds(1));
        paidOrder("99.99", PaymentStatus.SUCCESS, TO);
        paidOrder("40.00", PaymentStatus.PENDING, FROM.plusSeconds(2));
        paidOrder("60.00", PaymentStatus.FAILED, FROM.plusSeconds(3));

        mockMvc.perform(get("/api/cms/dashboard/summary")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("from", FROM.toString()).param("to", TO.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paidRevenue").value(12.34))
                .andExpect(jsonPath("$.paidOrderCount").value(1))
                .andExpect(jsonPath("$.averagePaidOrderValue").value(12.34))
                .andExpect(jsonPath("$.from").value(FROM.toString()))
                .andExpect(jsonPath("$.to").value(TO.toString()))
                .andExpect(jsonPath("$.generatedAt").isNotEmpty());

        Instant now = Instant.now();
        mockMvc.perform(get("/api/cms/dashboard/summary")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("from", now.minusSeconds(60).toString()).param("to", now.plusSeconds(60).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ordersByStatus.CONFIRMED").value(2))
                .andExpect(jsonPath("$.ordersByStatus.PENDING_PAYMENT").value(1))
                .andExpect(jsonPath("$.ordersByStatus.CANCELLED").value(1));
    }

    @Test
    void summaryReturnsZerosWhenIntervalHasNoOrders() throws Exception {
        Category category = categoryRepository.save(Category.builder().name("Low stock category").slug("low-stock").build());
        Product product = productRepository.save(Product.builder().category(category).name("Low stock")
                .slug("low-stock-product").status(ProductStatus.ACTIVE).build());
        productVariantRepository.save(ProductVariant.builder().product(product).sku("LOW-STOCK-1")
                .price(new BigDecimal("8.25")).stockQuantity(5).reservedQuantity(1).build());

        mockMvc.perform(get("/api/cms/dashboard/summary")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("from", "2035-01-01T00:00:00Z").param("to", "2035-01-02T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paidRevenue").value(0))
                .andExpect(jsonPath("$.paidOrderCount").value(0))
                .andExpect(jsonPath("$.averagePaidOrderValue").value(0))
                .andExpect(jsonPath("$.productCount").value(1))
                .andExpect(jsonPath("$.lowStockVariantCount").value(1));
    }

    @Test
    void summaryRejectsReversedOrOverlongInterval() throws Exception {
        mockMvc.perform(get("/api/cms/dashboard/summary").header("Authorization", "Bearer " + adminToken)
                        .param("from", TO.toString()).param("to", FROM.toString()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/cms/dashboard/summary").header("Authorization", "Bearer " + adminToken)
                        .param("from", "2020-01-01T00:00:00Z").param("to", "2022-01-02T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void summaryRequiresAdmin() throws Exception {
        mockMvc.perform(get("/api/cms/dashboard/summary")).andExpect(status().isUnauthorized());
    }

    @Test
    void revenueTimelineGroupsByUtcDayAndZeroFillsMissingDates() throws Exception {
        paidOrder("5.25", PaymentStatus.SUCCESS, Instant.parse("2026-01-01T00:00:00Z"));
        paidOrder("7.50", PaymentStatus.SUCCESS, Instant.parse("2026-01-02T10:30:00Z"));
        paidOrder("90.00", PaymentStatus.SUCCESS, TO);

        mockMvc.perform(get("/api/cms/dashboard/revenue").header("Authorization", "Bearer " + adminToken)
                        .param("from", FROM.toString()).param("to", "2026-01-04T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("day"))
                .andExpect(jsonPath("$.buckets.length()").value(3))
                .andExpect(jsonPath("$.buckets[0].bucketStart").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.buckets[0].paidRevenue").value(5.25))
                .andExpect(jsonPath("$.buckets[1].paidRevenue").value(7.50))
                .andExpect(jsonPath("$.buckets[2].paidRevenue").value(0))
                .andExpect(jsonPath("$.buckets[2].paidOrderCount").value(0));
    }

    @Test
    void revenueTimelineUsesMondayUtcWeeksAndCalendarMonths() throws Exception {
        paidOrder("10.00", PaymentStatus.SUCCESS, Instant.parse("2026-01-07T09:00:00Z"));
        paidOrder("20.00", PaymentStatus.SUCCESS, Instant.parse("2026-01-15T12:00:00Z"));
        paidOrder("30.00", PaymentStatus.SUCCESS, Instant.parse("2026-02-14T12:00:00Z"));

        mockMvc.perform(get("/api/cms/dashboard/revenue").header("Authorization", "Bearer " + adminToken)
                        .param("from", "2026-01-07T00:00:00Z").param("to", "2026-01-20T00:00:00Z")
                        .param("granularity", "week"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets.length()").value(3))
                .andExpect(jsonPath("$.buckets[0].bucketStart").value("2026-01-05T00:00:00Z"))
                .andExpect(jsonPath("$.buckets[1].bucketStart").value("2026-01-12T00:00:00Z"))
                .andExpect(jsonPath("$.buckets[2].bucketStart").value("2026-01-19T00:00:00Z"));

        mockMvc.perform(get("/api/cms/dashboard/revenue").header("Authorization", "Bearer " + adminToken)
                        .param("from", "2026-01-15T00:00:00Z").param("to", "2026-03-02T00:00:00Z")
                        .param("granularity", "month"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets.length()").value(3))
                .andExpect(jsonPath("$.buckets[0].bucketStart").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.buckets[1].bucketStart").value("2026-02-01T00:00:00Z"))
                .andExpect(jsonPath("$.buckets[2].bucketStart").value("2026-03-01T00:00:00Z"))
                .andExpect(jsonPath("$.buckets[2].paidRevenue").value(0));
    }

    @Test
    void revenueTimelineRejectsUnsupportedGranularityAndTooManyBuckets() throws Exception {
        mockMvc.perform(get("/api/cms/dashboard/revenue").header("Authorization", "Bearer " + adminToken)
                        .param("from", FROM.toString()).param("to", TO.toString()).param("granularity", "year"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/cms/dashboard/revenue").header("Authorization", "Bearer " + adminToken)
                        .param("from", "2024-01-01T00:00:00Z").param("to", "2026-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    private void paidOrder(String amount, PaymentStatus paymentStatus, Instant paidAt) {
        Order order = Order.builder().user(userRepository.findByEmail("dashboard-admin@example.com").orElseThrow())
                .status(paymentStatus == PaymentStatus.FAILED ? OrderStatus.CANCELLED
                        : paymentStatus == PaymentStatus.PENDING ? OrderStatus.PENDING_PAYMENT : OrderStatus.CONFIRMED)
                .paymentMethod(PaymentMethod.COD).recipientName("Test").phone("0900000000")
                .addressLine("Test address").totalAmount(new BigDecimal(amount)).build();
        order.assignPayment(Payment.builder().gateway(PaymentMethod.COD).status(paymentStatus).paidAt(paidAt).build());
        orderRepository.save(order);
    }
}
