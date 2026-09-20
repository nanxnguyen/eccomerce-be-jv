package com.example.backend.event;

import org.slf4j.Logger; // API ghi log của ứng dụng (Logger).
import org.slf4j.LoggerFactory; // API ghi log của ứng dụng (LoggerFactory).
import org.springframework.context.event.EventListener; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (EventListener).
import org.springframework.stereotype.Component; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Component).

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
