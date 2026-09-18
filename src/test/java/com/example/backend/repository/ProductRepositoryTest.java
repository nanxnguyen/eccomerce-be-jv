package com.example.backend.repository;

import com.example.backend.entity.Category;
import com.example.backend.entity.Product;
import com.example.backend.entity.ProductImage;
import com.example.backend.entity.ProductStatus;
import com.example.backend.entity.ProductVariant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ProductRepositoryTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductVariantRepository productVariantRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void savesProductWithVariantsAndImagesAndFindsBySlug() {
        Category category = categoryRepository.save(
                Category.builder().name("Fashion").slug("fashion").description("Clothes").build());

        Product product = Product.builder()
                .category(category)
                .name("T-Shirt")
                .slug("t-shirt")
                .description("Basic tee")
                .status(ProductStatus.ACTIVE)
                .build();

        product.addVariant(ProductVariant.builder()
                .sku("TSHIRT-BLK-M")
                .size("M")
                .color("Black")
                .price(new BigDecimal("19.99"))
                .stockQuantity(50)
                .build());

        product.addImage(ProductImage.builder()
                .url("https://example.com/tshirt.jpg")
                .isPrimary(true)
                .sortOrder(1)
                .build());

        productRepository.saveAndFlush(product);
        entityManager.clear();

        Product found = productRepository.findBySlug("t-shirt").orElseThrow();
        assertThat(found.getVariants()).hasSize(1);
        assertThat(found.getVariants().get(0).getSku()).isEqualTo("TSHIRT-BLK-M");
        assertThat(found.getImages()).hasSize(1);
        assertThat(productRepository.existsBySlug("t-shirt")).isTrue();
        assertThat(productVariantRepository.existsBySku("TSHIRT-BLK-M")).isTrue();
    }
}
