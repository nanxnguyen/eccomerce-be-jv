package com.example.backend.security;

import jakarta.servlet.FilterChain; // thông tin request/response HTTP của Servlet (FilterChain).
import jakarta.servlet.ServletException; // thông tin request/response HTTP của Servlet (ServletException).
import jakarta.servlet.http.HttpServletRequest; // thông tin request/response HTTP của Servlet (HttpServletRequest).
import jakarta.servlet.http.HttpServletResponse; // thông tin request/response HTTP của Servlet (HttpServletResponse).
import org.jspecify.annotations.NonNull; // thư viện/kiểu NonNull được dùng trong file này.
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken; // thành phần Spring Security cho xác thực/phân quyền (UsernamePasswordAuthenticationToken).
import org.springframework.security.core.context.SecurityContextHolder; // thành phần Spring Security cho xác thực/phân quyền (SecurityContextHolder).
import org.springframework.security.core.userdetails.UserDetails; // thành phần Spring Security cho xác thực/phân quyền (UserDetails).
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource; // thành phần Spring Security cho xác thực/phân quyền (WebAuthenticationDetailsSource).
import org.springframework.stereotype.Component; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Component).
import org.springframework.web.filter.OncePerRequestFilter; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (OncePerRequestFilter).

import java.io.IOException; // thiết bị đọc/ghi dữ liệu chuẩn Java (IOException).

/**
 * Filter chạy trên MỖI request để kiểm tra JWT token trong header "Authorization: Bearer <token>".
 * Đây là API stateless (không dùng session), nên không có cơ chế "đăng nhập 1 lần, nhớ session"
 * như web truyền thống - mỗi request phải tự chứng minh danh tính bằng token của chính nó.
 *
 * Kế thừa OncePerRequestFilter (thay vì Filter thường) để đảm bảo logic chỉ chạy đúng 1 lần
 * cho mỗi request, kể cả khi request được forward/include nội bộ nhiều lần trong servlet container.
 *
 * Được đăng ký (ở SecurityConfig) chạy TRƯỚC UsernamePasswordAuthenticationFilter, để khi
 * Spring Security xử lý authorization, SecurityContext đã có sẵn Authentication (nếu token hợp lệ)
 * trước khi các filter xác thực mặc định của Spring Security kịp chạy.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsServiceImpl userDetailsService;

    public JwtAuthFilter(JwtService jwtService, UserDetailsServiceImpl userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        // Không có header hoặc sai định dạng "Bearer <token>" -> bỏ qua, để request đi tiếp
        // như một request chưa xác thực (anonymous). SecurityConfig sẽ quyết định request đó
        // có được phép truy cập endpoint hay không.
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        // Toàn bộ việc đọc token + tra cứu user nằm trong 1 try/catch RuntimeException:
        // - jwtService.extractUserId(token) ném lỗi nếu token sai định dạng/chữ ký hỏng/hết hạn.
        // - userDetailsService.loadUserById(userId) ném UsernameNotFoundException (cũng là
        //   RuntimeException) nếu userId trong token hợp lệ nhưng user đã bị xóa khỏi DB sau khi
        //   token được cấp. Trước đây lệnh này nằm NGOÀI try/catch nên lỗi này sẽ vọt thẳng ra
        //   ngoài filter -> 500 thô, kể cả trên endpoint public như GET /api/products, vì filter
        //   này chạy TRƯỚC khi Spring Security kịp phân biệt public/protected. Gộp chung 1 try để
        //   cả 2 loại lỗi đều được xử lý giống nhau: coi như request chưa xác thực (anonymous).
        try {
            Long userId = jwtService.extractUserId(token);
            // Chỉ set Authentication nếu chưa có (tránh ghi đè nếu có cơ chế xác thực khác đã chạy trước)
            if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = userDetailsService.loadUserById(userId);
                if (jwtService.isTokenValid(token)) {
                    // principal = userDetails, credentials = null (không cần mật khẩu nữa vì đã xác thực bằng token)
                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (RuntimeException ex) {
            // Token hỏng HOẶC user không còn tồn tại -> coi như chưa xác thực, không throw lỗi ở đây.
            // Nếu endpoint yêu cầu xác thực, Spring Security sẽ tự trả 401/403 phía sau (thay vì 500).
        }

        filterChain.doFilter(request, response);
    }
}
