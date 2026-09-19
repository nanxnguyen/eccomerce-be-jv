package com.example.backend.controller;

import com.example.backend.dto.CategoryRequest;
import com.example.backend.dto.ProductRequest;
import com.example.backend.dto.ProductImageRequest;
import com.example.backend.dto.ProductVariantRequest;
import com.example.backend.entity.Category;
import com.example.backend.entity.Product;
import com.example.backend.entity.ProductStatus;
import com.example.backend.entity.Role;
import com.example.backend.entity.User;
import com.example.backend.repository.CategoryRepository;
import com.example.backend.repository.ProductRepository;
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
class CmsApiAuthorizationIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private TestDataCleaner testDataCleaner;

    private String adminToken;
    private String customerToken;

    @BeforeEach
    void setUp() {
        testDataCleaner.cleanAll();
        User admin = userRepository.save(User.builder().name("Admin").email("cms-admin@example.com")
                .passwordHash(passwordEncoder.encode("password123")).role(Role.ADMIN).build());
        User customer = userRepository.save(User.builder().name("Customer").email("cms-customer@example.com")
                .passwordHash(passwordEncoder.encode("password123")).role(Role.CUSTOMER).build());
        adminToken = jwtService.generateToken(admin.getId(), admin.getRole().name());
        customerToken = jwtService.generateToken(customer.getId(), customer.getRole().name());
    }

    @Test
    void cmsResourceFamiliesRequireAdmin() throws Exception {
        for (String path : new String[]{"/api/cms/products", "/api/cms/categories", "/api/cms/orders"}) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).header("Authorization", "Bearer " + customerToken))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(path).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk());
        }

        String categoryBody = objectMapper.writeValueAsString(new CategoryRequest("Fashion", "fashion-cms", null));
        mockMvc.perform(post("/api/cms/categories").header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(categoryBody))
                .andExpect(status().isForbidden());
    }

    @Test
    void cmsProductListIncludesInactiveWhilePublicListStillHidesIt() throws Exception {
        Category category = categoryRepository.save(Category.builder().name("Fashion").slug("fashion").build());
        productRepository.save(Product.builder().category(category).name("Available").slug("available")
                .status(ProductStatus.ACTIVE).build());
        productRepository.save(Product.builder().category(category).name("Hidden").slug("hidden")
                .status(ProductStatus.INACTIVE).build());

        mockMvc.perform(get("/api/cms/products").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].slug").value("available"));
    }

    @Test
    void adminCanCreateUpdateAndDeleteProductsThroughCmsRoutes() throws Exception {
        String categoryBody = objectMapper.writeValueAsString(new CategoryRequest("Fashion", "fashion-cms", null));
        String categoryJson = mockMvc.perform(post("/api/cms/categories")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(categoryBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long categoryId = objectMapper.readTree(categoryJson).get("id").asLong();

        String productBody = objectMapper.writeValueAsString(
                new ProductRequest(categoryId, "Hidden product", "hidden-cms", "draft", ProductStatus.INACTIVE));
        String productJson = mockMvc.perform(post("/api/cms/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(productBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andReturn().getResponse().getContentAsString();
        long productId = objectMapper.readTree(productJson).get("id").asLong();

        mockMvc.perform(get("/api/cms/products/{id}", productId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("hidden-cms"));

        String updateBody = objectMapper.writeValueAsString(
                new ProductRequest(categoryId, "Hidden product edited", "hidden-cms", "updated", null));
        mockMvc.perform(put("/api/cms/products/{id}", productId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        String variantBody = objectMapper.writeValueAsString(
                new ProductVariantRequest("SKU-CMS-1", "M", "Black", new java.math.BigDecimal("19.99"), 8));
        mockMvc.perform(post("/api/cms/products/{id}/variants", productId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(variantBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.variants[0].sku").value("SKU-CMS-1"));

        String imageBody = objectMapper.writeValueAsString(new ProductImageRequest("https://example.com/cms.png", true, 0));
        mockMvc.perform(post("/api/cms/products/{id}/images", productId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(imageBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.images.length()").value(1));

        mockMvc.perform(delete("/api/cms/products/{id}", productId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }
}
