package com.example.backend.service;

import com.example.backend.dto.CheckoutRequest;
import com.example.backend.dto.OrderResponse;
import com.example.backend.entity.Address;
import com.example.backend.entity.Cart;
import com.example.backend.entity.CartItem;
import com.example.backend.entity.Category;
import com.example.backend.entity.Order;
import com.example.backend.entity.OrderItem;
import com.example.backend.entity.OrderStatus;
import com.example.backend.entity.InventoryMovementType;
import com.example.backend.entity.Payment;
import com.example.backend.entity.PaymentMethod;
import com.example.backend.entity.PaymentStatus;
import com.example.backend.entity.Product;
import com.example.backend.entity.ProductStatus;
import com.example.backend.entity.ProductVariant;
import com.example.backend.entity.Role;
import com.example.backend.entity.User;
import com.example.backend.exception.InsufficientStockException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.repository.AddressRepository;
import com.example.backend.repository.CartItemRepository;
import com.example.backend.repository.CartRepository;
import com.example.backend.repository.OrderRepository;
import com.example.backend.repository.ProductVariantRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.service.payment.PaymentInitResult;
import com.example.backend.service.InventoryMovementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final CartRepository cartRepository = mock(CartRepository.class);
    private final CartItemRepository cartItemRepository = mock(CartItemRepository.class);
    private final AddressRepository addressRepository = mock(AddressRepository.class);
    private final ProductVariantRepository productVariantRepository = mock(ProductVariantRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final InventoryMovementService inventoryMovements = mock(InventoryMovementService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private final OrderService orderService = new OrderService(
            orderRepository, cartRepository, cartItemRepository, addressRepository,
            productVariantRepository, userRepository, paymentService, inventoryMovements, eventPublisher, 15);

    private User user;
    private Cart cart;

    @BeforeEach
    void setUp() {
        user = User.builder().id(1L).name("Nhut").email("nhut@example.com").role(Role.CUSTOMER).build();
        Address address = Address.builder().id(10L).user(user).recipientName("Nhut").phone("0900000000")
                .addressLine("123 Main St").isDefault(true).build();
        cart = Cart.builder().id(100L).user(user).build();

        when(userRepository.findByEmail("nhut@example.com")).thenReturn(Optional.of(user));
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(cart));
        when(addressRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(address));
    }

    @Test
    void codCheckoutConfirmsImmediatelyAndDeductsStockDirectly() {
        ProductVariant variant = variant(200L, 50, 0);
        CartItem item = cartItem(1000L, variant, 2);
        when(cartItemRepository.findByIdInAndCartId(List.of(1000L), 100L)).thenReturn(List.of(item));
        when(productVariantRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(variant));
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CheckoutRequest request = new CheckoutRequest(List.of(1000L), 10L, PaymentMethod.COD);
        OrderResponse response = orderService.checkout("nhut@example.com", request);

        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(response.totalAmount()).isEqualTo(new BigDecimal("39.98"));
        assertThat(variant.getStockQuantity()).isEqualTo(48);
        assertThat(variant.getReservedQuantity()).isZero();
        verify(inventoryMovements).record(variant, null, InventoryMovementType.SALE, -2, 0);
        verify(cartItemRepository).deleteAll(List.of(item));
        verify(paymentService, never()).initiate(any());
    }

    @Test
    void onlinePaymentCheckoutReservesStockAndCallsGateway() {
        ProductVariant variant = variant(200L, 50, 0);
        CartItem item = cartItem(1000L, variant, 2);
        when(cartItemRepository.findByIdInAndCartId(List.of(1000L), 100L)).thenReturn(List.of(item));
        when(productVariantRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(variant));
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentService.initiate(any())).thenReturn(PaymentInitResult.clientSecret("secret"));

        CheckoutRequest request = new CheckoutRequest(List.of(1000L), 10L, PaymentMethod.STRIPE);
        OrderResponse response = orderService.checkout("nhut@example.com", request);

        assertThat(response.status()).isEqualTo("PENDING_PAYMENT");
        assertThat(response.paymentInit().clientSecret()).isEqualTo("secret");
        assertThat(variant.getStockQuantity()).isEqualTo(50);
        assertThat(variant.getReservedQuantity()).isEqualTo(2);
        verify(inventoryMovements).record(variant, null, InventoryMovementType.RESERVATION, 0, 2);
    }

    @Test
    void insufficientStockThrowsAndNeverSavesOrder() {
        ProductVariant variant = variant(200L, 1, 0);
        CartItem item = cartItem(1000L, variant, 2);
        when(cartItemRepository.findByIdInAndCartId(List.of(1000L), 100L)).thenReturn(List.of(item));
        when(productVariantRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(variant));

        CheckoutRequest request = new CheckoutRequest(List.of(1000L), 10L, PaymentMethod.COD);

        assertThatThrownBy(() -> orderService.checkout("nhut@example.com", request))
                .isInstanceOf(InsufficientStockException.class);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void requestingCartItemsNotInTheCartThrows404() {
        when(cartItemRepository.findByIdInAndCartId(List.of(1000L, 2000L), 100L))
                .thenReturn(List.of(cartItem(1000L, variant(200L, 50, 0), 1)));

        CheckoutRequest request = new CheckoutRequest(List.of(1000L, 2000L), 10L, PaymentMethod.COD);

        assertThatThrownBy(() -> orderService.checkout("nhut@example.com", request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void expireOverdueOrdersReleasesReservationAndCancelsOrder() {
        ProductVariant variant = variant(200L, 50, 3);
        Order order = Order.builder().id(500L).status(OrderStatus.PENDING_PAYMENT).paymentMethod(PaymentMethod.VNPAY)
                .recipientName("Nhut").phone("0900000000").addressLine("123 Main St")
                .totalAmount(new BigDecimal("59.97")).expiresAt(Instant.now().minusSeconds(60)).build();
        order.addItem(OrderItem.builder().variant(variant).productName("T-Shirt").sku("TSHIRT-M")
                .unitPrice(new BigDecimal("19.99")).quantity(3).build());
        order.assignPayment(Payment.builder().gateway(PaymentMethod.VNPAY).status(PaymentStatus.PENDING).build());

        when(orderRepository.findByStatusAndExpiresAtBefore(eq(OrderStatus.PENDING_PAYMENT), any())).thenReturn(List.of(order));
        when(productVariantRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(variant));

        orderService.expireOverdueOrders();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getPayment().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(variant.getReservedQuantity()).isZero();
        verify(inventoryMovements).record(variant, 500L, InventoryMovementType.RESERVATION_RELEASED, 0, -3);
        verify(orderRepository).save(order);
    }

    @Test
    void successfulPaymentRecordsStockAndReservationChangesTogether() {
        ProductVariant variant = variant(200L, 50, 2);
        Order order = orderWithOneItem(variant, OrderStatus.PENDING_PAYMENT, PaymentStatus.PENDING);
        when(orderRepository.findById(500L)).thenReturn(Optional.of(order));
        when(productVariantRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(variant));

        orderService.confirmPayment(500L, true, "txn-1");

        assertThat(variant.getStockQuantity()).isEqualTo(48);
        assertThat(variant.getReservedQuantity()).isZero();
        verify(inventoryMovements).record(variant, 500L, InventoryMovementType.PAYMENT_CAPTURED, -2, -2);
    }

    @Test
    void cancelConfirmedOrderRecordsReturnedStock() {
        ProductVariant variant = variant(200L, 48, 0);
        Order order = orderWithOneItem(variant, OrderStatus.CONFIRMED, PaymentStatus.SUCCESS);
        when(orderRepository.findByIdAndUserId(500L, 1L)).thenReturn(Optional.of(order));
        when(productVariantRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(variant));

        orderService.cancel("nhut@example.com", 500L);

        assertThat(variant.getStockQuantity()).isEqualTo(50);
        verify(inventoryMovements).record(variant, 500L, InventoryMovementType.RESTOCKED, 2, 0);
    }

    private Order orderWithOneItem(ProductVariant variant, OrderStatus status, PaymentStatus paymentStatus) {
        Order order = Order.builder().id(500L).user(user).status(status).paymentMethod(PaymentMethod.STRIPE)
                .recipientName("Nhut").phone("0900000000").addressLine("123 Main St")
                .totalAmount(new BigDecimal("39.98")).build();
        order.addItem(OrderItem.builder().variant(variant).productName("T-Shirt").sku("TSHIRT-M")
                .unitPrice(new BigDecimal("19.99")).quantity(2).build());
        order.assignPayment(Payment.builder().gateway(PaymentMethod.STRIPE).status(paymentStatus).build());
        return order;
    }

    private ProductVariant variant(Long id, int stock, int reserved) {
        Category category = Category.builder().id(1L).name("Fashion").slug("fashion").build();
        Product product = Product.builder().id(1L).category(category).name("T-Shirt").slug("t-shirt")
                .status(ProductStatus.ACTIVE).build();
        return ProductVariant.builder().id(id).product(product).sku("TSHIRT-M")
                .price(new BigDecimal("19.99")).stockQuantity(stock).reservedQuantity(reserved).build();
    }

    private CartItem cartItem(Long id, ProductVariant variant, int quantity) {
        return CartItem.builder().id(id).cart(cart).variant(variant).quantity(quantity).build();
    }
}
