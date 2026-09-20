package com.example.backend.service;

import com.example.backend.dto.CheckoutRequest; // CheckoutRequest (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.OrderResponse; // OrderResponse (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.PaymentResponse; // PaymentResponse (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.entity.Address; // Address (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.Cart; // Cart (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.CartItem; // CartItem (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.Order; // Order (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.OrderItem; // OrderItem (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.OrderStatus; // OrderStatus (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.Payment; // Payment (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.PaymentMethod; // PaymentMethod (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.PaymentStatus; // PaymentStatus (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.ProductVariant; // ProductVariant (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.User; // User (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.event.OrderCancelledEvent; // OrderCancelledEvent (sự kiện nối các bước xử lý).
import com.example.backend.event.OrderPlacedEvent; // OrderPlacedEvent (sự kiện nối các bước xử lý).
import com.example.backend.event.PaymentConfirmedEvent; // PaymentConfirmedEvent (sự kiện nối các bước xử lý).
import com.example.backend.event.OrderStatusChangedEvent; // OrderStatusChangedEvent (sự kiện nối các bước xử lý).
import com.example.backend.exception.InsufficientStockException; // InsufficientStockException (loại lỗi nghiệp vụ hoặc dữ liệu).
import com.example.backend.exception.InvalidRequestException; // InvalidRequestException (loại lỗi nghiệp vụ hoặc dữ liệu).
import com.example.backend.exception.InvalidOrderStateException; // InvalidOrderStateException (loại lỗi nghiệp vụ hoặc dữ liệu).
import com.example.backend.exception.ResourceNotFoundException; // ResourceNotFoundException (loại lỗi nghiệp vụ hoặc dữ liệu).
import com.example.backend.repository.AddressRepository; // AddressRepository (repository truy vấn/lưu dữ liệu qua JPA).
import com.example.backend.repository.CartItemRepository; // CartItemRepository (repository truy vấn/lưu dữ liệu qua JPA).
import com.example.backend.repository.CartRepository; // CartRepository (repository truy vấn/lưu dữ liệu qua JPA).
import com.example.backend.repository.OrderRepository; // OrderRepository (repository truy vấn/lưu dữ liệu qua JPA).
import com.example.backend.repository.ProductVariantRepository; // ProductVariantRepository (repository truy vấn/lưu dữ liệu qua JPA).
import com.example.backend.repository.UserRepository; // UserRepository (repository truy vấn/lưu dữ liệu qua JPA).
import com.example.backend.service.payment.PaymentInitResult; // PaymentInitResult (payment).
import org.springframework.beans.factory.annotation.Value; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Value).
import org.springframework.context.ApplicationEventPublisher; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (ApplicationEventPublisher).
import org.springframework.data.domain.Page; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Page).
import org.springframework.data.domain.PageRequest; // kiểu Spring Data hỗ trợ truy cập/phân trang database (PageRequest).
import org.springframework.data.domain.Pageable; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Pageable).
import org.springframework.data.domain.Sort; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Sort).
import org.springframework.stereotype.Service; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Service).
import org.springframework.transaction.annotation.Transactional; // quản lý transaction database (Transactional).

