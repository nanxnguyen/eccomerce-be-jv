package com.example.backend.service.payment;

import com.example.backend.entity.Order; // Order (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.PaymentMethod; // PaymentMethod (entity ánh xạ dữ liệu với bảng database).
import com.stripe.Stripe; // thư viện/kiểu Stripe được dùng trong file này.
import com.stripe.exception.SignatureVerificationException; // thư viện/kiểu SignatureVerificationException được dùng trong file này.
import com.stripe.exception.StripeException; // thư viện/kiểu StripeException được dùng trong file này.
import com.stripe.model.Event; // thư viện/kiểu Event được dùng trong file này.
import com.stripe.model.PaymentIntent; // thư viện/kiểu PaymentIntent được dùng trong file này.
import com.stripe.net.Webhook; // thư viện/kiểu Webhook được dùng trong file này.
import com.stripe.param.PaymentIntentCreateParams; // thư viện/kiểu PaymentIntentCreateParams được dùng trong file này.
import jakarta.servlet.http.HttpServletRequest; // thông tin request/response HTTP của Servlet (HttpServletRequest).
import org.springframework.beans.factory.annotation.Value; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Value).
import org.springframework.stereotype.Component; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Component).

import java.math.BigDecimal; // tính tiền chính xác, tránh sai số số thực.
import java.math.RoundingMode; // tiện ích toán học chuẩn Java (RoundingMode).

@Component
public class StripeGateway implements PaymentGateway {

    private final String webhookSecret;

    public StripeGateway(@Value("${stripe.secret-key}") String secretKey,
                          @Value("${stripe.webhook-secret}") String webhookSecret) {
        Stripe.apiKey = secretKey;
        this.webhookSecret = webhookSecret;
    }

    @Override
    public PaymentMethod getMethod() {
        return PaymentMethod.STRIPE;
    }

    @Override
    public PaymentInitResult initiate(Order order) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(toStripeAmount(order.getTotalAmount()))
                .setCurrency("vnd")
                .putMetadata("orderId", order.getId().toString())
                .build();
        try {
            PaymentIntent intent = PaymentIntent.create(params);
            return PaymentInitResult.clientSecret(intent.getClientSecret());
        } catch (StripeException e) {
            throw new PaymentGatewayException("Stripe PaymentIntent creation failed for order " + order.getId(), e);
        }
    }

    @Override
    public PaymentWebhookResult parseWebhook(HttpServletRequest request, String rawBody) {
        String signatureHeader = request.getHeader("Stripe-Signature");
        Event event;
        try {
            event = Webhook.constructEvent(rawBody, signatureHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            return null;
        }

        PaymentIntent intent = (PaymentIntent) event.getDataObjectDeserializer().getObject().orElseThrow();
        Long orderId = Long.parseLong(intent.getMetadata().get("orderId"));
        boolean success = "payment_intent.succeeded".equals(event.getType());
        return new PaymentWebhookResult(orderId, success, intent.getId());
    }

    // VND là zero-decimal currency trong Stripe: amount gửi lên API là số VND NGUYÊN, KHÔNG x100
    // như USD cents. Xem Global Constraints + spec §4 Money handling - nhầm chỗ này khiến MỌI giao
    // dịch Stripe bị tính sai gấp 100 lần (150.000đ bị gửi thành 15.000.000đ).
    // setScale(0, HALF_UP) TRƯỚC longValueExact(): Order.totalAmount luôn có 2 chữ số lẻ
    // (price.multiply(quantity), ví dụ 39.98) - gọi longValueExact() thẳng trên số có phần lẻ ném
    // ArithmeticException ngay tại checkout. VND không có đơn vị nhỏ hơn 1 đồng nên làm tròn về số
    // nguyên là đúng, không mất giá trị đáng kể.
    static long toStripeAmount(BigDecimal totalAmount) {
        return totalAmount.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
