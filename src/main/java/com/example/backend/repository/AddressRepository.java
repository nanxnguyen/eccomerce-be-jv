package com.example.backend.repository;

import com.example.backend.entity.Address; // Address (entity ánh xạ dữ liệu với bảng database).
import org.springframework.data.jpa.repository.JpaRepository; // kiểu Spring Data hỗ trợ truy cập/phân trang database (JpaRepository).
import org.springframework.data.jpa.repository.Query; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Query).
import org.springframework.data.repository.query.Param; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Param).

import java.util.List; // danh sách phần tử cùng kiểu.
import java.util.Optional; // biểu diễn kết quả có thể không tồn tại.

public interface AddressRepository extends JpaRepository<Address, Long> {
    // Tìm tất cả địa chỉ có user_id bằng ID tài khoản.
    List<Address> findByUserId(Long userId);

    // Owner-scoped lookup: dùng cho MỌI thao tác update/delete/setDefault - nếu addressId có thật
    // nhưng thuộc user khác, trả Optional.empty() (giống không tồn tại), KHÔNG được ném 403 (sẽ lộ
    // thông tin "address này có tồn tại, chỉ là không phải của bạn" cho kẻ dò id).
    // Checkout tìm theo ID + user ID để không cho dùng địa chỉ thuộc tài khoản khác.
    Optional<Address> findByIdAndUserId(Long id, Long userId);

    // @Query rõ ràng thay vì derived method findByUserIdAndIsDefaultTrue(): Spring Data phải suy
    // "IsDefault" ra field isDefault lúc parse tên method ở STARTUP - nếu suy sai (field boolean đặt
    // tên kiểu "isXxx" đôi khi bị resolver hiểu nhầm), lỗi là PropertyReferenceException làm SẬP
    // context của MỌI @SpringBootTest trong project, không chỉ test của riêng Address. @Query loại
    // bỏ rủi ro đó hoàn toàn.
    @Query("select a from Address a where a.user.id = :userId and a.isDefault = true")
    Optional<Address> findDefaultByUserId(@Param("userId") Long userId);
}
