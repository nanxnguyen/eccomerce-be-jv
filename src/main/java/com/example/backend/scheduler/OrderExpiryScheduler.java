package com.example.backend.scheduler;

import com.example.backend.service.OrderService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// @EnableScheduling đã bật ở BackendApplication. fixedRate=60_000: quét mỗi 60s - đủ nhanh so với
// order.payment-expiry-minutes=15, không cần chính xác tới giây.
@Component
public class OrderExpiryScheduler {

    private final OrderService orderService;

    public OrderExpiryScheduler(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(fixedRate = 60_000)
    public void expirePendingOrders() {
        orderService.expireOverdueOrders();
    }
}
