package com.example.backend.controller;

import com.example.backend.dto.CategoryRequest;
import com.example.backend.dto.ProductImageRequest;
import com.example.backend.dto.ProductRequest;
import com.example.backend.dto.ProductVariantRequest;
import com.example.backend.entity.Category;
import com.example.backend.entity.ProductStatus;
import com.example.backend.entity.Role;
import com.example.backend.entity.User;
import com.example.backend.repository.CategoryRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.security.JwtService;
import com.example.backend.support.TestDataCleaner;
import tools.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProductControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private TestDataCleaner testDataCleaner;

    private String adminToken;
    private Long categoryId;

    @BeforeEach
    void setUp() {
        // Dọn TOÀN BỘ bảng liên quan trước mỗi test, không chỉ product/category/user của riêng
        // class này - cả suite dùng chung 1 H2 instance nên rác từ class KHÁC (Cart/Address) cũng
        // có thể chặn deleteAll() ở đây. Xem TestDataCleaner.
        testDataCleaner.cleanAll();

        User admin = User.builder()
                .name("Admin")
                .email("admin@example.com")
                .passwordHash(passwordEncoder.encode("password123"))
                .role(Role.ADMIN)
                .build();
        userRepository.save(admin);
        adminToken = jwtService.generateToken(admin.getEmail(), admin.getRole().name());

        Category category = categoryRepository.save(
                Category.builder().name("Fashion").slug("fashion").description("Clothes").build());
        categoryId = category.getId();
    }

    @Test
    void adminCreatesProductWithVariantAndImage() throws Exception {
        String productBody = objectMapper.writeValueAsString(
                new ProductRequest(categoryId, "T-Shirt", "t-shirt", "Basic tee", null));

        mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("t-shirt"));

        String variantBody = objectMapper.writeValueAsString(
                new ProductVariantRequest("TSHIRT-BLK-M", "M", "Black", new BigDecimal("19.99"), 50));

        // Cố tình dùng slug "t-shirt" làm {id} (không phải số) để chứng minh endpoint này
        // nhận id dạng Long: Spring không convert được "t-shirt" -> Long, ném
        // MethodArgumentTypeMismatchException. GlobalExceptionHandler giờ có handler riêng cho
        // ngoại lệ này (Finding 1 của final review) -> trả 400 Bad Request rõ ràng, thay vì rơi
        // vào handler Exception.class chung và trả 500 như trước.
        mockMvc.perform(post("/api/products/{slug}/variants", "t-shirt")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(variantBody))
                .andExpect(status().isBadRequest());

        Long productId = objectMapper.readTree(
                mockMvc.perform(get("/api/products/t-shirt"))
                        .andReturn().getResponse().getContentAsString()
        ).get("id").asLong();

        mockMvc.perform(post("/api/products/{id}/variants", productId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(variantBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.variants[0].sku").value("TSHIRT-BLK-M"));

        String imageBody = objectMapper.writeValueAsString(
                new ProductImageRequest("https://example.com/tshirt.jpg", true, 1));

        // Jackson 3 (tools.jackson) giữ nguyên tên record component "isPrimary" khi serialize,
        // KHÔNG áp dụng quy tắc bean-getter cổ điển (bỏ tiền tố "is" cho boolean getter) như với
        // POJO thường -> key JSON thực tế là "isPrimary", không phải "primary". Đã verify bằng
        // cách in raw response body thực tế trước khi chốt assertion này (xem task-12-report.md).
        mockMvc.perform(post("/api/products/{id}/images", productId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(imageBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.images[0].url").value("https://example.com/tshirt.jpg"))
                .andExpect(jsonPath("$.images[0].isPrimary").value(true));

        mockMvc.perform(get("/api/products/t-shirt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants.length()").value(1))
                .andExpect(jsonPath("$.images.length()").value(1));

        mockMvc.perform(get("/api/products").param("categoryId", categoryId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].slug").value("t-shirt"));
    }

    @Test
    void nonAdminCannotCreateProduct() throws Exception {
        User customer = User.builder()
                .name("Cust")
                .email("cust@example.com")
                .passwordHash(passwordEncoder.encode("password123"))
                .role(Role.CUSTOMER)
                .build();
        userRepository.save(customer);
        String customerToken = jwtService.generateToken(customer.getEmail(), customer.getRole().name());

        String productBody = objectMapper.writeValueAsString(
                new ProductRequest(categoryId, "T-Shirt", "t-shirt", "Basic tee", null));

        mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownSlugReturns404() throws Exception {
        mockMvc.perform(get("/api/products/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminUpdatesProduct() throws Exception {
        Long productId = createProduct("t-shirt", "T-Shirt");

        String updateBody = objectMapper.writeValueAsString(
                new ProductRequest(categoryId, "T-Shirt V2", "t-shirt", "Updated tee", null));

        mockMvc.perform(put("/api/products/{id}", productId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("T-Shirt V2"))
                .andExpect(jsonPath("$.description").value("Updated tee"));
    }

    @Test
    void updateWithTakenSlugReturns409() throws Exception {
        createProduct("t-shirt", "T-Shirt");
        Long jeansId = createProduct("jeans", "Jeans");

        // Sửa "jeans" nhưng đổi slug thành "t-shirt" (đã bị sản phẩm khác chiếm) -> phải bị chặn 409.
        String updateBody = objectMapper.writeValueAsString(
                new ProductRequest(categoryId, "Jeans", "t-shirt", "Denim", null));

        mockMvc.perform(put("/api/products/{id}", jeansId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isConflict());
    }

    @Test
    void adminDeletesProductCascadesVariantsAndImages() throws Exception {
        Long productId = createProduct("t-shirt", "T-Shirt");
        addVariant(productId, "TSHIRT-BLK-M");
        addImage(productId, "https://example.com/tshirt.jpg");

        mockMvc.perform(delete("/api/products/{id}", productId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        // Chứng minh CascadeType.ALL + orphanRemoval trên Product.variants/images (khai báo ở
        // Task 10) hoạt động thật: xóa Product xong, GET lại slug cũ phải trả 404 (không chỉ
        // riêng Product biến mất, mà cả variant/image con cũng không còn sót lại "mồ côi" trong DB
        // gây lỗi FK hay rác dữ liệu). Đây là test đầu tiên trong cả plan thực sự verify hành vi
        // cascade này thay vì chỉ khai báo.
        mockMvc.perform(get("/api/products/t-shirt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void inactiveProductIsHiddenFromPublicDetailEndpoint() throws Exception {
        String body = objectMapper.writeValueAsString(
                new ProductRequest(categoryId, "T-Shirt", "t-shirt", "Basic tee", ProductStatus.INACTIVE));

        mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        // getBySlug() lọc status = ACTIVE (ProductService) -> sản phẩm INACTIVE trả 404 dù biết
        // đúng slug, kể cả với request không token (endpoint này public, dùng chung cho mọi
        // người, không có cơ chế "admin xem trước sản phẩm đang ẩn" trong scope fix này).
        mockMvc.perform(get("/api/products/t-shirt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updatingStatusToInactiveHidesPreviouslyVisibleProduct() throws Exception {
        Long productId = createProduct("t-shirt", "T-Shirt");

        mockMvc.perform(get("/api/products/t-shirt"))
                .andExpect(status().isOk());

        String updateBody = objectMapper.writeValueAsString(
                new ProductRequest(categoryId, "T-Shirt", "t-shirt", "Basic tee", ProductStatus.INACTIVE));

        mockMvc.perform(put("/api/products/{id}", productId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        mockMvc.perform(get("/api/products/t-shirt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updatingWithoutStatusKeepsCurrentStatus() throws Exception {
        Long productId = createProduct("t-shirt", "T-Shirt");

        // status = null trong request update -> ProductService phải GIỮ NGUYÊN status hiện tại
        // (ACTIVE, do createProduct() không truyền status), không được reset lại ACTIVE một cách
        // vô nghĩa (đằng nào cũng đang ACTIVE) và cũng không được vô tình đổi sang null/ACTIVE nếu
        // sau này logic thay đổi. Test này chốt hành vi "null = giữ nguyên" cho nhánh update.
        String updateBody = objectMapper.writeValueAsString(
                new ProductRequest(categoryId, "T-Shirt V2", "t-shirt", "Basic tee", null));

        mockMvc.perform(put("/api/products/{id}", productId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void listingProductsDoesNotTriggerNPlusOneQueries() throws Exception {
        // Cố tình cho mỗi product 1 category KHÁC NHAU: nếu dùng chung 1 category, Hibernate chỉ
        // load category đó 1 lần rồi cache trong persistence context của request (open-in-view),
        // các lần lazy-load sau trong CÙNG request hit cache chứ không ra query mới -> che mất N+1,
        // test sẽ pass "giả" (đã tự bắt gặp lỗi này khi viết test, xem lịch sử session).
        Long categoryA = createCategory("cat-a", "Category A");
        Long categoryB = createCategory("cat-b", "Category B");
        Long categoryC = createCategory("cat-c", "Category C");
        createProduct(categoryA, "t-shirt-1", "Ao thun 1");
        createProduct(categoryB, "t-shirt-2", "Ao thun 2");
        createProduct(categoryC, "t-shirt-3", "Ao thun 3");

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3));

        // entityFetchCount = số lần Hibernate phải load thêm 1 entity ngoài query chính (tức lazy
        // proxy init riêng lẻ - đúng định nghĩa của N+1). KHÔNG dùng getQueryExecutionCount(): số
        // đó chỉ đếm query HQL/JPQL chạy tường minh, không tính SELECT ngầm khi khởi tạo lazy proxy
        // theo id - đã tự kiểm chứng điều này khi viết test (in thử statistics ra mới phát hiện).
        // Trước khi có @EntityGraph: mỗi sản phẩm 1 category khác nhau -> 3 lazy fetch riêng lẻ
        // (entityFetchCount = 3). Sau khi thêm @EntityGraph("category") vào ProductRepository:
        // category được JOIN ngay trong query chính -> entityFetchCount = 0, không phụ thuộc số
        // sản phẩm trên trang.
        assertThat(statistics.getEntityFetchCount()).isZero();
        // collectionFetchCount: tương tự entityFetchCount nhưng cho @OneToMany (variants/images).
        // ProductResponse (dùng cho GET /{slug} - 1 sản phẩm) cần đủ variants/images nên chấp nhận
        // lazy-load ở đó. Nhưng list() trả nhiều sản phẩm cùng lúc thì KHÔNG nên kéo theo cả
        // variants/images từng dòng - trang danh sách không cần dữ liệu đó, chỉ trang chi tiết mới
        // cần. Vì vậy list() phải dùng DTO rút gọn (không có variants/images) để không chạm tới 2
        // collection này -> collectionFetchCount phải bằng 0.
        assertThat(statistics.getCollectionFetchCount()).isZero();
    }

    // Helper dùng chung: tạo product qua API thật (không insert thẳng vào repository) để mỗi test
    // đi đúng qua ProductController/ProductService như một client thực sự sẽ gọi.
    private Long createProduct(String slug, String name) throws Exception {
        return createProduct(categoryId, slug, name);
    }

    private Long createProduct(Long categoryId, String slug, String name) throws Exception {
        String body = objectMapper.writeValueAsString(new ProductRequest(categoryId, name, slug, "Basic tee", null));
        String response = mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private Long createCategory(String slug, String name) throws Exception {
        String body = objectMapper.writeValueAsString(new CategoryRequest(name, slug, "Description"));
        String response = mockMvc.perform(post("/api/categories")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private void addVariant(Long productId, String sku) throws Exception {
        String body = objectMapper.writeValueAsString(
                new ProductVariantRequest(sku, "M", "Black", new BigDecimal("19.99"), 50));
        mockMvc.perform(post("/api/products/{id}/variants", productId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    private void addImage(Long productId, String url) throws Exception {
        String body = objectMapper.writeValueAsString(new ProductImageRequest(url, true, 1));
        mockMvc.perform(post("/api/products/{id}/images", productId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }
}
