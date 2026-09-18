package com.example.backend.service.payment;

import com.example.backend.entity.Order;
import com.example.backend.entity.OrderStatus;
import com.example.backend.entity.PaymentMethod;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class StripeGatewayTest {

    private final StripeGateway gateway = new StripeGateway("sk_test_dummy", "whsec_dummy");

    @Test
    void toStripeAmountSendsWholeVndValueNotTimes100() {
        // VND là zero-decimal currency trong Stripe - KHÔNG x100 như USD cents. Đây là quy ước
        // NGƯỢC với VNPay (luôn x100) - xem VnPayGateway.initiate().
        assertThat(StripeGateway.toStripeAmount(new BigDecimal("150000"))).isEqualTo(150000L);
    }

    @Test
    void toStripeAmountRoundsRealisticFractionalTotalsInsteadOfThrowing() {
        // Order.totalAmount là price.multiply(quantity) - luôn có .00-.99, KHÔNG phải số nguyên như
        // test ở trên. longValueExact() trên 1 BigDecimal có phần lẻ (39.98) ném ArithmeticException
        // nếu không setScale(0, HALF_UP) trước.
        assertThat(StripeGateway.toStripeAmount(new BigDecimal("39.98"))).isEqualTo(40L);
        assertThat(StripeGateway.toStripeAmount(new BigDecimal("39.49"))).isEqualTo(39L);
    }

    @Test
    void initiateCreatesPaymentIntentWithWholeVndAmountAndReturnsClientSecret() {
        Order order = Order.builder().id(7L).status(OrderStatus.PENDING_PAYMENT)
                .paymentMethod(PaymentMethod.STRIPE).totalAmount(new BigDecimal("150000")).build();

        PaymentIntent fakeIntent = mock(PaymentIntent.class);
        when(fakeIntent.getClientSecret()).thenReturn("pi_fake_secret");

        try (MockedStatic<PaymentIntent> stripeStatic = mockStatic(PaymentIntent.class)) {
            stripeStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenReturn(fakeIntent);

            PaymentInitResult result = gateway.initiate(order);

            assertThat(result.clientSecret()).isEqualTo("pi_fake_secret");
            assertThat(result.redirectUrl()).isNull();
        }
    }

    @Test
    void parseWebhookMapsSucceededEventToSuccessResult() throws SignatureVerificationException {
        Event event = mock(Event.class);
        PaymentIntent intent = mock(PaymentIntent.class);
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);

        when(event.getType()).thenReturn("payment_intent.succeeded");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of(intent));
        when(intent.getMetadata()).thenReturn(Map.of("orderId", "7"));
        when(intent.getId()).thenReturn("pi_123");

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString())).thenReturn(event);

            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Stripe-Signature", "t=1,v1=fake");

            PaymentWebhookResult result = gateway.parseWebhook(request, "{}");

            assertThat(result).isNotNull();
            assertThat(result.orderId()).isEqualTo(7L);
            assertThat(result.success()).isTrue();
            assertThat(result.gatewayTransactionRef()).isEqualTo("pi_123");
        }
    }

    @Test
    void parseWebhookReturnsNullOnInvalidSignature() throws SignatureVerificationException {
        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenThrow(mock(SignatureVerificationException.class));

            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Stripe-Signature", "t=1,v1=bad");

            assertThat(gateway.parseWebhook(request, "{}")).isNull();
        }
    }
}
