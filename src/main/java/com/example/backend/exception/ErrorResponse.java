package com.example.backend.exception;

import java.time.Instant;

// Cấu trúc JSON thống nhất được trả về khi có lỗi.
// - timestamp: thời điểm lỗi xảy ra
// - status: mã HTTP (404, 409, 500, v.v.)
// - error: tên của trạng thái HTTP (e.g., "Not Found", "Internal Server Error")
// - message: mô tả chi tiết về lỗi
// - path: đường dẫn API bị lỗi
public record ErrorResponse(Instant timestamp, int status, String error, String message, String path) {}
