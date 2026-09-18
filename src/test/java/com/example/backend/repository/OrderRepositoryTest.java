package com.example.backend.repository;

import com.example.backend.entity.Category;
import com.example.backend.entity.Order;
import com.example.backend.entity.OrderItem;
import com.example.backend.entity.OrderStatus;
import com.example.backend.entity.Payment;
import com.example.backend.entity.PaymentMethod;
import com.example.backend.entity.PaymentStatus;
import com.example.backend.entity.Product;
import com.example.backend.entity.ProductStatus;
import com.example.backend.entity.ProductVariant;
import com.example.backend.entity.Role;
import com.example.backend.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class OrderRepositoryTest {

    @Autowired private OrderRepository orderRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;

    @Test
    void savesOrderWithItemsAndPaymentCascaded() {
        User user = userRepository.save(User.builder()
                .name("Nhut").email("nhut@example.com").passwordHash("hashed").role(Role.CUSTOMER).build());
        ProductVariant variant = saveVariant();

        Order order = Order.builder()
                .user(user)
                .status(OrderStatus.PENDING_PAYMENT)
                .paymentMethod(PaymentMethod.VNPAY)
                .recipientName("Nhut").phone("0900000000").addressLine("123 Main St")
                .totalAmount(new BigDecimal("39.98"))
                .expiresAt(Instant.now().plus(15, ChronoUnit.MINUTES))
                .build();
        order.addItem(OrderItem.builder()
                .variant(variant).productName("T-Shirt").sku(variant.getSku())
                .unitPrice(variant.getPrice()).quantity(2).build());
        order.assignPayment(Payment.builder().gateway(PaymentMethod.VNPAY).status(PaymentStatus.PENDING).build());

        Order saved = orderRepository.save(order);

        assertThat(orderRepository.findByIdAndUserId(saved.getId(), user.getId())).isPresent();
        assertThat(orderRepository.findByIdAndUserId(saved.getId(), 999_999L)).isEmpty();
        assertThat(orderRepository.findByUserId(user.getId(), PageRequest.of(0, 10)).getContent()).hasSize(1);
        assertThat(orderRepository.findByStatus(OrderStatus.PENDING_PAYMENT, PageRequest.of(0, 10)).getContent()).hasSize(1);

        Order reloaded = orderRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getItems()).hasSize(1);
        assertThat(reloaded.getPayment().getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void findsOverdueOrdersByStatusAndExpiry() {
        User user = userRepository.save(User.builder()
                .name("Nhut").email("nhut@example.com").passwordHash("hashed").role(Role.CUSTOMER).build());

        Order overdue = orderRepository.save(Order.builder()
                .user(user).status(OrderStatus.PENDING_PAYMENT).paymentMethod(PaymentMethod.VNPAY)
                .recipientName("Nhut").phone("0900000000").addressLine("123 Main St")
                .totalAmount(BigDecimal.TEN).expiresAt(Instant.now().minus(1, ChronoUnit.MINUTES)).build());
        orderRepository.save(Order.builder()
                .user(user).status(OrderStatus.PENDING_PAYMENT).paymentMethod(PaymentMethod.VNPAY)
                .recipientName("Nhut").phone("0900000000").addressLine("123 Main St")
                .totalAmount(BigDecimal.TEN).expiresAt(Instant.now().plus(15, ChronoUnit.MINUTES)).build());

        var overdueOrders = orderRepository.findByStatusAndExpiresAtBefore(OrderStatus.PENDING_PAYMENT, Instant.now());
        assertThat(overdueOrders).extracting(Order::getId).containsExactly(overdue.getId());
    }

    private ProductVariant saveVariant() {
        Category category = categoryRepository.save(
                Category.builder().name("Fashion").slug("fashion").description("Clothes").build());
        Product product = Product.builder()
                .category(category).name("T-Shirt").slug("t-shirt").description("Basic tee")
                .status(ProductStatus.ACTIVE).build();
        product.addVariant(ProductVariant.builder()
                .sku("TSHIRT-BLK-M").size("M").color("Black").price(new BigDecimal("19.99")).stockQuantity(50).build());
        productRepository.saveAndFlush(product);
        return product.getVariants().get(0);
    }
}
