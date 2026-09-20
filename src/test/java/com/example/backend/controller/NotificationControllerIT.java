package com.example.backend.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.backend.entity.*;
import com.example.backend.event.OrderPlacedEvent;
import com.example.backend.repository.*;
import com.example.backend.security.JwtService;
import com.example.backend.support.TestDataCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class NotificationControllerIT {
  @Autowired MockMvc mvc;
  @Autowired UserRepository users;
  @Autowired NotificationRepository notifications;
  @Autowired NotificationOutboxRepository outbox;
  @Autowired OrderRepository orders;
  @Autowired ApplicationEventPublisher events;
  @Autowired TransactionTemplate transactions;
  @Autowired JwtService jwt;
  @Autowired TestDataCleaner cleaner;
  String customerToken, otherToken, adminToken;
  User customer, other;

  @BeforeEach
  void setup() {
    cleaner.cleanAll();
    customer = user("customer@example.com", Role.CUSTOMER);
    other = user("other@example.com", Role.CUSTOMER);
    User admin = user("notify-admin@example.com", Role.ADMIN);
    customerToken = jwt.generateToken(customer.getId(), "CUSTOMER");
    otherToken = jwt.generateToken(other.getId(), "CUSTOMER");
    adminToken = jwt.generateToken(admin.getId(), "ADMIN");
    add(customer, "ORDER_PLACED", "order:1:ORDER_PLACED");
    add(customer, "PAYMENT_CONFIRMED", "order:1:PAYMENT_CONFIRMED");
    add(other, "ORDER_PLACED", "order:2:ORDER_PLACED");
    add(admin, "CMS_NEW_ORDER", "order:3:ORDER_PLACED");
  }

  @Test
  void customerCanReadOnlyOwnNotificationsAndMarkRead() throws Exception {
    Long id =
        notifications.findAll().stream()
            .filter(n -> n.getRecipient().getId().equals(customer.getId()))
            .findFirst()
            .orElseThrow()
            .getId();
    mvc.perform(get("/api/notifications").header("Authorization", "Bearer " + customerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2));
    mvc.perform(
            put("/api/notifications/" + id + "/read")
                .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
    mvc.perform(
            put("/api/notifications/" + id + "/read")
                .header("Authorization", "Bearer " + customerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.read").value(true));
    mvc.perform(
            get("/api/notifications/unread-count")
                .header("Authorization", "Bearer " + customerToken))
        .andExpect(status().isOk())
        .andExpect(content().string("1"));
    mvc.perform(
            put("/api/notifications/read-all").header("Authorization", "Bearer " + customerToken))
        .andExpect(status().isOk())
        .andExpect(content().string("1"));
    mvc.perform(
            get("/api/notifications")
                .param("read", "true")
                .header("Authorization", "Bearer " + customerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2));
    mvc.perform(
            get("/api/notifications")
                .param("size", "101")
                .header("Authorization", "Bearer " + customerToken))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
  }

  @Test
  void cmsNotificationRouteRequiresAdminAndFiltersCmsRows() throws Exception {
    mvc.perform(get("/api/cms/notifications").header("Authorization", "Bearer " + customerToken))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/cms/notifications").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(get("/api/notifications").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void orderEventPersistsCustomerAdminAndOutboxRowsOnlyOnCommit() {
    Long rolledBackId =
        transactions.execute(
            status -> {
              Order order = createOrder(customer);
              events.publishEvent(new OrderPlacedEvent(order.getId()));
              status.setRollbackOnly();
              return order.getId();
            });
    assertFalse(outbox.existsByEventKey("order:" + rolledBackId + ":ORDER_PLACED"));

    Long committedId =
        transactions.execute(
            status -> {
              Order order = createOrder(customer);
              events.publishEvent(new OrderPlacedEvent(order.getId()));
              return order.getId();
            });
    assertTrue(outbox.existsByEventKey("order:" + committedId + ":ORDER_PLACED"));
    assertTrue(
        notifications.existsByRecipientIdAndEventKey(
            customer.getId(), "order:" + committedId + ":ORDER_PLACED"));
    assertTrue(
        notifications.existsByRecipientIdAndEventKey(
            users.findByEmail("notify-admin@example.com").orElseThrow().getId(),
            "order:" + committedId + ":ORDER_PLACED"));
  }

  private User user(String email, Role role) {
    return users.save(User.builder().name(email).email(email).passwordHash("x").role(role).build());
  }

  private void add(User user, String type, String key) {
    Notification n = new Notification();
    n.setRecipient(user);
    n.setEventKey(key);
    n.setType(type);
    n.setTitle("title");
    n.setMessage("message");
    notifications.save(n);
  }

  private Order createOrder(User user) {
    return orders.saveAndFlush(
        Order.builder()
            .user(user)
            .status(OrderStatus.CONFIRMED)
            .paymentMethod(PaymentMethod.COD)
            .recipientName("Test")
            .phone("000")
            .addressLine("Test")
            .totalAmount(java.math.BigDecimal.TEN)
            .build());
  }
}
