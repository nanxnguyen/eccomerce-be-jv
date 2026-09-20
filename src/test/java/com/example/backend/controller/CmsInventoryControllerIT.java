package com.example.backend.controller;

import com.example.backend.entity.Category;
import com.example.backend.entity.Product;
import com.example.backend.entity.ProductStatus;
import com.example.backend.entity.ProductVariant;
import com.example.backend.entity.Role;
import com.example.backend.entity.User;
import com.example.backend.repository.CategoryRepository;
import com.example.backend.repository.ProductRepository;
import com.example.backend.repository.ProductVariantRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.security.JwtService;
import com.example.backend.support.TestDataCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CmsInventoryControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository productVariantRepository;
    @Autowired private JwtService jwtService;
    @Autowired private TestDataCleaner testDataCleaner;

    private String adminToken;

    @BeforeEach
    void setUp() {
        testDataCleaner.cleanAll();
        User admin = userRepository.save(User.builder().name("Admin").email("inventory-admin@example.com")
                .passwordHash("test").role(Role.ADMIN).build());
        adminToken = jwtService.generateToken(admin.getId(), admin.getRole().name());
    }

    @Test
    void lowStockListUsesAvailableStockAndPagesResults() throws Exception {
        Category category = categoryRepository.save(Category.builder().name("Tools").slug("tools").build());
        Product product = productRepository.save(Product.builder().category(category).name("Hammer")
                .slug("hammer").status(ProductStatus.ACTIVE).build());
        productVariantRepository.save(ProductVariant.builder().product(product).sku("HAMMER-LOW")
                .price(new BigDecimal("5.00")).stockQuantity(5).reservedQuantity(2).build());
        productVariantRepository.save(ProductVariant.builder().product(product).sku("HAMMER-OK")
                .price(new BigDecimal("5.00")).stockQuantity(20).reservedQuantity(0).build());

        mockMvc.perform(get("/api/cms/inventory/low-stock").header("Authorization", "Bearer " + adminToken)
                        .param("threshold", "3").param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("HAMMER-LOW"))
                .andExpect(jsonPath("$.content[0].stockQuantity").value(5))
                .andExpect(jsonPath("$.content[0].reservedQuantity").value(2))
                .andExpect(jsonPath("$.content[0].availableQuantity").value(3))
                .andExpect(jsonPath("$.content[0].threshold").value(3));

        mockMvc.perform(get("/api/cms/inventory/low-stock").header("Authorization", "Bearer " + adminToken)
                        .param("threshold", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lowStockListRequiresAdmin() throws Exception {
        mockMvc.perform(get("/api/cms/inventory/low-stock")).andExpect(status().isUnauthorized());
    }
}
