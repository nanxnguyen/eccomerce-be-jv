package com.example.backend.repository;

import com.example.backend.entity.Category;
import com.example.backend.entity.Product;
import com.example.backend.entity.ProductStatus;
import com.example.backend.entity.ProductVariant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ProductVariantRepositoryTest {

    @Autowired
    private ProductVariantRepository productVariantRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Test
    void defaultsReservedQuantityToZeroAndComputesAvailable() {
        Long variantId = saveProductWithVariant(50);

        ProductVariant variant = productVariantRepository.findById(variantId).orElseThrow();
        assertThat(variant.getReservedQuantity()).isZero();
        assertThat(variant.getAvailableQuantity()).isEqualTo(50);

        variant.setReservedQuantity(20);
        assertThat(variant.getAvailableQuantity()).isEqualTo(30);
    }

    @Test
    @Transactional
    void findByIdForUpdateLocksAndReturnsTheVariant() {
        Long variantId = saveProductWithVariant(50);

        ProductVariant locked = productVariantRepository.findByIdForUpdate(variantId).orElseThrow();
        assertThat(locked.getId()).isEqualTo(variantId);

        assertThat(productVariantRepository.findByIdForUpdate(999_999L)).isEmpty();
    }

    private Long saveProductWithVariant(int stockQuantity) {
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
                .stockQuantity(stockQuantity)
                .build());

        productRepository.saveAndFlush(product);
        return product.getVariants().get(0).getId();
    }
}
