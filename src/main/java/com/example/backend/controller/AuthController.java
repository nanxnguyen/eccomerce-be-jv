package com.example.backend.controller;

import com.example.backend.dto.AuthResponse;
import com.example.backend.dto.LoginRequest;
import com.example.backend.dto.RefreshTokenRequest;
import com.example.backend.dto.RegisterRequest;
import com.example.backend.security.LoginRateLimiter;
import com.example.backend.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final LoginRateLimiter loginRateLimiter;

    public AuthController(AuthService authService, LoginRateLimiter loginRateLimiter) {
        this.authService = authService;
        this.loginRateLimiter = loginRateLimiter;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        AuthResponse response = authService.register(request, deviceId(httpRequest), httpRequest.getRemoteAddr(), userAgent(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // checkAllowed() chạy TRƯỚC authService.login() (trước cả findByEmail) - vượt ngưỡng thì từ
    // chối thẳng, không tốn công tra DB/BCrypt cho request chắc chắn bị chặn.
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        loginRateLimiter.checkAllowed(ip);
        try {
            AuthResponse response = authService.login(request, deviceId(httpRequest), ip, userAgent(httpRequest));
            loginRateLimiter.recordSuccess(ip);
            return ResponseEntity.ok(response);
        } catch (BadCredentialsException ex) {
            loginRateLimiter.recordFailure(ip);
            throw ex;
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request, HttpServletRequest httpRequest) {
        AuthResponse response = authService.refresh(request.refreshToken(), deviceId(httpRequest), httpRequest.getRemoteAddr(), userAgent(httpRequest));
        return ResponseEntity.ok(response);
    }

    // Xác định phiên bằng refresh token trong body, không cần access token - access token vẫn
    // hợp lệ tới khi hết hạn (tối đa 15 phút) vì đây là JWT stateless, không tra cứu blacklist mỗi
    // request (đó là việc của Redis auth:blacklist:{jti}, Phase 2).
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    // Khác /logout: cần biết "user hiện tại" nên bắt buộc access token hợp lệ (xem SecurityConfig -
    // /api/auth/logout-all KHÔNG nằm trong danh sách permitAll).
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal UserDetails userDetails) {
        authService.logoutAll(userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    private static String deviceId(HttpServletRequest request) {
        return request.getHeader("X-Device-Id");
    }

    private static String userAgent(HttpServletRequest request) {
        return request.getHeader("User-Agent");
    }
}
