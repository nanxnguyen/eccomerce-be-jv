package com.example.backend.service;

import com.example.backend.entity.Order; // Order (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.PaymentMethod; // PaymentMethod (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.service.payment.PaymentGateway; // PaymentGateway (payment).
import com.example.backend.service.payment.PaymentInitResult; // PaymentInitResult (payment).
import com.example.backend.service.payment.PaymentWebhookResult; // PaymentWebhookResult (payment).
import jakarta.servlet.http.HttpServletRequest; // thông tin request/response HTTP của Servlet (HttpServletRequest).
import org.springframework.stereotype.Service; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Service).

import java.util.List; // danh sách phần tử cùng kiểu.
import java.util.Map; // bản đồ khóa–giá trị.
import java.util.function.Function; // tiện ích collection chuẩn Java (Function).
import java.util.stream.Collectors; // tiện ích collection chuẩn Java (Collectors).

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
        // Chọn adapter theo paymentMethod; gateway thực hiện bước ngoài hệ thống (tạo link/session).
        return gatewayOf(order.getPaymentMethod()).initiate(order);
    }

    public PaymentWebhookResult parseWebhook(PaymentMethod method, HttpServletRequest request, String rawBody) {
        return gatewayOf(method).parseWebhook(request, rawBody);
    }

    private PaymentGateway gatewayOf(PaymentMethod method) {
        // Các gateway đã được gom vào Map theo PaymentMethod lúc Spring khởi tạo service.
        PaymentGateway gateway = gateways.get(method);
        if (gateway == null) {
            throw new IllegalStateException("No PaymentGateway registered for " + method);
        }
        return gateway;
    }
}