import java.math.BigDecimal; // tính tiền chính xác, tránh sai số số thực.
import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).
import java.time.Duration; // kiểu/thao tác thời gian chuẩn Java (Duration).
import java.time.temporal.ChronoUnit; // kiểu/thao tác thời gian chuẩn Java (ChronoUnit).
import java.util.ArrayList; // danh sách có thể thêm phần tử.
import java.util.List; // danh sách phần tử cùng kiểu.

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

    // Gộp mọi lần đọc/ghi DB trong checkout thành một transaction: lỗi giữa chừng thì rollback hết.
    @Transactional
    public OrderResponse checkout(String email, CheckoutRequest request) {
        // 1) Tìm chủ tài khoản bằng email lấy từ token đăng nhập.
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // 2) Tìm giỏ hàng của đúng user; không có giỏ thì dừng request.
        Cart cart = cartRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Cart is empty"));

        // 3) Chỉ lấy các dòng giỏ vừa được gửi và thuộc cart của user này.
        List<CartItem> items = cartItemRepository.findByIdInAndCartId(request.cartItemIds(), cart.getId());
        // So sánh số dòng tìm được với số ID client gửi để phát hiện ID sai/không thuộc giỏ.
        if (items.size() != request.cartItemIds().size()) {
            throw new ResourceNotFoundException("One or more cart items not found");
        }

        // 4) Kiểm tra địa chỉ vừa tồn tại vừa thuộc user hiện tại.
        Address address = addressRepository.findByIdAndUserId(request.addressId(), user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Address not found: " + request.addressId()));

        // Lock từng variant TRƯỚC khi tạo order: lấy lại dòng mới nhất có PESSIMISTIC_WRITE, tránh
        // đọc bản lazy cached từ CartItem.getVariant() (có thể stale nếu 1 request khác vừa sửa
        // stock giữa lúc user load cart và lúc bấm checkout).
        // Giữ các variant vừa khóa theo đúng thứ tự items để ghép ở vòng tạo OrderItem.
        List<ProductVariant> lockedVariants = new ArrayList<>();
        // 5) Khóa và kiểm tra từng dòng tồn kho trước khi tạo đơn.
        for (CartItem item : items) {
            // SELECT ... FOR UPDATE đọc bản mới nhất và ngăn transaction khác sửa cùng variant.
            ProductVariant variant = productVariantRepository.findByIdForUpdate(item.getVariant().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product variant not found"));
            // Số có thể bán = tồn thật - số đã giữ cho đơn chờ thanh toán.
            if (variant.getAvailableQuantity() < item.getQuantity()) {
                throw new InsufficientStockException("Insufficient stock for SKU " + variant.getSku());
            }
            // Lưu object đã lock; transaction giữ lock tới commit hoặc rollback.
            lockedVariants.add(variant);
        }

        // COD xử lý ngay; các phương thức online còn phải chờ gateway xác nhận.
        boolean isCod = request.paymentMethod() == PaymentMethod.COD;

        // 6) Dựng entity đơn trong bộ nhớ; địa chỉ được sao chép vào đơn làm snapshot lịch sử.
        Order order = Order.builder()
                .user(user) // Lưu khóa ngoại user_id để biết ai đặt đơn.
                .status(isCod ? OrderStatus.CONFIRMED : OrderStatus.PENDING_PAYMENT) // COD xác nhận ngay, online chờ trả tiền.
                .paymentMethod(request.paymentMethod()) // Ghi phương thức khách chọn.
                .recipientName(address.getRecipientName()) // Chụp tên người nhận vào đơn.
                .phone(address.getPhone()) // Chụp số liên hệ vào đơn.
                .addressLine(address.getAddressLine()) // Chụp địa chỉ đường/số nhà.
                .ward(address.getWard()) // Chụp phường/xã.
                .district(address.getDistrict()) // Chụp quận/huyện.
                .province(address.getProvince()) // Chụp tỉnh/thành.
                .totalAmount(BigDecimal.ZERO) // Tạm đặt 0, vòng lặp bên dưới sẽ cộng tiền.
                .expiresAt(isCod ? null : Instant.now().plus(paymentExpiryMinutes, ChronoUnit.MINUTES)) // Online có thời hạn thanh toán.
                .build(); // Kết thúc builder và tạo object Order trong bộ nhớ.

        // COD xác nhận (CONFIRMED) và trừ stockQuantity THẬT ngay - không có gateway ngoài để chờ.
        // VNPay/Stripe chỉ RESERVE (reservedQuantity) - trừ thật xảy ra ở confirmPayment() khi
        // webhook báo thành công.
        // Bắt đầu tổng tiền từ 0; BigDecimal tránh sai số số thực khi tính tiền.
        BigDecimal total = BigDecimal.ZERO;
        // Ghép từng dòng giỏ với variant đã khóa ở cùng vị trí.
        for (int i = 0; i < items.size(); i++) {
            CartItem cartItem = items.get(i);
            ProductVariant variant = lockedVariants.get(i);

            // Tạo dòng lịch sử với tên, SKU và giá snapshot tại thời điểm mua.
            order.addItem(OrderItem.builder()
                    .variant(variant) // Lưu liên kết tới SKU gốc.
                    .productName(variant.getProduct().getName()) // Lưu tên tại thời điểm mua.
                    .sku(variant.getSku()) // Lưu SKU tại thời điểm mua.
                    .unitPrice(variant.getPrice()) // Lưu giá tại thời điểm mua.
                    .quantity(cartItem.getQuantity()) // Lưu số lượng từ giỏ hàng.
                    .build()); // Tạo OrderItem rồi gắn vào Order.
            // Cộng đơn giá nhân số lượng vào tổng đơn.
            total = total.add(variant.getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity())));

            // COD trừ tồn thật; online giữ hàng bằng reservedQuantity trong khi chờ thanh toán.
            if (isCod) {
                variant.setStockQuantity(variant.getStockQuantity() - cartItem.getQuantity());
            } else {
                variant.setReservedQuantity(variant.getReservedQuantity() + cartItem.getQuantity());
            }
            // Đưa thay đổi tồn/giữ hàng vào cùng transaction với việc tạo đơn.
            productVariantRepository.save(variant);
        }
        // Gán tổng tiền vừa tính cho Order.
        order.setTotalAmount(total);

        // Tạo bản ghi thanh toán gắn với đơn; COD thành công ngay, online bắt đầu ở trạng thái chờ.
        order.assignPayment(Payment.builder()
                .gateway(request.paymentMethod()) // Ghi COD/VNPAY/STRIPE vào payment.
                .status(isCod ? PaymentStatus.SUCCESS : PaymentStatus.PENDING) // COD đã trả; online đang chờ gateway.
                .paidAt(isCod ? Instant.now() : null) // COD có giờ trả ngay; online chờ webhook.
                .build()); // Tạo Payment và gắn hai chiều với Order.

        // Lưu Order; cascade từ Order sẽ lưu kèm OrderItem và Payment.
        orderRepository.save(order);
        // Xóa đúng các mặt hàng đã checkout, không xóa phần còn lại trong giỏ.
        cartItemRepository.deleteAll(items);

        // null cho COD (không phải PaymentInitResult.none()): OrderResponse.from() chỉ ẩn hẳn field
        // paymentInit khỏi JSON (null) khi initResult == null - truyền none() sẽ serialize thành
        // {"redirectUrl":null,"clientSecret":null}, một OBJECT không null, khác ý định "COD không
        // có gì để trả".
        // COD không gọi cổng ngoài; VNPay/Stripe khởi tạo phiên và trả URL/secret cho client.
        PaymentInitResult initResult = isCod ? null : paymentService.initiate(order);

        // Phát event đồng bộ; listener hiện chỉ ghi log. Muốn chạy sau commit cần listener transaction-aware.
        eventPublisher.publishEvent(new OrderPlacedEvent(order.getId()));

        // Đổi entity thành DTO; controller trả DTO này thành JSON cho client.
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

    @Transactional(readOnly = true)
    public Page<OrderResponse> listCmsOrders(OrderStatus status, Instant from, Instant to,
                                              PaymentStatus paymentStatus, PaymentMethod paymentMethod,
                                              String buyerEmail, Pageable pageable) {
        if (from != null && to != null) {
            if (!from.isBefore(to)) throw new InvalidRequestException("from must be before to");
            if (Duration.between(from, to).compareTo(Duration.ofDays(366)) > 0) {
                throw new InvalidRequestException("Date range cannot exceed 366 days");
            }
        }
        if (pageable.getPageSize() > 100) throw new InvalidRequestException("page size cannot exceed 100");
        String normalizedEmail = buyerEmail == null || buyerEmail.isBlank() ? null : buyerEmail.trim();
        Pageable stablePage = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                pageable.getSort().and(Sort.by(Sort.Direction.DESC, "id")));
        return orderRepository.findCmsOrders(status, from, to, paymentStatus, paymentMethod, normalizedEmail, stablePage)
                .map(order -> OrderResponse.from(order, null));
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
        Order saved = orderRepository.save(order);
        eventPublisher.publishEvent(new OrderStatusChangedEvent(saved.getId(), targetStatus));
        return OrderResponse.from(saved, null);
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
