package com.example.backend.service.payment;

import com.example.backend.entity.Order; // Order (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.PaymentMethod; // PaymentMethod (entity ánh xạ dữ liệu với bảng database).
import jakarta.servlet.http.HttpServletRequest; // thông tin request/response HTTP của Servlet (HttpServletRequest).
import org.springframework.stereotype.Component; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Component).

// COD không có gateway ngoài để chờ webhook - OrderService.checkout xác nhận (CONFIRMED) và trừ
// stock thật ngay lúc tạo Order cho COD, KHÔNG gọi initiate() ở nhánh đó (xem spec §6). Class này
// vẫn implement PaymentGateway đầy đủ để PaymentService.gatewayOf(COD) có gì đó trả về, và để test
// theo cùng interface với VnPayGateway/StripeGateway.
@Component
public class CodGateway implements PaymentGateway {

    @Override
    public PaymentMethod getMethod() {
        return PaymentMethod.COD;
    }

    @Override
    public PaymentInitResult initiate(Order order) {
        return PaymentInitResult.none();
    }

    @Override
    public PaymentWebhookResult parseWebhook(HttpServletRequest request, String rawBody) {
        throw new UnsupportedOperationException("COD has no webhook");
    }
}
