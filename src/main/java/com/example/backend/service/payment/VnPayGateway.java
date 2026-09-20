package com.example.backend.service.payment;

import com.example.backend.entity.Order; // Order (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.PaymentMethod; // PaymentMethod (entity ánh xạ dữ liệu với bảng database).
import jakarta.servlet.http.HttpServletRequest; // thông tin request/response HTTP của Servlet (HttpServletRequest).
import org.springframework.beans.factory.annotation.Value; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Value).
import org.springframework.stereotype.Component; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Component).

import javax.crypto.Mac; // thư viện/kiểu Mac được dùng trong file này.
import javax.crypto.spec.SecretKeySpec; // thư viện/kiểu SecretKeySpec được dùng trong file này.
import java.math.BigDecimal; // tính tiền chính xác, tránh sai số số thực.
import java.net.URLEncoder; // thư viện/kiểu URLEncoder được dùng trong file này.
import java.nio.charset.StandardCharsets; // thư viện/kiểu StandardCharsets được dùng trong file này.
import java.security.MessageDigest; // thư viện/kiểu MessageDigest được dùng trong file này.
import java.time.ZonedDateTime; // kiểu/thao tác thời gian chuẩn Java (ZonedDateTime).
import java.time.format.DateTimeFormatter; // kiểu/thao tác thời gian chuẩn Java (DateTimeFormatter).
import java.util.Enumeration; // tiện ích collection chuẩn Java (Enumeration).
import java.util.Map; // bản đồ khóa–giá trị.
import java.util.TreeMap; // tiện ích collection chuẩn Java (TreeMap).

// VNPay không có SDK Java chính thức - build query string + ký HMAC-SHA512 tay theo tài liệu của
// VNPay (https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html).
@Component
public class VnPayGateway implements PaymentGateway {

    private static final DateTimeFormatter CREATE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final String merchantCode;
    private final String hashSecret;
    private final String payUrl;
    private final String returnUrl;

    public VnPayGateway(@Value("${vnpay.merchant-code}") String merchantCode,
                         @Value("${vnpay.hash-secret}") String hashSecret,
                         @Value("${vnpay.pay-url}") String payUrl,
                         @Value("${vnpay.return-url}") String returnUrl) {
        this.merchantCode = merchantCode;
        this.hashSecret = hashSecret;
        this.payUrl = payUrl;
        this.returnUrl = returnUrl;
    }

    @Override
    public PaymentMethod getMethod() {
        return PaymentMethod.VNPAY;
    }

    @Override
    public PaymentInitResult initiate(Order order) {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", merchantCode);
        // VNPay LUÔN nhân amount x100 - quy ước nội bộ riêng của VNPay, KHÔNG liên quan tới quy ước
        // zero-decimal-currency của Stripe cho VND (xem StripeGateway.toStripeAmount, Task 12).
        // Đừng lấy nhầm 1 trong 2 chỗ nhân/không nhân 100 này.
        params.put("vnp_Amount", order.getTotalAmount().multiply(BigDecimal.valueOf(100)).toBigInteger().toString());
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_TxnRef", order.getId().toString());
        params.put("vnp_OrderInfo", "Thanh toan don hang " + order.getId());
        params.put("vnp_OrderType", "other");
        params.put("vnp_Locale", "vn");
        params.put("vnp_ReturnUrl", returnUrl);
        params.put("vnp_IpAddr", "127.0.0.1");
        params.put("vnp_CreateDate", ZonedDateTime.now().format(CREATE_DATE_FORMAT));

        String query = buildQuery(params);
        String secureHash = hmacSha512(hashSecret, query);
        return PaymentInitResult.redirect(payUrl + "?" + query + "&vnp_SecureHash=" + secureHash);
    }

    @Override
    public PaymentWebhookResult parseWebhook(HttpServletRequest request, String rawBody) {
        Map<String, String> params = new TreeMap<>();
        Enumeration<String> names = request.getParameterNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (!"vnp_SecureHash".equals(name) && !"vnp_SecureHashType".equals(name)) {
                params.put(name, request.getParameter(name));
            }
        }

        String receivedHash = request.getParameter("vnp_SecureHash");
        String expectedHash = hmacSha512(hashSecret, buildQuery(params));
        // MessageDigest.isEqual: so sánh constant-time, tránh timing attack dò ký tự đúng của hash
        // (khác String.equals() thường, dừng sớm ngay ký tự sai đầu tiên).
        if (receivedHash == null || !MessageDigest.isEqual(
                receivedHash.getBytes(StandardCharsets.UTF_8), expectedHash.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }

        Long orderId = Long.parseLong(params.get("vnp_TxnRef"));
        boolean success = "00".equals(params.get("vnp_ResponseCode"));
        return new PaymentWebhookResult(orderId, success, params.get("vnp_TransactionNo"));
    }

    // Package-private (không private): VnPayGatewayTest cần build 1 request "hợp lệ" bằng ĐÚNG
    // thuật toán này, không lặp lại code ký ở 2 chỗ.
    static String buildQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    static String hmacSha512(String key, String data) {
        try {
            Mac hmac512 = Mac.getInstance("HmacSHA512");
            hmac512.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] result = hmac512.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : result) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot compute VNPay secure hash", e);
        }
    }
}
