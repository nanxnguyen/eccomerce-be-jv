package com.example.backend.service;

import com.example.backend.dto.AuthResponse; // AuthResponse (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.LoginRequest; // LoginRequest (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.RegisterRequest; // RegisterRequest (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.UserResponse; // UserResponse (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.entity.RefreshToken; // RefreshToken (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.Role; // Role (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.User; // User (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.exception.DuplicateResourceException; // DuplicateResourceException (loại lỗi nghiệp vụ hoặc dữ liệu).
import com.example.backend.repository.RefreshTokenRepository; // RefreshTokenRepository (repository truy vấn/lưu dữ liệu qua JPA).
import com.example.backend.repository.UserRepository; // UserRepository (repository truy vấn/lưu dữ liệu qua JPA).
import com.example.backend.security.JwtService; // JwtService (thành phần xác thực/phân quyền).
import org.springframework.beans.factory.annotation.Value; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Value).
import org.springframework.security.authentication.BadCredentialsException; // thành phần Spring Security cho xác thực/phân quyền (BadCredentialsException).
import org.springframework.security.crypto.password.PasswordEncoder; // thành phần Spring Security cho xác thực/phân quyền (PasswordEncoder).
import org.springframework.stereotype.Service; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Service).
import org.springframework.transaction.annotation.Transactional; // quản lý transaction database (Transactional).

import java.security.MessageDigest; // thư viện/kiểu MessageDigest được dùng trong file này.
import java.security.NoSuchAlgorithmException; // thư viện/kiểu NoSuchAlgorithmException được dùng trong file này.
import java.security.SecureRandom; // thư viện/kiểu SecureRandom được dùng trong file này.
import java.nio.charset.StandardCharsets; // thư viện/kiểu StandardCharsets được dùng trong file này.
import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).
import java.util.Base64; // mã hóa/giải mã Base64.
import java.util.HexFormat; // tiện ích collection chuẩn Java (HexFormat).
import java.util.UUID; // tạo định danh ngẫu nhiên.

