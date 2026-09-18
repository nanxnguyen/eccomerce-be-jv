package com.example.backend.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
// ProductRepository.findByStatusAndCategoryId (và biến thể có thêm tên) luôn filter theo cặp
// (status, category_id); Spring Data Page<> còn chạy kèm 1 query COUNT riêng cho cùng filter đó
// mỗi lần gọi. Postgres KHÔNG tự đánh index cho cột FK (category_id) như một số DB khác, nên không
// có index này thì cả 2 query trên đều Seq Scan toàn bảng - đo thực tế trên 1 triệu dòng: COUNT
// giảm từ ~49ms xuống ~9ms sau khi có index (Bitmap Index Scan thay vì Seq Scan).
@Table(name = "products", indexes = {
        @Index(name = "idx_products_status_category", columnList = "status, category_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(nullable = false)
    private String name;

    // slug là định danh duy nhất dùng cho URL (vd: /product/t-shirt), unique constraint chống trùng
    @Column(nullable = false, unique = true)
    private String slug;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductStatus status;

    // Product KHÔNG có field price/stock: một sản phẩm có nhiều biến thể (size/màu...),
    // mỗi biến thể mới có giá và tồn kho riêng -> price/stock thuộc về ProductVariant.
    // cascade = ALL: lưu/xóa Product thì lưu/xóa luôn variants đi kèm.
    // orphanRemoval = true: xóa 1 variant khỏi list này (không gán product khác) thì row đó bị xóa khỏi DB.
    @Builder.Default
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductVariant> variants = new ArrayList<>();

    // Tương tự variants: ảnh sản phẩm sống và chết theo Product cha.
    @Builder.Default
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductImage> images = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    // Chỉ add(variant) vào list là KHÔNG đủ: cột khóa ngoại product_id nằm ở phía ProductVariant
    // (owning side của quan hệ). Nếu không gọi variant.setProduct(this), Hibernate sẽ lưu product_id = NULL
    // dù list trong bộ nhớ đã có phần tử. Helper này set cả 2 chiều để tránh lỗi kinh điển đó.
    public void addVariant(ProductVariant variant) {
        variants.add(variant);
        variant.setProduct(this);
    }

    public void addImage(ProductImage image) {
        images.add(image);
        image.setProduct(this);
    }
}
