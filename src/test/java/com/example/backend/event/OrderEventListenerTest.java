package com.example.backend.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class OrderEventListenerTest {

    private final OrderEventListener listener = new OrderEventListener();

    @Test
    void handlesEachEventTypeWithoutThrowing() {
        assertThatCode(() -> listener.onOrderPlaced(new OrderPlacedEvent(1L))).doesNotThrowAnyException();
        assertThatCode(() -> listener.onPaymentConfirmed(new PaymentConfirmedEvent(1L))).doesNotThrowAnyException();
        assertThatCode(() -> listener.onOrderCancelled(new OrderCancelledEvent(1L, "EXPIRED"))).doesNotThrowAnyException();
    }
}
