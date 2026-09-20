package com.example.backend.repository;

import com.example.backend.entity.ProductVariant; // ProductVariant (entity ánh xạ dữ liệu với bảng database).
import jakarta.persistence.LockModeType; // annotation/API JPA để ánh xạ entity với database (LockModeType).
import org.springframework.data.jpa.repository.JpaRepository; // kiểu Spring Data hỗ trợ truy cập/phân trang database (JpaRepository).
import org.springframework.data.domain.Page; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Page).
import org.springframework.data.domain.Pageable; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Pageable).
import org.springframework.data.jpa.repository.Lock; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Lock).
import org.springframework.data.jpa.repository.Query; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Query).
import org.springframework.data.repository.query.Param; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Param).

import java.util.Optional; // biểu diễn kết quả có thể không tồn tại.

public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {
    boolean existsBySku(String sku);

    // Checkout đọc bản tồn kho mới nhất trước khi kiểm tra và cập nhật số lượng.
    // PESSIMISTIC_WRITE: dịch ra SELECT ... FOR UPDATE, chặn mọi transaction khác đang cố lock
    // CÙNG dòng này cho tới khi transaction hiện tại commit/rollback. Bắt buộc dùng method này
    // (không dùng findById thường) ở BẤT KỲ chỗ nào đọc rồi ghi lại stockQuantity/reservedQuantity -
    // nếu không, 2 request checkout đồng thời cùng đọc availableQuantity=1 rồi cùng trừ, bán vượt
    // tồn kho (race condition kinh điển). Dùng @Query thủ công vì @Lock không áp được lên
    // findById() kế thừa từ JpaRepository, phải khai báo lại bằng 1 query rõ ràng.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ProductVariant v where v.id = :id")
    Optional<ProductVariant> findByIdForUpdate(@Param("id") Long id);

    @Query("select count(v) from ProductVariant v where v.stockQuantity - v.reservedQuantity <= :threshold")
    long countAvailableAtOrBelow(@Param("threshold") int threshold);

    @Query("select p.id as productId, p.name as productName, v.id as variantId, v.sku as sku, " +
            "v.stockQuantity as stockQuantity, v.reservedQuantity as reservedQuantity, " +
            "(v.stockQuantity - v.reservedQuantity) as availableQuantity " +
            "from ProductVariant v join v.product p " +
            "where v.stockQuantity - v.reservedQuantity <= :threshold " +
            "and (:categoryId is null or p.category.id = :categoryId)")
    Page<LowStockVariantProjection> findLowStock(@Param("threshold") int threshold,
                                                 @Param("categoryId") Long categoryId,
                                                 Pageable pageable);

    @Query("select p.id as productId, p.name as productName, v.id as variantId, v.sku as sku, " +
            "v.stockQuantity as stockQuantity, v.reservedQuantity as reservedQuantity, " +
            "(v.stockQuantity - v.reservedQuantity) as availableQuantity from ProductVariant v join v.product p " +
            "where (:categoryId is null or p.category.id = :categoryId) " +
            "and (:threshold is null or v.stockQuantity - v.reservedQuantity <= :threshold)")
    Page<LowStockVariantProjection> findInventory(@Param("categoryId") Long categoryId,
                                                   @Param("threshold") Integer threshold,
                                                   Pageable pageable);
}
