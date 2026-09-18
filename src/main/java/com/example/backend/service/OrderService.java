package com.example.backend.service;

import com.example.backend.dto.CheckoutRequest;
import com.example.backend.dto.OrderResponse;
import com.example.backend.dto.PaymentResponse;
import com.example.backend.entity.Address;
import com.example.backend.entity.Cart;
import com.example.backend.entity.CartItem;
import com.example.backend.entity.Order;
import com.example.backend.entity.OrderItem;
import com.example.backend.entity.OrderStatus;
import com.example.backend.entity.Payment;
import com.example.backend.entity.PaymentMethod;
import com.example.backend.entity.PaymentStatus;
import com.example.backend.entity.ProductVariant;
import com.example.backend.entity.User;
import com.example.backend.event.OrderCancelledEvent;
import com.example.backend.event.OrderPlacedEvent;
import com.example.backend.event.PaymentConfirmedEvent;
import com.example.backend.exception.InsufficientStockException;
import com.example.backend.exception.InvalidOrderStateException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.repository.AddressRepository;
import com.example.backend.repository.CartItemRepository;
import com.example.backend.repository.CartRepository;
import com.example.backend.repository.OrderRepository;
import com.example.backend.repository.ProductVariantRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.service.payment.PaymentInitResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final AddressRepository addressRepository;
    private final ProductVariantRepository productVariantRepository;
    private final UserRepository userRepository;
    private final PaymentService paymentService;
    private final ApplicationEventPublisher eventPublisher;
    private final int paymentExpiryMinutes;

    public OrderService(OrderRepository orderRepository,
                         CartRepository cartRepository,
                         CartItemRepository cartItemRepository,
                         AddressRepository addressRepository,
                         ProductVariantRepository productVariantRepository,
                         UserRepository userRepository,
                         PaymentService paymentService,
                         ApplicationEventPublisher eventPublisher,
                         @Value("${order.payment-expiry-minutes}") int paymentExpiryMinutes) {
        this.orderRepository = orderRepository;
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.addressRepository = addressRepository;
        this.productVariantRepository = productVariantRepository;
        this.userRepository = userRepository;
        this.paymentService = paymentService;
        this.eventPublisher = eventPublisher;
        this.paymentExpiryMinutes = paymentExpiryMinutes;
    }

    @Transactional
    public OrderResponse checkout(String email, CheckoutRequest request) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Cart cart = cartRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Cart is empty"));

        List<CartItem> items = cartItemRepository.findByIdInAndCartId(request.cartItemIds(), cart.getId());
        if (items.size() != request.cartItemIds().size()) {
            throw new ResourceNotFoundException("One or more cart items not found");
        }

        Address address = addressRepository.findByIdAndUserId(request.addressId(), user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Address not found: " + request.addressId()));

        // Lock từng variant TRƯỚC khi tạo order: lấy lại dòng mới nhất có PESSIMISTIC_WRITE, tránh
        // đọc bản lazy cached từ CartItem.getVariant() (có thể stale nếu 1 request khác vừa sửa
        // stock giữa lúc user load cart và lúc bấm checkout).
        List<ProductVariant> lockedVariants = new ArrayList<>();
        for (CartItem item : items) {
            ProductVariant variant = productVariantRepository.findByIdForUpdate(item.getVariant().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product variant not found"));
            if (variant.getAvailableQuantity() < item.getQuantity()) {
                throw new InsufficientStockException("Insufficient stock for SKU " + variant.getSku());
            }
            lockedVariants.add(variant);
        }

        boolean isCod = request.paymentMethod() == PaymentMethod.COD;

        Order order = Order.builder()
                .user(user)
                .status(isCod ? OrderStatus.CONFIRMED : OrderStatus.PENDING_PAYMENT)
                .paymentMethod(request.paymentMethod())
                .recipientName(address.getRecipientName())
                .phone(address.getPhone())
                .addressLine(address.getAddressLine())
                .ward(address.getWard())
                .district(address.getDistrict())
                .province(address.getProvince())
                .totalAmount(BigDecimal.ZERO)
                .expiresAt(isCod ? null : Instant.now().plus(paymentExpiryMinutes, ChronoUnit.MINUTES))
                .build();

        // COD xác nhận (CONFIRMED) và trừ stockQuantity THẬT ngay - không có gateway ngoài để chờ.
        // VNPay/Stripe chỉ RESERVE (reservedQuantity) - trừ thật xảy ra ở confirmPayment() khi
        // webhook báo thành công.
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < items.size(); i++) {
            CartItem cartItem = items.get(i);
            ProductVariant variant = lockedVariants.get(i);

            order.addItem(OrderItem.builder()
                    .variant(variant)
                    .productName(variant.getProduct().getName())
                    .sku(variant.getSku())
                    .unitPrice(variant.getPrice())
                    .quantity(cartItem.getQuantity())
                    .build());
            total = total.add(variant.getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity())));

            if (isCod) {
                variant.setStockQuantity(variant.getStockQuantity() - cartItem.getQuantity());
            } else {
                variant.setReservedQuantity(variant.getReservedQuantity() + cartItem.getQuantity());
            }
            productVariantRepository.save(variant);
        }
        order.setTotalAmount(total);

        order.assignPayment(Payment.builder()
                .gateway(request.paymentMethod())
                .status(isCod ? PaymentStatus.SUCCESS : PaymentStatus.PENDING)
                .paidAt(isCod ? Instant.now() : null)
                .build());

        orderRepository.save(order);
        cartItemRepository.deleteAll(items);

        // null cho COD (không phải PaymentInitResult.none()): OrderResponse.from() chỉ ẩn hẳn field
        // paymentInit khỏi JSON (null) khi initResult == null - truyền none() sẽ serialize thành
        // {"redirectUrl":null,"clientSecret":null}, một OBJECT không null, khác ý định "COD không
        // có gì để trả".
        PaymentInitResult initResult = isCod ? null : paymentService.initiate(order);

        eventPublisher.publishEvent(new OrderPlacedEvent(order.getId()));

        return OrderResponse.from(order, initResult);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> getOrders(String email, Pageable pageable) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return orderRepository.findByUserId(user.getId(), pageable).map(order -> OrderResponse.from(order, null));
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(String email, Long orderId) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Order order = orderRepository.findByIdAndUserId(orderId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        return OrderResponse.from(order, null);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(String email, Long orderId) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Order order = orderRepository.findByIdAndUserId(orderId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        return PaymentResponse.from(order.getPayment());
    }

    // Idempotent theo thiết kế: order != PENDING_PAYMENT nghĩa là webhook này đã được xử lý rồi
    // (gọi 2 lần) hoặc order đã bị expire - no-op, KHÔNG ném lỗi, để controller luôn trả 200 cho
    // gateway (gateway có retry thêm cũng không đổi được gì). Xem spec §6.
    @Transactional
    public void confirmPayment(Long orderId, boolean success, String gatewayTransactionRef) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            return;
        }

        if (success) {
            for (OrderItem item : order.getItems()) {
                ProductVariant variant = productVariantRepository.findByIdForUpdate(item.getVariant().getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Product variant not found"));
                variant.setStockQuantity(variant.getStockQuantity() - item.getQuantity());
                variant.setReservedQuantity(variant.getReservedQuantity() - item.getQuantity());
                productVariantRepository.save(variant);
            }
            order.setStatus(OrderStatus.CONFIRMED);
            order.getPayment().setStatus(PaymentStatus.SUCCESS);
            order.getPayment().setPaidAt(Instant.now());
            order.getPayment().setGatewayTransactionRef(gatewayTransactionRef);
            orderRepository.save(order);
            eventPublisher.publishEvent(new PaymentConfirmedEvent(order.getId()));
        } else {
            releaseReservation(order);
            order.setStatus(OrderStatus.CANCELLED);
            order.getPayment().setStatus(PaymentStatus.FAILED);
            order.getPayment().setGatewayTransactionRef(gatewayTransactionRef);
            orderRepository.save(order);
            eventPublisher.publishEvent(new OrderCancelledEvent(order.getId(), "PAYMENT_FAILED"));
        }
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> listAllOrders(OrderStatus status, Pageable pageable) {
        Page<Order> page = status != null ? orderRepository.findByStatus(status, pageable) : orderRepository.findAll(pageable);
        return page.map(order -> OrderResponse.from(order, null));
    }

    @Transactional
    public OrderResponse advanceStatus(Long orderId, OrderStatus targetStatus) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        boolean validTransition = (order.getStatus() == OrderStatus.CONFIRMED && targetStatus == OrderStatus.SHIPPED)
                || (order.getStatus() == OrderStatus.SHIPPED && targetStatus == OrderStatus.DELIVERED);
        if (!validTransition) {
            throw new InvalidOrderStateException("Cannot move order from " + order.getStatus() + " to " + targetStatus);
        }

        order.setStatus(targetStatus);
        return OrderResponse.from(orderRepository.save(order), null);
    }

    @Transactional
    public OrderResponse cancel(String email, Long orderId) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Order order = orderRepository.findByIdAndUserId(orderId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        if (order.getStatus() != OrderStatus.PENDING_PAYMENT && order.getStatus() != OrderStatus.CONFIRMED) {
            throw new InvalidOrderStateException("Order cannot be cancelled in status " + order.getStatus());
        }

        if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
            releaseReservation(order);
        } else {
            restoreStock(order);
        }

        order.setStatus(OrderStatus.CANCELLED);
        if (order.getPayment().getStatus() == PaymentStatus.PENDING) {
            order.getPayment().setStatus(PaymentStatus.FAILED);
        }
        orderRepository.save(order);

        eventPublisher.publishEvent(new OrderCancelledEvent(order.getId(), "USER_CANCELLED"));
        return OrderResponse.from(order, null);
    }

    // CONFIRMED nghĩa là stockQuantity đã bị trừ THẬT (COD lúc checkout, hoặc online sau khi
    // confirmPayment) - hoàn ngược lại stockQuantity, KHÁC với releaseReservation() (chỉ hoàn
    // reservedQuantity cho order còn PENDING_PAYMENT, chưa từng trừ thật).
    private void restoreStock(Order order) {
        for (OrderItem item : order.getItems()) {
            ProductVariant variant = productVariantRepository.findByIdForUpdate(item.getVariant().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product variant not found"));
            variant.setStockQuantity(variant.getStockQuantity() + item.getQuantity());
            productVariantRepository.save(variant);
        }
    }

    // Quét bởi OrderExpiryScheduler mỗi 60s. Mọi order trả về ở đây LUÔN đang PENDING_PAYMENT
    // (điều kiện của query) nên tái dùng releaseReservation() y hệt nhánh "payment failed" của
    // confirmPayment() - không cần phân nhánh CONFIRMED (đó là việc của cancel()).
    @Transactional
    public void expireOverdueOrders() {
        List<Order> overdue = orderRepository.findByStatusAndExpiresAtBefore(OrderStatus.PENDING_PAYMENT, Instant.now());
        for (Order order : overdue) {
            releaseReservation(order);
            order.setStatus(OrderStatus.CANCELLED);
            order.getPayment().setStatus(PaymentStatus.FAILED);
            orderRepository.save(order);
            eventPublisher.publishEvent(new OrderCancelledEvent(order.getId(), "EXPIRED"));
        }
    }

    // Hoàn reservedQuantity cho 1 order CÒN Ở TRẠNG THÁI PENDING_PAYMENT (chưa từng trừ
    // stockQuantity thật). Dùng ở đây (webhook báo fail) và ở expireOverdueOrders() - cả 2 chỗ đều
    // CHỈ xử lý order đang PENDING_PAYMENT nên dùng chung được, không cần phân nhánh CONFIRMED (đó
    // là việc của cancel(), có helper riêng vì phải hoàn stockQuantity thay vì reserved).
    private void releaseReservation(Order order) {
        for (OrderItem item : order.getItems()) {
            ProductVariant variant = productVariantRepository.findByIdForUpdate(item.getVariant().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product variant not found"));
            variant.setReservedQuantity(variant.getReservedQuantity() - item.getQuantity());
            productVariantRepository.save(variant);
        }
    }
}
