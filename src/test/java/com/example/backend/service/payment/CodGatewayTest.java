package com.example.backend.service.payment;

import com.example.backend.entity.Order;
import com.example.backend.entity.OrderStatus;
import com.example.backend.entity.PaymentMethod;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodGatewayTest {

    private final CodGateway gateway = new CodGateway();

    @Test
    void identifiesAsCodAndReturnsEmptyInitResult() {
        Order order = Order.builder().status(OrderStatus.CONFIRMED).paymentMethod(PaymentMethod.COD).build();

        assertThat(gateway.getMethod()).isEqualTo(PaymentMethod.COD);
        PaymentInitResult result = gateway.initiate(order);
        assertThat(result.redirectUrl()).isNull();
        assertThat(result.clientSecret()).isNull();
    }

    @Test
    void hasNoWebhook() {
        assertThatThrownBy(() -> gateway.parseWebhook(null, null))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
