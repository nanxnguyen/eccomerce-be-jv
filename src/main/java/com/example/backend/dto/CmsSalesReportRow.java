package com.example.backend.dto;

import com.example.backend.entity.OrderStatus; // OrderStatus (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.PaymentMethod; // PaymentMethod (entity ánh xạ dữ liệu với bảng database).
import java.math.BigDecimal; // tính tiền chính xác, tránh sai số số thực.
import java.time.LocalDate; // kiểu/thao tác thời gian chuẩn Java (LocalDate).

public record CmsSalesReportRow(
    LocalDate bucketStart, // Ngày bắt đầu của mốc báo cáo.
    PaymentMethod paymentMethod, // Phương thức thanh toán của nhóm đơn.
    OrderStatus orderStatus, // Trạng thái đơn trong nhóm.
    BigDecimal paidRevenue, // Tổng tiền của các đơn đã thanh toán.
    long paidOrderCount) {} // Số đơn thuộc nhóm này.
