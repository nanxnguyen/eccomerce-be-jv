package com.example.backend.repository;

import com.example.backend.entity.Product;
import com.example.backend.entity.ProductStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

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
}
