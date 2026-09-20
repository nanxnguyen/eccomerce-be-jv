package com.example.backend.service;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.event.OrderPlacedEvent;
import org.junit.jupiter.api.Test;

class NotificationServiceTest {
  @Test
  void orderEventCarriesStableOrderIdentity() {
    OrderPlacedEvent event = new OrderPlacedEvent(42L);
    assertEquals(42L, event.orderId());
  }
}
