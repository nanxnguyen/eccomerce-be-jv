package com.example.backend.service;

import com.example.backend.entity.Order;
import com.example.backend.entity.PaymentMethod;
import com.example.backend.service.payment.PaymentGateway;
import com.example.backend.service.payment.PaymentInitResult;
import com.example.backend.service.payment.PaymentWebhookResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PaymentService {

    private final Map<PaymentMethod, PaymentGateway> gateways;

    // Spring tự inject MỌI @Component implement PaymentGateway (CodGateway, VnPayGateway,
    // StripeGateway) vào đây dưới dạng List - thêm 1 gateway mới trong tương lai chỉ cần thêm class
    // implement interface này, KHÔNG cần sửa PaymentService.
    public PaymentService(List<PaymentGateway> gatewayList) {
        this.gateways = gatewayList.stream().collect(Collectors.toMap(PaymentGateway::getMethod, Function.identity()));
    }

    public PaymentInitResult initiate(Order order) {
        return gatewayOf(order.getPaymentMethod()).initiate(order);
    }

    public PaymentWebhookResult parseWebhook(PaymentMethod method, HttpServletRequest request, String rawBody) {
        return gatewayOf(method).parseWebhook(request, rawBody);
    }

    private PaymentGateway gatewayOf(PaymentMethod method) {
        PaymentGateway gateway = gateways.get(method);
        if (gateway == null) {
            throw new IllegalStateException("No PaymentGateway registered for " + method);
        }
        return gateway;
    }
}
