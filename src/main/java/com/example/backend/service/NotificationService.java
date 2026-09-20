package com.example.backend.service;

import com.example.backend.dto.NotificationResponse;
import com.example.backend.entity.*;
import com.example.backend.event.*;
import com.example.backend.exception.InvalidRequestException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.repository.*;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class NotificationService {
  private static final List<String> CMS_TYPES = List.of("CMS_NEW_ORDER", "LOW_STOCK");
  private final OrderRepository orders;
  private final UserRepository users;
  private final NotificationRepository notifications;
  private final NotificationOutboxRepository outbox;
  private final JdbcTemplate jdbc;

  public NotificationService(
      OrderRepository orders,
      UserRepository users,
      NotificationRepository notifications,
      NotificationOutboxRepository outbox,
      JdbcTemplate jdbc) {
    this.orders = orders;
    this.users = users;
    this.notifications = notifications;
    this.outbox = outbox;
    this.jdbc = jdbc;
  }


  @Transactional
  public void recordOrderEvent(
      Long orderId, String type, String title, String message, boolean cmsAlert) {
    Order order = orders.findById(orderId).orElse(null);
    if (order == null) return;
    String key = "order:" + orderId + ":" + type; // Tạo khóa để không ghi lặp cùng một sự kiện đơn hàng.
    addNotification(order.getUser(), order, key, type, title, message);
    if (cmsAlert)
      for (User admin : users.findAllByRole(Role.ADMIN))
        addNotification(admin, order, key, "CMS_NEW_ORDER", title, message);
    if (!outbox.existsByEventKey(key)) {
      NotificationOutbox mail = new NotificationOutbox();
      mail.setEventKey(key);
      mail.setEventType(type);
      mail.setRecipient(order.getUser());
      mail.setRecipientEmail(order.getUser().getEmail());
      mail.setTemplateData("{\"orderId\":" + orderId + "}");
      outbox.save(mail);
    }
    for (OrderItem item : order.getItems()) evaluateLowStock(item.getVariant());
  }


  @Transactional(readOnly = true)
  public Page<NotificationResponse> list(
      String email, Boolean read, boolean cms, Pageable pageable) {
    if (pageable.getPageSize() > 100)
      throw new InvalidRequestException("page size cannot exceed 100");
    User user =
        users.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    Page<Notification> result;
    if (cms) {
      var types = CMS_TYPES;
      result =
          read == null
              ? notifications.findByRecipientIdAndTypeIn(user.getId(), types, pageable)
              : read
                  ? notifications.findByRecipientIdAndTypeInAndReadAtIsNotNull(
                      user.getId(), types, pageable)
                  : notifications.findByRecipientIdAndTypeInAndReadAtIsNull(
                      user.getId(), types, pageable);
    } else {
      result =
          read == null
              ? notifications.findByRecipientIdAndTypeNotIn(user.getId(), CMS_TYPES, pageable)
              : read
                  ? notifications.findByRecipientIdAndTypeNotInAndReadAtIsNotNull(
                      user.getId(), CMS_TYPES, pageable)
                  : notifications.findByRecipientIdAndTypeNotInAndReadAtIsNull(
                      user.getId(), CMS_TYPES, pageable);
    }
    return result.map(NotificationService::response);
  }


  @Transactional(readOnly = true)
  public long unreadCount(String email, boolean cms) {
    User user =
        users.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    return cms
        ? notifications.countByRecipientIdAndTypeInAndReadAtIsNull(
            user.getId(), List.of("CMS_NEW_ORDER", "LOW_STOCK"))
        : notifications.countByRecipientIdAndTypeNotInAndReadAtIsNull(user.getId(), CMS_TYPES);
  }


  @Transactional
  public NotificationResponse markRead(String email, Long notificationId, boolean cms) {
    User user =
        users.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    Notification n =
        (cms
                ? notifications.findByIdAndRecipientIdAndTypeIn(
                    notificationId, user.getId(), CMS_TYPES)
                : notifications
                    .findByIdAndRecipientId(notificationId, user.getId())
                    .filter(row -> !CMS_TYPES.contains(row.getType())))
            .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
    if (n.getReadAt() == null) {
      n.setReadAt(java.time.Instant.now());
      notifications.save(n);
    }
    return response(n);
  }


  @Transactional
  public int markAllRead(String email, boolean cms) {
    User user =
        users.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    if (cms) return notifications.markAllCmsRead(user.getId(), java.time.Instant.now());
    return notifications.markAllCustomerRead(user.getId(), CMS_TYPES, java.time.Instant.now());
  }

    private static NotificationResponse response(Notification n) {
    return new NotificationResponse(
        n.getId(),
        n.getOrder() == null ? null : n.getOrder().getId(),
        n.getType(),
        n.getTitle(),
        n.getMessage(),
        n.getReadAt() != null,
        n.getCreatedAt());
  }

    private void addNotification(
      User user, Order order, String key, String type, String title, String message) {
    if (notifications.existsByRecipientIdAndEventKey(user.getId(), key)) return; // Bỏ qua nếu người nhận đã có thông báo cùng sự kiện.
    Notification n = new Notification();
    n.setRecipient(user);
    n.setOrder(order);
    n.setEventKey(key);
    n.setType(type);
    n.setTitle(title);
    n.setMessage(message);
    notifications.save(n);
  }

    private void evaluateLowStock(ProductVariant variant) {
    int threshold = 5;
    boolean below = variant.getAvailableQuantity() <= threshold; // Kiểm tra số hàng có thể bán đã chạm ngưỡng thấp chưa.
    String database =
        jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
    boolean crossed;
    if ("H2".equalsIgnoreCase(database)) {
      List<Boolean> old =
          jdbc.query(
              "select active from low_stock_alert_states where variant_id=? and threshold=?",
              (rs, row) -> rs.getBoolean(1),
              variant.getId(),
              threshold);
      jdbc.update(
          "merge into low_stock_alert_states(variant_id,threshold,active,updated_at) "
              + "key(variant_id,threshold) values(?,?,?,current_timestamp)",
          variant.getId(),
          threshold,
          below);
      crossed = below && (old.isEmpty() || !old.get(0));
    } else {
      List<Boolean> result =
          jdbc.query(
              "insert into low_stock_alert_states(variant_id,threshold,active) values (?,?,?) on"
                  + " conflict (variant_id,threshold) do update set"
                  + " active=excluded.active,updated_at=now() where low_stock_alert_states.active"
                  + " is distinct from excluded.active returning active",
              (rs, row) -> rs.getBoolean(1),
              variant.getId(),
              threshold,
              below);
      crossed = !result.isEmpty() && result.get(0);
    }
    if (crossed) {
      String key =
          "variant:"
              + variant.getId()
              + ":low-stock:"
              + threshold
              + ":"
              + java.util.UUID.randomUUID();
      for (User admin : users.findAllByRole(Role.ADMIN)) {
        addNotification(
            admin,
            null,
            key,
            "LOW_STOCK",
            "Low stock warning",
            "SKU " + variant.getSku() + " has " + variant.getAvailableQuantity() + " available.");
      }
    }
  }


  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void onOrderPlaced(OrderPlacedEvent e) {
    recordOrderEvent(
        e.orderId(), "ORDER_PLACED", "Order placed", "Your order has been placed.", true);
  }


  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void onPaymentConfirmed(PaymentConfirmedEvent e) {
    recordOrderEvent(
        e.orderId(),
        "PAYMENT_CONFIRMED",
        "Payment confirmed",
        "Payment for your order was confirmed.",
        false);
  }


  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void onOrderCancelled(OrderCancelledEvent e) {
    if ("PAYMENT_FAILED".equals(e.reason()))
      recordOrderEvent(
          e.orderId(),
          "PAYMENT_FAILED",
          "Payment failed",
          "Payment could not be confirmed.",
          false);
    else
      recordOrderEvent(
          e.orderId(), "ORDER_CANCELLED", "Order cancelled", "Your order was cancelled.", false);
  }


  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void onStatusChanged(OrderStatusChangedEvent e) {
    if (e.status() == OrderStatus.SHIPPED)
      recordOrderEvent(
          e.orderId(), "ORDER_SHIPPED", "Order shipped", "Your order has shipped.", false);
    if (e.status() == OrderStatus.DELIVERED)
      recordOrderEvent(
          e.orderId(), "ORDER_DELIVERED", "Order delivered", "Your order was delivered.", false);
  }
}
