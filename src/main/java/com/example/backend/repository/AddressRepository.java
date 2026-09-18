package com.example.backend.repository;

import com.example.backend.entity.Address;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AddressRepository extends JpaRepository<Address, Long> {
    List<Address> findByUserId(Long userId);

    // Owner-scoped lookup: dùng cho MỌI thao tác update/delete/setDefault - nếu addressId có thật
    // nhưng thuộc user khác, trả Optional.empty() (giống không tồn tại), KHÔNG được ném 403 (sẽ lộ
    // thông tin "address này có tồn tại, chỉ là không phải của bạn" cho kẻ dò id).
    Optional<Address> findByIdAndUserId(Long id, Long userId);

    // @Query rõ ràng thay vì derived method findByUserIdAndIsDefaultTrue(): Spring Data phải suy
    // "IsDefault" ra field isDefault lúc parse tên method ở STARTUP - nếu suy sai (field boolean đặt
    // tên kiểu "isXxx" đôi khi bị resolver hiểu nhầm), lỗi là PropertyReferenceException làm SẬP
    // context của MỌI @SpringBootTest trong project, không chỉ test của riêng Address. @Query loại
    // bỏ rủi ro đó hoàn toàn.
    @Query("select a from Address a where a.user.id = :userId and a.isDefault = true")
    Optional<Address> findDefaultByUserId(@Param("userId") Long userId);
}
