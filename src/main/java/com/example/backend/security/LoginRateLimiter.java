package com.example.backend.security;

// Chặn brute-force login theo IP: quá auth.login.max-attempts lần sai trong auth.login.window-minutes
// phút thì từ chối, không gọi tới AuthService.login() nữa (tránh tốn công BCrypt.matches() vô ích).
// Key theo IP (KHÔNG theo email): key theo email cho phép 1 kẻ tấn công tự khoá tài khoản người khác
// chỉ bằng cách gửi vài request sai mật khẩu (targeted lockout DoS) - AuthController.checkAllowed()
// gọi TRƯỚC AuthService.findByEmail() nên không mở lại lỗ user-enumeration mà AuthService cố tình che.
//
// 2 implementation: RedisLoginRateLimiter (mặc định, dùng thật - đếm nhất quán across nhiều instance)
// và InMemoryLoginRateLimiter (chỉ bật trong test, xem application.properties test - test không cần
// phụ thuộc Redis thật để chạy, giống cách test dùng H2 thay vì Postgres thật).
public interface LoginRateLimiter {

    void checkAllowed(String ip);

    void recordFailure(String ip);

    void recordSuccess(String ip);
}
