package com.example.backend.config;

import com.example.backend.security.JwtAuthFilter;
import com.example.backend.logging.HttpExchangeLoggingFilter;
import com.example.backend.security.KeycloakJwtAuthenticationConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Cấu hình trung tâm cho Spring Security: quy tắc phân quyền theo endpoint, cách xác thực,
 * và các filter tham gia vào "chuỗi lọc" (filter chain) xử lý mỗi HTTP request.
 *
 * - @EnableWebSecurity: bật cấu hình Web Security tùy biến (thay vì cấu hình mặc định của Boot).
 * - @EnableMethodSecurity: cho phép dùng @PreAuthorize/@PostAuthorize trên method của controller/service
 *   (chưa dùng ở task này, nhưng các task sau cần quyền ADMIN/CUSTOMER trên từng method sẽ cần nó).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    // Comma-separated, đọc từ app.cors.allowed-origins (application.properties/application-prod.properties).
    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Value("${app.keycloak.enabled:false}")
    private boolean keycloakEnabled;

    // BCrypt: thuật toán hash mật khẩu một chiều (không thể giải mã ngược), có "salt" ngẫu nhiên
    // tự động nên cùng 1 mật khẩu hash 2 lần ra 2 chuỗi khác nhau -> chống rainbow-table attack.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public FilterRegistrationBean<HttpExchangeLoggingFilter> httpExchangeLoggingFilterRegistration(
            HttpExchangeLoggingFilter filter) {
        FilterRegistrationBean<HttpExchangeLoggingFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    // Client xác thực bằng header Authorization: Bearer <token>, không dùng cookie -> không có gì
    // để "credential" hoá, allowCredentials=false. Origin lấy từ property thay vì hardcode để prod
    // whitelist đúng domain FE thật, dev whitelist localhost.
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter,
                                                   HttpExchangeLoggingFilter httpExchangeLoggingFilter,
                                                   KeycloakJwtAuthenticationConverter keycloakConverter,
                                                   ObjectProvider<BearerTokenResolver> bearerTokenResolver) throws Exception {
        http
                // CSRF (Cross-Site Request Forgery) là rủi ro của app dùng session cookie: browser tự
                // động gửi kèm cookie trong mọi request, kể cả request giả mạo từ site khác, nên cần
                // token CSRF để phân biệt. API này KHÔNG dùng session/cookie để xác thực (client tự
                // gắn JWT vào header Authorization), nên không có rủi ro CSRF kiểu đó -> tắt an toàn.
                .csrf(csrf -> csrf.disable())
                // Bật CORS bằng bean corsConfigurationSource() ở trên. Thiếu dòng này thì bean tồn tại
                // vô nghĩa - Spring Security vẫn chặn request cross-origin ở tầng filter chain, và
                // preflight OPTIONS sẽ dính rule "anyRequest().authenticated()" bên dưới -> 401 sai chỗ.
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // STATELESS: server không tạo/lưu HttpSession cho request nào cả. Mỗi request phải tự
                // mang đủ thông tin xác thực (JWT) của chính nó - đúng bản chất REST API không trạng thái,
                // và tránh việc phải đồng bộ session giữa nhiều instance server khi scale ngang.
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Đăng ký/đăng nhập phải public - chưa có token thì sao gọi API cần xác thực được.
                        // /v3/api-docs/** và /swagger-ui/** cũng phải public vì đây là tài liệu API và
                        // giao diện Swagger UI - nếu bắt xác thực thì gặp "con gà quả trứng": muốn xem
                        // tài liệu để biết cách lấy token nhưng lại cần token để xem tài liệu. Ở prod,
                        // springdoc.api-docs.enabled=false/springdoc.swagger-ui.enabled=false (application-prod.properties)
                        // tắt hẳn 2 endpoint này nên rule permitAll ở đây không còn lộ gì.
                        // /api/auth/logout-all KHÔNG nằm trong danh sách permitAll - nó cần biết "user
                        // hiện tại" nên bắt buộc access token hợp lệ, rơi vào rule anyRequest().authenticated().
                        .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout",
                                "/api/payments/webhooks/**", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        // Health check cho load balancer/k8s probe - không cần (và không nên) xác thực.
                        .requestMatchers("/actuator/health/**").permitAll()
                        // Prometheus scrape endpoint is served on the private management port.
                        .requestMatchers("/actuator/prometheus").permitAll()
                        // Khách vãng lai (chưa đăng nhập) vẫn xem được danh mục/sản phẩm (GET),
                        // nhưng sửa/xóa (POST/PUT/DELETE) thì rơi vào rule "anyRequest().authenticated()" bên dưới.
                        .requestMatchers(HttpMethod.GET, "/api/categories/**", "/api/products/**").permitAll()
                        .anyRequest().authenticated()
                )
                // Trả về 401 (Unauthorized) cho request không có xác thực thay vì 403 (Forbidden).
                // Giúp client phân biệt: 401 = cần token, 403 = có token nhưng không đủ quyền.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)
                ))
                // Chèn JwtAuthFilter chạy TRƯỚC UsernamePasswordAuthenticationFilter (filter xác thực
                // form-login mặc định của Spring Security) để SecurityContext có Authentication từ JWT
                // sớm nhất có thể, trước khi các bước authorization phía sau kiểm tra quyền truy cập.
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(httpExchangeLoggingFilter, BearerTokenAuthenticationFilter.class);

        if (keycloakEnabled) {
            http.oauth2ResourceServer(oauth2 -> oauth2
                    .bearerTokenResolver(bearerTokenResolver.getObject())
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakConverter)));
        }

        return http.build();
    }
}
