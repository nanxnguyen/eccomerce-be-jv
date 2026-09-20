package com.example.backend.controller.publicapi; // package = chỗ file này thuộc về, phải khớp đường dẫn thư mục src/main/java/...

// DTO (Data Transfer Object) = object dùng riêng để nhận/trả dữ liệu qua API,
// KHÔNG dùng thẳng Entity (bảng DB) để tránh lộ field nhạy cảm hoặc cấu trúc DB ra ngoài.
import com.example.backend.dto.AddressRequest;   // dữ liệu client gửi lên khi tạo/sửa địa chỉ
import com.example.backend.dto.AddressResponse;  // dữ liệu server trả về client
import com.example.backend.service.AddressService; // lớp chứa business logic thật sự (controller chỉ gọi qua đây)

import jakarta.validation.Valid; // annotation bật validate tự động cho object (Jakarta Bean Validation, hậu duệ của javax.validation)

import org.springframework.http.HttpStatus;     // enum các mã HTTP status (200, 201, 404...) để trả về thay vì hardcode số
import org.springframework.http.ResponseEntity; // wrapper cho phép custom cả status code lẫn body của response

// Spring Security: lấy thông tin user đang đăng nhập từ request (token/session) mà không cần tự parse.
import org.springframework.security.core.annotation.AuthenticationPrincipal; // thành phần Spring Security cho xác thực/phân quyền (AuthenticationPrincipal).
import org.springframework.security.core.userdetails.UserDetails; // thành phần Spring Security cho xác thực/phân quyền (UserDetails).

// Các annotation đánh dấu method xử lý HTTP method tương ứng (GET/POST/PUT/DELETE) và đường dẫn (route).
import org.springframework.web.bind.annotation.DeleteMapping; // annotation Spring MVC để khai báo route/đọc request (DeleteMapping).
import org.springframework.web.bind.annotation.GetMapping; // annotation Spring MVC để khai báo route/đọc request (GetMapping).
import org.springframework.web.bind.annotation.PathVariable;   // lấy biến động trên URL, vd {id}
import org.springframework.web.bind.annotation.PostMapping; // annotation Spring MVC để khai báo route/đọc request (PostMapping).
import org.springframework.web.bind.annotation.PutMapping; // annotation Spring MVC để khai báo route/đọc request (PutMapping).
import org.springframework.web.bind.annotation.RequestBody;    // lấy dữ liệu JSON trong body request, parse thành object Java
import org.springframework.web.bind.annotation.RequestMapping; // khai báo prefix path chung cho cả class
import org.springframework.web.bind.annotation.RestController; // đánh dấu class này là REST controller (trả JSON, không trả view HTML)

import java.util.List; // kiểu danh sách chuẩn của Java, dùng cho endpoint trả nhiều địa chỉ

// @RestController = @Controller + @ResponseBody: mỗi method trả về data (JSON) thẳng vào response body,
// không cần render view (HTML) như @Controller thường dùng cho web truyền thống.
@RestController
// Prefix chung cho mọi endpoint trong class này -> full path sẽ là /api/addresses, /api/addresses/{id}, ...
@RequestMapping("/api/addresses")
public class AddressController {

    // Controller không tự chứa business logic, chỉ nhận request rồi giao cho Service xử lý (tách trách nhiệm).
    private final AddressService addressService;

    // Constructor injection: Spring tự động inject bean AddressService vào đây khi khởi tạo controller.
    // Ưu tiên constructor injection hơn @Autowired field vì field final -> immutable, dễ test (mock trong unit test).
    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    // GET /api/addresses -> lấy danh sách địa chỉ của user đang đăng nhập.
    @GetMapping
    public List<AddressResponse> list(@AuthenticationPrincipal UserDetails userDetails) {
        // @AuthenticationPrincipal: Spring Security tự lấy user hiện tại (đã login qua JWT/session) và bind vào tham số,
        // không cần tự parse token hay query SecurityContext thủ công.
        return addressService.list(userDetails.getUsername());
    }

    // POST /api/addresses -> tạo mới một địa chỉ.
    @PostMapping
    public ResponseEntity<AddressResponse> create(@AuthenticationPrincipal UserDetails userDetails,
                                                    @Valid @RequestBody AddressRequest request) {
        // @RequestBody: parse JSON trong body request thành object AddressRequest.
        // @Valid: kích hoạt validation theo annotation khai báo trong AddressRequest (vd @NotBlank, @Size...).
        //         Nếu không hợp lệ, Spring tự throw MethodArgumentNotValidException -> trả 400 (thường có @ControllerAdvice bắt lỗi này).
        // Tạo mới thành công theo chuẩn REST nên trả HTTP 201 Created, không phải 200 mặc định.
        return ResponseEntity.status(HttpStatus.CREATED).body(addressService.create(userDetails.getUsername(), request));
    }

    // PUT /api/addresses/{id} -> cập nhật địa chỉ theo id.
    @PutMapping("/{id}")
    public AddressResponse update(@AuthenticationPrincipal UserDetails userDetails,
                                   @PathVariable Long id, @Valid @RequestBody AddressRequest request) {
        // @PathVariable: lấy giá trị {id} trên URL, Spring tự convert String -> Long.
        return addressService.update(userDetails.getUsername(), id, request);
    }

    // DELETE /api/addresses/{id} -> xóa địa chỉ theo id.
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserDetails userDetails, @PathVariable Long id) {
        addressService.delete(userDetails.getUsername(), id);
        // Xóa thành công, không có nội dung trả về -> chuẩn REST là 204 No Content.
        return ResponseEntity.noContent().build();
    }

    // PUT /api/addresses/{id}/default -> đặt địa chỉ này làm địa chỉ mặc định của user.
    // Đây là 1 sub-resource action riêng (thay vì nhét field "isDefault" vào update() chung),
    // giúp endpoint có ý nghĩa rõ ràng và tránh side-effect ngoài ý muốn khi update thông tin khác.
    @PutMapping("/{id}/default")
    public AddressResponse setDefault(@AuthenticationPrincipal UserDetails userDetails, @PathVariable Long id) {
        return addressService.setDefault(userDetails.getUsername(), id);
    }
}
