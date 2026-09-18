package com.example.backend.config;

import com.example.backend.entity.Category;
import com.example.backend.entity.Product;
import com.example.backend.entity.ProductImage;
import com.example.backend.entity.ProductStatus;
import com.example.backend.entity.ProductVariant;
import com.example.backend.entity.Role;
import com.example.backend.entity.User;
import com.example.backend.repository.CategoryRepository;
import com.example.backend.repository.ProductRepository;
import com.example.backend.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

// Chèn data mẫu (10 user, 20 category, 50 product + variant/image) để có data thật thao tác,
// khỏi tạo tay qua API/pgAdmin từng dòng. CHỈ chạy khi bật profile "seed"
// (./mvnw spring-boot:run -Dspring-boot.run.profiles=seed), không bao giờ tự chạy ở profile mặc
// định -> an toàn, không lo chèn nhầm data giả lúc chạy app bình thường. Kiểm tra
// userRepository.count() > 0 trước khi chèn -> chạy lại nhiều lần không bị nhân đôi data.
@Component
@Profile("seed")
public class DataSeeder implements CommandLineRunner {

    private static final String SEED_PASSWORD = "123456";

    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(UserRepository userRepository,
                       CategoryRepository categoryRepository,
                       ProductRepository productRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            System.out.println("[DataSeeder] Đã có data (userRepository.count() > 0) - bỏ qua seed.");
            return;
        }

        List<User> users = seedUsers();
        List<Category> categories = seedCategories();
        seedProducts(categories);

        System.out.println("[DataSeeder] Xong: " + users.size() + " user, " + categories.size()
                + " category, " + productRepository.count() + " product. Mật khẩu mọi user: " + SEED_PASSWORD);
    }

    private List<User> seedUsers() {
        List<User> users = new ArrayList<>();

        users.add(userRepository.save(User.builder()
                .name("Admin")
                .email("admin@example.com")
                .passwordHash(passwordEncoder.encode(SEED_PASSWORD))
                .role(Role.ADMIN)
                .build()));

        for (int i = 1; i <= 9; i++) {
            users.add(userRepository.save(User.builder()
                    .name("Khách hàng " + i)
                    .email("customer" + i + "@example.com")
                    .passwordHash(passwordEncoder.encode(SEED_PASSWORD))
                    .role(Role.CUSTOMER)
                    .build()));
        }

        return users;
    }

    private List<Category> seedCategories() {
        String[] names = {
                "Thời trang nam", "Thời trang nữ", "Giày dép", "Túi xách", "Đồng hồ",
                "Mỹ phẩm", "Điện thoại", "Laptop", "Phụ kiện điện tử", "Đồ gia dụng",
                "Nội thất", "Sách", "Văn phòng phẩm", "Đồ chơi trẻ em", "Thể thao",
                "Đồ dùng nhà bếp", "Trang sức", "Mẹ và bé", "Thú cưng", "Thực phẩm"
        };

        List<Category> categories = new ArrayList<>();
        for (String name : names) {
            categories.add(categoryRepository.save(Category.builder()
                    .name(name)
                    .slug(slugify(name))
                    .description("Danh mục " + name.toLowerCase())
                    .build()));
        }
        return categories;
    }

    // 50 product chia đều cho 20 category (vòng round-robin: category đầu dư ra sẽ có 3 product,
    // còn lại 2 product). Mỗi product có sẵn 2 variant (size M/L) + 1 ảnh minh họa (picsum.photos,
    // seed theo slug -> mỗi product 1 ảnh khác nhau nhưng ổn định, load lại vẫn ra ảnh cũ).
    private void seedProducts(List<Category> categories) {
        int total = 50;
        int[] countPerCategory = new int[categories.size()];
        for (int i = 0; i < total; i++) {
            countPerCategory[i % categories.size()]++;
        }

        int productIndex = 0;
        for (int c = 0; c < categories.size(); c++) {
            Category category = categories.get(c);
            for (int n = 1; n <= countPerCategory[c]; n++) {
                productIndex++;
                String name = category.getName() + " " + n;
                String slug = category.getSlug() + "-" + n;

                Product product = Product.builder()
                        .category(category)
                        .name(name)
                        .slug(slug)
                        .description("Sản phẩm mẫu thuộc danh mục " + category.getName().toLowerCase())
                        .status(ProductStatus.ACTIVE)
                        .build();

                BigDecimal basePrice = BigDecimal.valueOf(100_000 + (productIndex * 15_000));

                product.addVariant(ProductVariant.builder()
                        .sku(slug.toUpperCase() + "-M")
                        .size("M")
                        .color("Đen")
                        .price(basePrice)
                        .stockQuantity(20 + (productIndex % 30))
                        .build());

                product.addVariant(ProductVariant.builder()
                        .sku(slug.toUpperCase() + "-L")
                        .size("L")
                        .color("Đen")
                        .price(basePrice.add(BigDecimal.valueOf(20_000)))
                        .stockQuantity(15 + (productIndex % 20))
                        .build());

                product.addImage(ProductImage.builder()
                        .url("https://picsum.photos/seed/" + slug + "/600/600")
                        .isPrimary(true)
                        .sortOrder(1)
                        .build());

                productRepository.save(product);
            }
        }
    }

    // Chuyển tên tiếng Việt có dấu thành slug URL-friendly (vd "Thời trang nam" -> "thoi-trang-nam").
    // "đ" xử lý riêng vì Unicode không coi đây là "d + dấu kết hợp" nên Normalizer.NFD không tách được.
    private static String slugify(String input) {
        String replaced = input.replace("đ", "d").replace("Đ", "D");
        String normalized = Normalizer.normalize(replaced, Normalizer.Form.NFD);
        String noDiacritics = normalized.replaceAll("\\p{M}", "");
        return noDiacritics.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .trim()
                .replaceAll("\\s+", "-");
    }
}
