package com.example.backend.controller;

import com.example.backend.dto.AuthResponse;
import com.example.backend.dto.LoginRequest;
import com.example.backend.dto.RegisterRequest;
import com.example.backend.security.LoginRateLimiter;
import com.example.backend.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
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
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    // checkAllowed() chạy TRƯỚC authService.login() (trước cả findByEmail) - vượt ngưỡng thì từ
    // chối thẳng, không tốn công tra DB/BCrypt cho request chắc chắn bị chặn.
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        loginRateLimiter.checkAllowed(ip);
        try {
            AuthResponse response = authService.login(request);
            loginRateLimiter.recordSuccess(ip);
            return ResponseEntity.ok(response);
        } catch (BadCredentialsException ex) {
            loginRateLimiter.recordFailure(ip);
            throw ex;
        }
    }
}
