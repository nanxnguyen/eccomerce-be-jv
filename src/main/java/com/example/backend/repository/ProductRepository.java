package com.example.backend.repository;

import com.example.backend.entity.Product; // Product (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.ProductStatus; // ProductStatus (entity ánh xạ dữ liệu với bảng database).
import org.springframework.data.domain.Page; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Page).
import org.springframework.data.domain.Pageable; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Pageable).
import org.springframework.data.jpa.repository.EntityGraph; // kiểu Spring Data hỗ trợ truy cập/phân trang database (EntityGraph).
import org.springframework.data.jpa.repository.JpaRepository; // kiểu Spring Data hỗ trợ truy cập/phân trang database (JpaRepository).
import org.springframework.data.jpa.repository.Query; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Query).
import org.springframework.data.repository.query.Param; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Param).

import java.util.Optional; // biểu diễn kết quả có thể không tồn tại.

public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findBySlug(String slug);
    boolean existsBySlug(String slug);
    // Dùng để chặn xóa Category còn Product tham chiếu tới (xem CategoryService.delete()) trước
    // khi để DB tự ném DataIntegrityViolationException do ràng buộc FK NOT NULL Product.category.
    boolean existsByCategoryId(Long categoryId);

    // @EntityGraph(attributePaths = "category"): bắt Spring Data JOIN sẵn bảng categories ngay
    // trong query chính (thay vì để category ở chế độ lazy mặc định). Không có annotation này,
    // ProductService.list() trả về 1 trang 20 sản phẩm sẽ kích hoạt thêm 20 query lazy-load
    // category riêng lẻ (1 query/dòng) khi ProductSummaryResponse.from() đọc product.getCategory()
    // - đây chính là lỗi N+1 kinh điển. Có @EntityGraph: chỉ 1 query duy nhất cho cả trang, bất kể
    // trang có bao nhiêu sản phẩm.
    @EntityGraph(attributePaths = "category")
    Page<Product> findByStatus(ProductStatus status, Pageable pageable);

    @EntityGraph(attributePaths = "category")
    Page<Product> findByStatusAndCategoryId(ProductStatus status, Long categoryId, Pageable pageable);

    @EntityGraph(attributePaths = "category")
    Page<Product> findByStatusAndNameContainingIgnoreCase(ProductStatus status, String name, Pageable pageable);

    @EntityGraph(attributePaths = "category")
    Page<Product> findByStatusAndCategoryIdAndNameContainingIgnoreCase(ProductStatus status, Long categoryId, String name, Pageable pageable);

    @EntityGraph(attributePaths = "category")
    @Query("select p from Product p where (:status is null or p.status = :status) " +
            "and (:categoryId is null or p.category.id = :categoryId) " +
            "and (:search is null or lower(p.name) like lower(concat('%', :search, '%'))) ")
    Page<Product> findAdminProducts(@Param("status") ProductStatus status,
                                    @Param("categoryId") Long categoryId,
                                    @Param("search") String search,
                                    Pageable pageable);
}
