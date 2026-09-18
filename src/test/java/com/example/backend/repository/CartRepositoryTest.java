package com.example.backend.repository;

import com.example.backend.entity.Cart;
import com.example.backend.entity.CartItem;
import com.example.backend.entity.Category;
import com.example.backend.entity.Product;
import com.example.backend.entity.ProductStatus;
import com.example.backend.entity.ProductVariant;
import com.example.backend.entity.Role;
import com.example.backend.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class CartRepositoryTest {

    @Autowired private CartRepository cartRepository;
    @Autowired private CartItemRepository cartItemRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;

    @Test
    void savesCartWithItemsAndFindsByUser() {
        User user = userRepository.save(User.builder()
                .name("Nhut").email("nhut@example.com").passwordHash("hashed").role(Role.CUSTOMER).build());
        ProductVariant variant = saveVariant();

        Cart cart = cartRepository.save(Cart.builder().user(user).build());
        CartItem item = cartItemRepository.save(CartItem.builder().cart(cart).variant(variant).quantity(2).build());

        assertThat(cartRepository.findByUserId(user.getId())).contains(cart);
        assertThat(cartItemRepository.findByCartId(cart.getId())).containsExactly(item);
        assertThat(cartItemRepository.findByCartIdAndVariantId(cart.getId(), variant.getId())).contains(item);
        assertThat(cartItemRepository.findByIdAndCartId(item.getId(), cart.getId())).isPresent();
        assertThat(cartItemRepository.findByIdInAndCartId(List.of(item.getId()), cart.getId())).hasSize(1);
        assertThat(cartRepository.findByUserId(999_999L)).isEmpty();
    }

    private ProductVariant saveVariant() {
        Category category = categoryRepository.save(
                Category.builder().name("Fashion").slug("fashion").description("Clothes").build());
        Product product = Product.builder()
                .category(category).name("T-Shirt").slug("t-shirt").description("Basic tee")
                .status(ProductStatus.ACTIVE).build();
        product.addVariant(ProductVariant.builder()
                .sku("TSHIRT-BLK-M").size("M").color("Black").price(new BigDecimal("19.99")).stockQuantity(50).build());
        productRepository.saveAndFlush(product);
        return product.getVariants().get(0);
    }
}
