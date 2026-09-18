package com.example.backend.scheduler;

import com.example.backend.service.OrderService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OrderExpirySchedulerTest {

    @Test
    void delegatesToOrderServiceExpireOverdueOrders() {
        OrderService orderService = mock(OrderService.class);
        OrderExpiryScheduler scheduler = new OrderExpiryScheduler(orderService);

        scheduler.expirePendingOrders();

        verify(orderService).expireOverdueOrders();
    }
}
