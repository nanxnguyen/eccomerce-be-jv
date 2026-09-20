package com.example.backend.service;

import com.example.backend.dto.CartItemAddRequest; // CartItemAddRequest (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.CartItemQuantityRequest; // CartItemQuantityRequest (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.CartResponse; // CartResponse (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.entity.Cart; // Cart (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.CartItem; // CartItem (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.ProductVariant; // ProductVariant (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.User; // User (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.exception.ResourceNotFoundException; // ResourceNotFoundException (loại lỗi nghiệp vụ hoặc dữ liệu).
import com.example.backend.repository.CartItemRepository; // CartItemRepository (repository truy vấn/lưu dữ liệu qua JPA).
import com.example.backend.repository.CartRepository; // CartRepository (repository truy vấn/lưu dữ liệu qua JPA).
import com.example.backend.repository.ProductVariantRepository; // ProductVariantRepository (repository truy vấn/lưu dữ liệu qua JPA).
import com.example.backend.repository.UserRepository; // UserRepository (repository truy vấn/lưu dữ liệu qua JPA).
import org.springframework.stereotype.Service; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Service).
import org.springframework.transaction.annotation.Transactional; // quản lý transaction database (Transactional).

@Service
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductVariantRepository productVariantRepository;
    private final UserRepository userRepository;

    public CartService(CartRepository cartRepository,
                        CartItemRepository cartItemRepository,
                        ProductVariantRepository productVariantRepository,
                        UserRepository userRepository) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.productVariantRepository = productVariantRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public CartResponse getCart(String email) {
        Cart cart = getOrCreateCart(email);
        return CartResponse.from(cart, cartItemRepository.findByCartId(cart.getId()));
    }

    // Thêm variant đã có trong cart -> TĂNG quantity thay vì tạo dòng mới (UNIQUE(cart_id,
    // variant_id) ở Task 6 cũng chặn việc tạo dòng trùng nếu code này có bug).
    @Transactional
    public CartResponse addItem(String email, CartItemAddRequest request) {
        Cart cart = getOrCreateCart(email);
        ProductVariant variant = productVariantRepository.findById(request.variantId())
                .orElseThrow(() -> new ResourceNotFoundException("Product variant not found: " + request.variantId()));

        CartItem item = cartItemRepository.findByCartIdAndVariantId(cart.getId(), variant.getId())
                .orElseGet(() -> CartItem.builder().cart(cart).variant(variant).quantity(0).build());
        item.setQuantity(item.getQuantity() + request.quantity());
        cartItemRepository.save(item);

        return CartResponse.from(cart, cartItemRepository.findByCartId(cart.getId()));
    }

    @Transactional
    public CartResponse updateItem(String email, Long itemId, CartItemQuantityRequest request) {
        Cart cart = getOrCreateCart(email);
        CartItem item = cartItemRepository.findByIdAndCartId(itemId, cart.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found: " + itemId));
        item.setQuantity(request.quantity());
        cartItemRepository.save(item);
        return CartResponse.from(cart, cartItemRepository.findByCartId(cart.getId()));
    }

    @Transactional
    public CartResponse removeItem(String email, Long itemId) {
        Cart cart = getOrCreateCart(email);
        CartItem item = cartItemRepository.findByIdAndCartId(itemId, cart.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found: " + itemId));
        cartItemRepository.delete(item);
        return CartResponse.from(cart, cartItemRepository.findByCartId(cart.getId()));
    }

    private Cart getOrCreateCart(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return cartRepository.findByUserId(user.getId())
                .orElseGet(() -> cartRepository.save(Cart.builder().user(user).build()));
    }
}
