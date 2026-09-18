package com.example.backend.service.payment;

import com.example.backend.entity.Order;
import com.example.backend.entity.OrderStatus;
import com.example.backend.entity.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class VnPayGatewayTest {

    private static final String HASH_SECRET = "TEST_HASH_SECRET_AT_LEAST_THIS_LONG";

    private final VnPayGateway gateway = new VnPayGateway(
            "TEST_MERCHANT", HASH_SECRET,
            "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html",
            "http://localhost:8080/api/payments/webhooks/vnpay");

    @Test
    void initiateBuildsSignedPayUrlWithAmountTimes100() {
        Order order = Order.builder().id(42L).status(OrderStatus.PENDING_PAYMENT)
                .paymentMethod(PaymentMethod.VNPAY).totalAmount(new BigDecimal("150000")).build();

        PaymentInitResult result = gateway.initiate(order);

        // VNPay nhân amount x100 theo quy ước riêng của nó: 150000 -> "15000000".
        assertThat(result.redirectUrl())
                .contains("vnp_TmnCode=TEST_MERCHANT")
                .contains("vnp_Amount=15000000")
                .contains("vnp_TxnRef=42")
                .contains("vnp_SecureHash=");
        assertThat(result.clientSecret()).isNull();
    }

    @Test
    void parseWebhookAcceptsValidSignatureAndRejectsTampering() {
        MockHttpServletRequest valid = new MockHttpServletRequest();
        valid.setParameter("vnp_TxnRef", "42");
        valid.setParameter("vnp_ResponseCode", "00");
        valid.setParameter("vnp_TransactionNo", "VNP123");
        valid.setParameter("vnp_Amount", "15000000");
        valid.setParameter("vnp_SecureHash", signParamsForTest(valid));

        PaymentWebhookResult result = gateway.parseWebhook(valid, null);
        assertThat(result).isNotNull();
        assertThat(result.orderId()).isEqualTo(42L);
        assertThat(result.success()).isTrue();
        assertThat(result.gatewayTransactionRef()).isEqualTo("VNP123");

        // Ký xong mới đổi vnp_Amount -> hash không còn khớp -> phải bị từ chối (trả null), không
        // được đọc nhầm order/số tiền theo params đã bị sửa.
        MockHttpServletRequest tampered = new MockHttpServletRequest();
        tampered.setParameter("vnp_TxnRef", "42");
        tampered.setParameter("vnp_ResponseCode", "00");
        tampered.setParameter("vnp_Amount", "99999999");
        tampered.setParameter("vnp_SecureHash", signParamsForTest(valid));

        assertThat(gateway.parseWebhook(tampered, null)).isNull();
    }

    // Ký lại bằng ĐÚNG helper mà VnPayGateway.parseWebhook() dùng (package-private) để tạo 1
    // request "hợp lệ" - test không mock hay tự viết lại thuật toán ký riêng.
    private String signParamsForTest(MockHttpServletRequest request) {
        Map<String, String> params = new TreeMap<>();
        for (String name : Collections.list(request.getParameterNames())) {
            params.put(name, request.getParameter(name));
        }
        return VnPayGateway.hmacSha512(HASH_SECRET, VnPayGateway.buildQuery(params));
    }
}
