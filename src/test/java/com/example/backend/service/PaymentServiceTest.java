package com.example.backend.service;

import com.example.backend.entity.Order;
import com.example.backend.entity.PaymentMethod;
import com.example.backend.service.payment.PaymentGateway;
import com.example.backend.service.payment.PaymentInitResult;
import com.example.backend.service.payment.PaymentWebhookResult;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    @Test
    void dispatchesInitiateToTheGatewayMatchingPaymentMethod() {
        PaymentGateway cod = fakeGateway(PaymentMethod.COD);
        PaymentGateway stripe = fakeGateway(PaymentMethod.STRIPE);
        Order order = Order.builder().paymentMethod(PaymentMethod.STRIPE).build();
        PaymentInitResult expected = PaymentInitResult.clientSecret("secret");
        when(stripe.initiate(order)).thenReturn(expected);

        PaymentService service = new PaymentService(List.of(cod, stripe));

        assertThat(service.initiate(order)).isEqualTo(expected);
    }

    @Test
    void dispatchesWebhookToTheRequestedGateway() {
        PaymentGateway vnpay = fakeGateway(PaymentMethod.VNPAY);
        HttpServletRequest request = mock(HttpServletRequest.class);
        PaymentWebhookResult expected = new PaymentWebhookResult(1L, true, "ref");
        when(vnpay.parseWebhook(request, "body")).thenReturn(expected);

        PaymentService service = new PaymentService(List.of(vnpay));

        assertThat(service.parseWebhook(PaymentMethod.VNPAY, request, "body")).isEqualTo(expected);
    }

    @Test
    void throwsWhenNoGatewayRegisteredForMethod() {
        PaymentService service = new PaymentService(List.of());

        assertThatThrownBy(() -> service.initiate(Order.builder().paymentMethod(PaymentMethod.COD).build()))
                .isInstanceOf(IllegalStateException.class);
    }

    private PaymentGateway fakeGateway(PaymentMethod method) {
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.getMethod()).thenReturn(method);
        return gateway;
    }
}
