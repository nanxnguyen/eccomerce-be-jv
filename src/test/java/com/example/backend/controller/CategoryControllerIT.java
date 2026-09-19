package com.example.backend.controller;

import com.example.backend.dto.CategoryRequest;
import com.example.backend.dto.ProductRequest;
import com.example.backend.entity.Role;
import com.example.backend.entity.User;
import com.example.backend.repository.UserRepository;
import com.example.backend.security.JwtService;
import com.example.backend.support.TestDataCleaner;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CategoryControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private TestDataCleaner testDataCleaner;

    private String adminToken;

    @BeforeEach
    void setUpAdmin() {
        // Dọn TOÀN BỘ bảng liên quan (cart_items/carts/addresses/products/users/categories) trước
        // mỗi test, không chỉ product/category/user của riêng class này - cả suite dùng chung 1 H2
        // instance nên rác từ class KHÁC (Cart/Address) cũng có thể chặn userRepository.deleteAll()
        // ở đây. Xem TestDataCleaner.
        testDataCleaner.cleanAll();
        User admin = User.builder()
                .name("Admin")
                .email("admin@example.com")
                .passwordHash(passwordEncoder.encode("password123"))
                .role(Role.ADMIN)
                .build();
        userRepository.save(admin);
        adminToken = jwtService.generateToken(admin.getId(), admin.getRole().name());
    }

    @Test
    void adminCanCreateAndPublicCanRead() throws Exception {
        String body = objectMapper.writeValueAsString(new CategoryRequest("Fashion", "fashion", "Clothes"));

        mockMvc.perform(post("/api/categories")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("fashion"));

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("fashion"));

        mockMvc.perform(get("/api/categories/fashion"))
                .andExpect(status().isOk());
    }

    @Test
    void nonAdminCannotCreate() throws Exception {
        User customer = User.builder()
                .name("Cust")
                .email("cust@example.com")
                .passwordHash(passwordEncoder.encode("password123"))
                .role(Role.CUSTOMER)
                .build();
        userRepository.save(customer);
        String customerToken = jwtService.generateToken(customer.getId(), customer.getRole().name());

        String body = objectMapper.writeValueAsString(new CategoryRequest("Fashion", "fashion", "Clothes"));

        mockMvc.perform(post("/api/categories")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void duplicateSlugRejected() throws Exception {
        String body = objectMapper.writeValueAsString(new CategoryRequest("Fashion", "fashion", "Clothes"));

        mockMvc.perform(post("/api/categories")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/categories")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void adminUpdatesCategory() throws Exception {
        Long categoryId = createCategory("fashion", "Fashion");

        // Giữ nguyên slug, chỉ đổi name/description -> không chạm nhánh check trùng slug trong
        // CategoryService.update().
        String updateBody = objectMapper.writeValueAsString(
                new CategoryRequest("Fashion V2", "fashion", "Updated description"));

        mockMvc.perform(put("/api/categories/{id}", categoryId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Fashion V2"))
                .andExpect(jsonPath("$.description").value("Updated description"));
    }

    @Test
    void updateCategoryWithTakenSlugReturns409() throws Exception {
        createCategory("fashion", "Fashion");
        Long shoesId = createCategory("shoes", "Shoes");

        // Sửa "shoes" nhưng đổi slug thành "fashion" (đã bị category khác chiếm) -> phải bị chặn 409.
        String updateBody = objectMapper.writeValueAsString(
                new CategoryRequest("Shoes", "fashion", "Footwear"));

        mockMvc.perform(put("/api/categories/{id}", shoesId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isConflict());
    }

    @Test
    void adminDeletesCategory() throws Exception {
        // Không tạo Product nào trong category này, để không đụng nhánh chặn xóa của Finding 2
        // (deletingCategoryWithProductsReturns409 test riêng nhánh đó).
        Long categoryId = createCategory("fashion", "Fashion");

        mockMvc.perform(delete("/api/categories/{id}", categoryId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/categories/fashion"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingCategoryWithProductsReturns409() throws Exception {
        Long categoryId = createCategory("fashion", "Fashion");

        String productBody = objectMapper.writeValueAsString(
                new ProductRequest(categoryId, "T-Shirt", "t-shirt", "Basic tee", null));
        mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody))
                .andExpect(status().isCreated());

        // Category còn Product tham chiếu -> CategoryService.delete() (Finding 2) phải chặn ở
        // tầng application với 409 rõ ràng, thay vì để DB ném DataIntegrityViolationException.
        mockMvc.perform(delete("/api/categories/{id}", categoryId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownRouteUnderPermitAllPatternReturns404() throws Exception {
        // "/api/categories/**" (GET) permitAll trong SecurityConfig, nên request này KHÔNG bị
        // chặn ở tầng filter security (không cần token). Nhưng "/api/categories/foo/bar" có 2
        // segment sau "/categories/" trong khi @GetMapping("/{slug}") của CategoryController chỉ
        // khớp ĐÚNG 1 segment -> không handler nào khớp -> Spring ném NoResourceFoundException,
        // đúng nhánh cần verify của Finding 1 (khác với ResourceNotFoundException - trường hợp
        // route tồn tại nhưng slug không có trong DB).
        mockMvc.perform(get("/api/categories/foo/bar"))
                .andExpect(status().isNotFound());
    }

    // Helper dùng chung: tạo category qua API thật (giống pattern createProduct() trong
    // ProductControllerIT) để mỗi test đi đúng qua CategoryController/CategoryService.
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
}
