package com.example.backend.service;

import com.example.backend.dto.ProductImageRequest;
import com.example.backend.dto.ProductRequest;
import com.example.backend.dto.ProductResponse;
import com.example.backend.dto.ProductSummaryResponse;
import com.example.backend.dto.ProductVariantRequest;
import com.example.backend.entity.Category;
import com.example.backend.entity.Product;
import com.example.backend.entity.ProductImage;
import com.example.backend.entity.ProductStatus;
import com.example.backend.entity.ProductVariant;
import com.example.backend.exception.DuplicateResourceException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.repository.CategoryRepository;
import com.example.backend.repository.ProductRepository;
import com.example.backend.repository.ProductVariantRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final CategoryRepository categoryRepository;

    public ProductService(ProductRepository productRepository,
                           ProductVariantRepository productVariantRepository,
                           CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.productVariantRepository = productVariantRepository;
        this.categoryRepository = categoryRepository;
    }

    // Vì sao 4 nhánh gọi 4 repository method khác nhau thay vì 1 query với tham số optional:
    // Spring Data derived query method (findByX...) không hỗ trợ "tham số null thì bỏ qua điều
    // kiện đó" - nếu viết findByStatusAndCategoryIdAndNameContainingIgnoreCase() rồi truyền
    // categoryId/search = null, JPA sẽ sinh ra "WHERE category_id = NULL" (luôn false) thay vì bỏ
    // qua điều kiện. Cách khắc phục "chuẩn" là Specification/Criteria API động, nhưng với chỉ 2 bộ
    // lọc optional (categoryId, search) thì 4 tổ hợp cứng (không lọc / theo category / theo tên /
    // cả hai) đơn giản và dễ đọc hơn nhiều so với việc kéo thêm Specification cho 1 chỗ dùng.
    @Transactional(readOnly = true)
    public Page<ProductSummaryResponse> list(Long categoryId, String search, Pageable pageable) {
        Page<Product> page;
        boolean hasSearch = search != null && !search.isBlank();

        if (categoryId != null && hasSearch) {
            page = productRepository.findByStatusAndCategoryIdAndNameContainingIgnoreCase(ProductStatus.ACTIVE, categoryId, search, pageable);
        } else if (categoryId != null) {
            page = productRepository.findByStatusAndCategoryId(ProductStatus.ACTIVE, categoryId, pageable);
        } else if (hasSearch) {
            page = productRepository.findByStatusAndNameContainingIgnoreCase(ProductStatus.ACTIVE, search, pageable);
        } else {
            page = productRepository.findByStatus(ProductStatus.ACTIVE, pageable);
        }

        // ProductSummaryResponse (không có variants/images) thay vì ProductResponse đầy đủ: xem
        // giải thích N+1 ở @EntityGraph trong ProductRepository và ở chính DTO này.
        return page.map(ProductSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<ProductSummaryResponse> listAdmin(ProductStatus status, Long categoryId, String search, Pageable pageable) {
        String normalizedSearch = search == null || search.isBlank() ? null : search.trim();
        return productRepository.findAdminProducts(status, categoryId, normalizedSearch, pageable)
                .map(ProductSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public ProductResponse getByIdAdmin(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
        return ProductResponse.from(product);
    }

    // Endpoint public, ai cũng gọi được (không cần token) - phải lọc status = ACTIVE giống list().
    // Nếu không lọc: 1 sản phẩm bị admin ẩn (INACTIVE) qua update() vẫn xem được bình thường chỉ
    // cần biết đúng slug, phá vỡ mục đích của việc ẩn sản phẩm. Chưa có cơ chế "admin xem trước
    // sản phẩm đang ẩn" qua endpoint này - nằm ngoài scope hiện tại.
    @Transactional(readOnly = true)
    public ProductResponse getBySlug(String slug) {
        Product product = productRepository.findBySlug(slug)
                .filter(p -> p.getStatus() == ProductStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + slug));
        return ProductResponse.from(product);
    }

    // Kiểm tra category tồn tại TRƯỚC KHI kiểm tra slug trùng: nếu cả categoryId lẫn slug trong
    // request đều sai, lỗi "category không tồn tại" (404) có ý nghĩa hơn với client là lỗi "slug
    // trùng" (409) - vì request coi như tham chiếu tới 1 danh mục không có thật, việc slug có
    // trùng hay không lúc này không còn quan trọng nữa. Đảo thứ tự sẽ khiến client sửa slug xong
    // vẫn dính lỗi category ở lần gọi sau, gây khó hiểu.
    @Transactional
    public ProductResponse create(ProductRequest request) {
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + request.categoryId()));

        if (productRepository.existsBySlug(request.slug())) {
            throw new DuplicateResourceException("Product slug already exists: " + request.slug());
        }

        // request.status() null -> mặc định ACTIVE (client không cần biết tới field này vẫn tạo
        // sản phẩm bình thường, hiển thị ngay).
        Product product = Product.builder()
                .category(category)
                .name(request.name())
                .slug(request.slug())
                .description(request.description())
                .status(request.status() != null ? request.status() : ProductStatus.ACTIVE)
                .build();

        return ProductResponse.from(productRepository.save(product));
    }

    // Cùng thứ tự với create(): kiểm tra category tồn tại TRƯỚC KHI kiểm tra slug trùng. Nếu
    // request tham chiếu 1 categoryId không có thật, request đó vô nghĩa bất kể slug có trùng hay
    // không - nên báo lỗi "category không tồn tại" (404) trước, để client sửa xong vẫn còn thấy
    // lỗi slug (nếu có) ở lần gọi sau, thay vì báo nhầm lỗi slug trước rồi mới lộ ra lỗi category.
    // So slug mới với slug hiện tại để không tự chặn nhầm chính bản ghi đang sửa (giống
    // CategoryService.update()).
    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));

        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + request.categoryId()));

        if (!product.getSlug().equals(request.slug()) && productRepository.existsBySlug(request.slug())) {
            throw new DuplicateResourceException("Product slug already exists: " + request.slug());
        }

        product.setCategory(category);
        product.setName(request.name());
        product.setSlug(request.slug());
        product.setDescription(request.description());
        // request.status() null -> GIỮ NGUYÊN status hiện tại, không reset về ACTIVE. Khác với
        // create(): ở update(), null nghĩa là "client không muốn đổi status", không phải "chưa
        // từng có status". Admin ẩn 1 sản phẩm (set INACTIVE) xong sửa tên/mô tả ở lần gọi sau mà
        // không gửi lại status thì sản phẩm đó phải VẪN ẩn, không tự động hiện lại.
        if (request.status() != null) {
            product.setStatus(request.status());
        }

        return ProductResponse.from(productRepository.save(product));
    }

    @Transactional
    public void delete(Long id) {
        if (!productRepository.existsById(id)) {
            throw new ResourceNotFoundException("Product not found: " + id);
        }
        productRepository.deleteById(id);
    }

    // addVariant/addImage trả về ProductResponse ĐẦY ĐỦ (cả product cha) thay vì chỉ trả riêng
    // variant/image vừa tạo: client (trang admin) sau khi thêm 1 biến thể/ảnh thường cần render
    // lại ngay danh sách variants/images mới nhất của sản phẩm đó. Trả về full ProductResponse
    // giúp client cập nhật UI trong 1 lần gọi, không phải gọi thêm GET /api/products/{slug} lần
    // nữa chỉ để lấy list mới.
    @Transactional
    public ProductResponse addVariant(Long productId, ProductVariantRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        if (productVariantRepository.existsBySku(request.sku())) {
            throw new DuplicateResourceException("SKU already exists: " + request.sku());
        }

        ProductVariant variant = ProductVariant.builder()
                .sku(request.sku())
                .size(request.size())
                .color(request.color())
                .price(request.price())
                .stockQuantity(request.stockQuantity())
                .build();

        product.addVariant(variant);
        return ProductResponse.from(productRepository.save(product));
    }

    @Transactional
    public ProductResponse addImage(Long productId, ProductImageRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        ProductImage image = ProductImage.builder()
                .url(request.url())
                .isPrimary(request.isPrimary())
                .sortOrder(request.sortOrder())
                .build();

        product.addImage(image);
        return ProductResponse.from(productRepository.save(product));
    }
}
