package com.example.backend.exception;

import jakarta.servlet.http.HttpServletRequest; // thông tin request/response HTTP của Servlet (HttpServletRequest).
import org.springframework.dao.DataIntegrityViolationException; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (DataIntegrityViolationException).
import org.springframework.http.HttpStatus; // kiểu HTTP như status, header hoặc response body (HttpStatus).
import org.springframework.http.ResponseEntity; // kiểu HTTP như status, header hoặc response body (ResponseEntity).
import org.springframework.http.converter.HttpMessageNotReadableException; // kiểu HTTP như status, header hoặc response body (HttpMessageNotReadableException).
import org.springframework.security.access.AccessDeniedException; // thành phần Spring Security cho xác thực/phân quyền (AccessDeniedException).
import org.springframework.security.authentication.BadCredentialsException; // thành phần Spring Security cho xác thực/phân quyền (BadCredentialsException).
import org.springframework.web.bind.MethodArgumentNotValidException; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (MethodArgumentNotValidException).
import org.springframework.web.bind.annotation.ExceptionHandler; // annotation Spring MVC để khai báo route/đọc request (ExceptionHandler).
import org.springframework.web.bind.annotation.RestControllerAdvice; // annotation Spring MVC để khai báo route/đọc request (RestControllerAdvice).
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (MethodArgumentTypeMismatchException).
import org.springframework.web.servlet.resource.NoResourceFoundException; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (NoResourceFoundException).
import org.slf4j.Logger; // API ghi log của ứng dụng (Logger).
import org.slf4j.LoggerFactory; // API ghi log của ứng dụng (LoggerFactory).

import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).
import java.util.stream.Collectors; // tiện ích collection chuẩn Java (Collectors).

// @RestControllerAdvice cho phép này xử lý ngoại lệ toàn cầu từ tất cả các Controller.
// Spring sẽ tự động bắt các ngoại lệ được ném từ request handler và gửi đến phương thức
// @ExceptionHandler phù hợp. Mỗi ngoại lệ loại khác nhau → một phương thức riêng + mã HTTP riêng.
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 404 Not Found: Tài nguyên không tìm thấy
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(InvalidRequestException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    // 409 Conflict: Tài nguyên trùng lặp (ví dụ: slug đã tồn tại)
    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateResourceException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    // 409 Conflict: không đủ hàng lúc checkout.
    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientStock(InsufficientStockException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    // 409 Conflict: thao tác Order không hợp lệ với status hiện tại.
    @ExceptionHandler(InvalidOrderStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidOrderState(InvalidOrderStateException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    // 401 Unauthorized: Xác thực thất bại (sai mật khẩu)
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), request);
    }

    // 403 Forbidden: Người dùng không có quyền truy cập
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "Access denied", request);
    }

    // 400 Bad Request: Dữ liệu đầu vào không hợp lệ (ví dụ: email bắt buộc, tên độ dài không hợp lệ)
    // Gom các lỗi trường lại với dấu phẩy để gửi toàn bộ danh sách lỗi
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    // 400 Bad Request: Body JSON gửi lên không đọc/parse được (ví dụ: JSON sai cú pháp, thiếu
    // dấu ngoặc). Đây là lỗi phía client, không phải lỗi server -> không nên rơi vào 500.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Malformed JSON request body", request);
    }

    // 400 Bad Request: Path variable/param không convert được sang kiểu Spring yêu cầu
    // (ví dụ: {id} khai báo Long nhưng client truyền "abc"). Cũng là lỗi input của client.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String requiredType = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "khác";
        String message = "Parameter '" + ex.getName() + "' should be of type " + requiredType;
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    // 409 Conflict: Ràng buộc dữ liệu ở tầng DB bị vi phạm (unique key, FK, v.v.) mà không bị
    // chặn trước ở tầng service/application (ví dụ: race condition giữa 2 request đồng thời
    // cùng tạo 1 slug -> cả 2 cùng qua được check existsBySlug() rồi 1 trong 2 mới bị DB chặn).
    // Vẫn nên trả 409 (client có thể sửa và thử lại) thay vì 500.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "Data integrity violation", request);
    }

    // 404 Not Found: Spring tự ném exception này khi không tìm thấy handler/route nào khớp với
    // URL (route hoàn toàn không tồn tại) - khác với ResourceNotFoundException (route tồn tại
    // nhưng ID/slug cụ thể không có trong DB).
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "Resource not found", request);
    }

    // 429 Too Many Requests: quá số lần login sai cho phép trong khoảng thời gian - xem LoginRateLimiter.
    @ExceptionHandler(TooManyAttemptsException.class)
    public ResponseEntity<ErrorResponse> handleTooManyAttempts(TooManyAttemptsException ex, HttpServletRequest request) {
        return build(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), request);
    }

    // 500 Internal Server Error: Lỗi bất ngờ (fallback cho tất cả ngoại lệ khác). Log đầy đủ
    // stack trace ở đây - trước đây không log gì, mọi lỗi 500 thật ở prod biến mất không dấu vết,
    // không có cách nào biết đã xảy ra chuyện gì để debug.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error handling {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error occurred", request);
    }

    // build() là phương thức trợ giúp để tránh lặp lại mã. Thay vì mỗi handler viết lại
    // cấu trúc ErrorResponse, tất cả đều gọi build() với trạng thái HTTP và thông báo khác nhau.
    // Nếu muốn thay đổi format JSON, chỉ cần sửa ở đây.
    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(), message, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
