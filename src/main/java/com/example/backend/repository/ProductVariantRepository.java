package com.example.backend.repository;

import com.example.backend.entity.ProductVariant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {
    boolean existsBySku(String sku);

    // PESSIMISTIC_WRITE: dịch ra SELECT ... FOR UPDATE, chặn mọi transaction khác đang cố lock
    // CÙNG dòng này cho tới khi transaction hiện tại commit/rollback. Bắt buộc dùng method này
    // (không dùng findById thường) ở BẤT KỲ chỗ nào đọc rồi ghi lại stockQuantity/reservedQuantity -
    // nếu không, 2 request checkout đồng thời cùng đọc availableQuantity=1 rồi cùng trừ, bán vượt
    // tồn kho (race condition kinh điển). Dùng @Query thủ công vì @Lock không áp được lên
    // findById() kế thừa từ JpaRepository, phải khai báo lại bằng 1 query rõ ràng.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ProductVariant v where v.id = :id")
    Optional<ProductVariant> findByIdForUpdate(@Param("id") Long id);
}