@Service
public class AuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final long accessExpirationMs;
    private final long refreshExpirationMs;

    public AuthService(UserRepository userRepository,
                        PasswordEncoder passwordEncoder,
                        JwtService jwtService,
                        RefreshTokenRepository refreshTokenRepository,
                        @Value("${jwt.expiration-ms}") long accessExpirationMs,
                        @Value("${jwt.refresh-expiration-ms}") long refreshExpirationMs) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenRepository = refreshTokenRepository;
        this.accessExpirationMs = accessExpirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    public AuthResponse register(RegisterRequest request, String deviceId, String ip, String userAgent) {
        // Kiểm tra trùng email TRƯỚC khi hash mật khẩu/lưu DB - tránh tốn công hash (BCrypt cố tình
        // chậm để chống brute-force) cho một request chắc chắn sẽ bị từ chối.
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email already registered: " + request.email());
        }

        // Role luôn là CUSTOMER, không đọc role từ request: đây là API đăng ký công khai (ai cũng
        // gọi được), nếu cho phép client tự chọn role thì ai cũng có thể tự phong mình làm ADMIN.
        // Muốn có tài khoản ADMIN thì phải tạo bằng cách khác (seed dữ liệu, hoặc endpoint riêng
        // chỉ ADMIN hiện có mới gọi được).
        User user = User.builder()
                .name(request.name())
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .phone(request.phone())
                .role(Role.CUSTOMER)
                .build();

        userRepository.save(user);

        return issueNewSession(user, deviceId, ip, userAgent);
    }

    public AuthResponse login(LoginRequest request, String deviceId, String ip, String userAgent) {
        // Cố tình dùng CÙNG MỘT thông báo lỗi cho cả 2 trường hợp "không tìm thấy email" và
        // "sai mật khẩu". Nếu thông báo khác nhau, kẻ tấn công có thể dò ra được email nào đã
        // tồn tại trong hệ thống (user enumeration attack) chỉ bằng cách thử đăng nhập.
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        // So khớp mật khẩu bằng passwordEncoder.matches(), KHÔNG bao giờ so sánh chuỗi (==, .equals())
        // vì passwordHash trong DB là chuỗi đã hash (BCrypt), không phải mật khẩu gốc - so sánh
        // chuỗi trực tiếp sẽ luôn sai. matches() tự hash lại mật khẩu vừa nhập bằng cùng salt rồi so sánh.
        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        return issueNewSession(user, deviceId, ip, userAgent);
    }

    // Rotate: mỗi lần gọi /refresh phát 1 access token mới + 1 refresh token mới, và revoke
    // NGAY refresh token cũ - xem docs/architecture-roadmap.md §4.3.
    // noRollbackFor BẮT BUỘC: nhánh reuse-detection bên dưới revoke cả session RỒI throw
    // BadCredentialsException để trả 401 - mặc định Spring rollback mọi RuntimeException, sẽ xoá
    // sạch chính cái revoke vừa làm, coi như không hề revoke gì (session vẫn dùng được, mất tác dụng).
    @Transactional(noRollbackFor = BadCredentialsException.class)
    public AuthResponse refresh(String rawRefreshToken, String deviceId, String ip, String userAgent) {
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash(rawRefreshToken))
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));

        if (stored.getExpiresAt().isBefore(Instant.now())) {
            throw new BadCredentialsException("Refresh token expired");
        }

        // UPDATE có điều kiện (revoked_at IS NULL) thay vì đọc rồi ghi: nếu token này đã bị rotate
        // trước đó (client cũ gửi lại - dấu hiệu bị đánh cắp) hoặc một request /refresh khác đang
        // chạy đồng thời trên CÙNG token vừa thắng, revoked = 0. Cả 2 trường hợp đều nguy hiểm như
        // nhau: coi là token bị lộ, thu hồi TOÀN BỘ session (mọi token kế thừa nhau qua rotation).
        int revoked = refreshTokenRepository.revokeIfActive(stored.getId(), Instant.now());
        if (revoked == 0) {
            refreshTokenRepository.revokeSession(stored.getSessionId(), Instant.now());
            throw new BadCredentialsException("Refresh token reuse detected");
        }

        return issueSession(stored.getUser(), stored.getSessionId(), deviceId, ip, userAgent);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenRepository.findByTokenHash(hash(rawRefreshToken))
                .ifPresent(t -> refreshTokenRepository.revokeSession(t.getSessionId(), Instant.now()));
    }

    @Transactional
    public void logoutAll(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Invalid user"));
        refreshTokenRepository.revokeAllForUser(user.getId(), Instant.now());
    }

    // Gọi định kỳ từ RefreshTokenCleanupScheduler - xoá row đã hết hạn (dùng được hay không cũng
    // không còn quan trọng, findByTokenHash trong refresh() đã tự chặn bằng expiresAt check).
    @Transactional
    public void cleanupExpiredRefreshTokens() {
        refreshTokenRepository.deleteExpired(Instant.now());
    }

    private AuthResponse issueNewSession(User user, String deviceId, String ip, String userAgent) {
        return issueSession(user, UUID.randomUUID().toString(), deviceId, ip, userAgent);
    }

    private AuthResponse issueSession(User user, String sessionId, String deviceId, String ip, String userAgent) {
        String accessToken = jwtService.generateToken(user.getId(), user.getRole().name());

        String rawRefreshToken = randomToken();
        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(hash(rawRefreshToken))
                .sessionId(sessionId)
                .deviceId(deviceId)
                .ipAddress(ip)
                .userAgent(userAgent)
                .expiresAt(Instant.now().plusMillis(refreshExpirationMs))
                .build();
        refreshTokenRepository.save(refreshToken);

        return new AuthResponse(
                accessToken,
                rawRefreshToken,
                "Bearer",
                accessExpirationMs / 1000,
                refreshExpirationMs / 1000,
                UserResponse.from(user)
        );
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // Hash bằng SHA-256 (KHÔNG dùng BCrypt): tra cứu refresh token là findByTokenHash (so khớp
    // chính xác), cần digest tất định. BCrypt sinh salt ngẫu nhiên mỗi lần hash - cùng input ra
    // 2 chuỗi khác nhau, không thể dùng làm khóa tra cứu (sẽ phải quét toàn bộ bảng để so từng dòng).
    private static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
