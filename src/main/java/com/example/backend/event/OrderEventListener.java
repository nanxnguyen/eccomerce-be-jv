package com.example.backend.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// Điểm nối cho consumer bất đồng bộ trong tương lai (email xác nhận, Kafka producer, v.v.) - xem
// spec §3 non-goals + §8. Hiện tại chỉ log; đổi THÂN của các method này (không đổi chỗ nào publish
// event trong OrderService) là đủ để chuyển sang xử lý thật sau này.
@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    @EventListener
    public void onOrderPlaced(OrderPlacedEvent event) {
        log.info("Order placed: orderId={}", event.orderId());
    }

    @EventListener
    public void onPaymentConfirmed(PaymentConfirmedEvent event) {
        log.info("Payment confirmed: orderId={}", event.orderId());
    }

    @EventListener
    public void onOrderCancelled(OrderCancelledEvent event) {
        log.info("Order cancelled: orderId={}, reason={}", event.orderId(), event.reason());
    }
}
