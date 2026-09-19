package com.example.backend.support;

import com.example.backend.repository.AddressRepository;
import com.example.backend.repository.CartItemRepository;
import com.example.backend.repository.CartRepository;
import com.example.backend.repository.CategoryRepository;
import com.example.backend.repository.OrderRepository;
import com.example.backend.repository.ProductRepository;
import com.example.backend.repository.RefreshTokenRepository;
import com.example.backend.repository.UserRepository;
import org.springframework.stereotype.Component;

// Dọn sạch DB theo đúng thứ tự FK cho MỌI @SpringBootTest IT. Cả suite dùng chung 1 H2 instance
// (DB_CLOSE_DELAY=-1 trong application.properties test) - một class không tự tạo carts/addresses
// vẫn có thể bị 1 class KHÁC (chạy trước nó trong cùng lần `mvn test`) để lại rác chặn ngay
// userRepository.deleteAll()/productRepository.deleteAll() của chính nó. Gọi cleanAll() thay vì
// mỗi IT tự viết lại chuỗi deleteAll() riêng - thêm bảng/entity mới (Order, Payment ở các task sau)
// chỉ cần sửa 1 chỗ này, không phải sửa từng IT class.
@Component
public class TestDataCleaner {

    private final OrderRepository orderRepository;
    private final CartItemRepository cartItemRepository;
    private final CartRepository cartRepository;
    private final AddressRepository addressRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    public TestDataCleaner(OrderRepository orderRepository,
                            CartItemRepository cartItemRepository,
                            CartRepository cartRepository,
                            AddressRepository addressRepository,
                            ProductRepository productRepository,
                            UserRepository userRepository,
                            CategoryRepository categoryRepository,
                            RefreshTokenRepository refreshTokenRepository) {
        this.orderRepository = orderRepository;
        this.cartItemRepository = cartItemRepository;
        this.cartRepository = cartRepository;
        this.addressRepository = addressRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.categoryRepository = categoryRepository;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    public void cleanAll() {
        // orders TRƯỚC cart_items/products: order_items.variant_id FK vào product_variants,
        // orders.user_id FK vào users - xoá products/users trước sẽ vi phạm ràng buộc FK nếu còn
        // order nào tham chiếu tới (cascade ALL trên Order.items/payment tự xoá order_items/payments
        // đi kèm, KHÔNG cần xoá riêng).
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        addressRepository.deleteAll();
        productRepository.deleteAll();
        // refresh_tokens.user_id FK vào users - phải xoá trước userRepository.deleteAll().
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        categoryRepository.deleteAll();
    }
}
