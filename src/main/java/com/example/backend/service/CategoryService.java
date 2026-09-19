package com.example.backend.service;

import com.example.backend.dto.CategoryRequest;
import com.example.backend.dto.CategoryResponse;
import com.example.backend.entity.Category;
import com.example.backend.exception.DuplicateResourceException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.repository.CategoryRepository;
import com.example.backend.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.stream.Collectors;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    public CategoryService(CategoryRepository categoryRepository, ProductRepository productRepository) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> findAll() {
        List<Category> categories = categoryRepository.findAll();
        Map<Long, List<Category>> children = categories.stream()
                .filter(category -> category.getParent() != null)
                .collect(Collectors.groupingBy(category -> category.getParent().getId()));
        return categories.stream().filter(category -> category.getParent() == null)
                .map(category -> tree(category, children)).toList();
    }

    @Transactional(readOnly = true)
    public CategoryResponse findBySlug(String slug) {
        Category category = categoryRepository.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + slug));
        return tree(category, categoryRepository.findAll().stream().filter(item -> item.getParent() != null)
                .collect(Collectors.groupingBy(item -> item.getParent().getId())));
    }

    // Kiểm tra slug trùng TRƯỚC KHI save: nếu để DB tự chặn qua unique constraint, lỗi sẽ là
    // DataIntegrityViolationException chung chung -> rơi vào handler 500 của GlobalExceptionHandler
    // thay vì 409 Conflict rõ ràng. Check trước cho phép ném DuplicateResourceException có chủ đích.
    @Transactional
    public CategoryResponse create(CategoryRequest request) {
        if (categoryRepository.existsBySlug(request.slug())) {
            throw new DuplicateResourceException("Category slug already exists: " + request.slug());
        }

        Category category = Category.builder()
                .name(request.name())
                .slug(request.slug())
                .description(request.description())
                .parent(parent(request.parentId()))
                .build();

        return CategoryResponse.from(categoryRepository.save(category));
    }

    @Transactional
    public CategoryResponse update(Long id, CategoryRequest request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + id));

        // Chỉ check trùng slug khi slug THỰC SỰ thay đổi. Nếu không có điều kiện != này, PUT giữ
        // nguyên slug cũ (trường hợp phổ biến nhất - chỉ sửa name/description) sẽ luôn thấy
        // existsBySlug() = true (vì chính bản ghi đang sửa đã có slug đó) -> tự chặn nhầm chính nó.
        if (!category.getSlug().equals(request.slug()) && categoryRepository.existsBySlug(request.slug())) {
            throw new DuplicateResourceException("Category slug already exists: " + request.slug());
        }

        if (request.parentId() != null && isDescendant(category.getId(), request.parentId())) {
            throw new DuplicateResourceException("Category cannot be moved under itself or its descendants");
        }
        category.setParent(parent(request.parentId()));
        category.setName(request.name());
        category.setSlug(request.slug());
        category.setDescription(request.description());

        return CategoryResponse.from(categoryRepository.save(category));
    }

    @Transactional
    public void delete(Long id) {
        if (!categoryRepository.existsById(id)) {
            throw new ResourceNotFoundException("Category not found: " + id);
        }
        // Product.category là FK NOT NULL -> nếu còn Product nào thuộc category này, xóa thẳng
        // sẽ vi phạm ràng buộc FK ở tầng DB (DataIntegrityViolationException -> 409 chung chung,
        // xem GlobalExceptionHandler). Check trước ở đây để trả lỗi rõ ràng, đúng nguyên nhân.
        if (productRepository.existsByCategoryId(id)) {
            throw new DuplicateResourceException("Cannot delete category with id " + id + ": it still has products");
        }
        if (categoryRepository.existsByParentId(id)) {
            throw new DuplicateResourceException("Cannot delete category with id " + id + ": it still has subcategories");
        }
        categoryRepository.deleteById(id);
    }

    private Category parent(Long parentId) {
        if (parentId == null) return null;
        return categoryRepository.findById(parentId)
                .orElseThrow(() -> new ResourceNotFoundException("Parent category not found: " + parentId));
    }

    private boolean isDescendant(Long categoryId, Long candidateId) {
        Category cursor = categoryRepository.findById(candidateId)
                .orElseThrow(() -> new ResourceNotFoundException("Parent category not found: " + candidateId));
        while (cursor != null) {
            if (cursor.getId().equals(categoryId)) return true;
            cursor = cursor.getParent();
        }
        return false;
    }

    private CategoryResponse tree(Category category, Map<Long, List<Category>> children) {
        List<CategoryResponse> nested = children.getOrDefault(category.getId(), List.of()).stream()
                .map(child -> tree(child, children)).toList();
        return new CategoryResponse(category.getId(), category.getName(), category.getSlug(), category.getDescription(),
                category.getParent() == null ? null : category.getParent().getId(), nested);
    }
}
