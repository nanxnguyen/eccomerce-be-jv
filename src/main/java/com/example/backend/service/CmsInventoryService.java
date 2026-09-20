package com.example.backend.service;

import com.example.backend.dto.CmsInventoryItem; // CmsInventoryItem (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.exception.InvalidRequestException; // InvalidRequestException (loại lỗi nghiệp vụ hoặc dữ liệu).
import com.example.backend.repository.ProductVariantRepository; // ProductVariantRepository (repository truy vấn/lưu dữ liệu qua JPA).
import org.springframework.data.domain.Page; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Page).
import org.springframework.data.domain.PageRequest; // kiểu Spring Data hỗ trợ truy cập/phân trang database (PageRequest).
import org.springframework.data.domain.Pageable; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Pageable).
import org.springframework.data.domain.Sort; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Sort).
import org.springframework.stereotype.Service; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Service).
import org.springframework.transaction.annotation.Transactional; // quản lý transaction database (Transactional).

@Service
public class CmsInventoryService {

    private static final int DEFAULT_THRESHOLD = 5;
    private static final int MAX_PAGE_SIZE = 100;

    private final ProductVariantRepository productVariantRepository;

    public CmsInventoryService(ProductVariantRepository productVariantRepository) {
        this.productVariantRepository = productVariantRepository;
    }

    @Transactional(readOnly = true)
    public Page<CmsInventoryItem> lowStock(Integer requestedThreshold, Long categoryId, Pageable pageable) {
        int threshold = requestedThreshold == null ? DEFAULT_THRESHOLD : requestedThreshold;
        if (threshold < 0) throw new InvalidRequestException("threshold cannot be negative");
        if (pageable.getPageSize() > MAX_PAGE_SIZE) throw new InvalidRequestException("page size cannot exceed 100");

        Pageable stablePage = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                pageable.getSort().and(Sort.by("id")));
        return productVariantRepository.findLowStock(threshold, categoryId, stablePage)
                .map(row -> new CmsInventoryItem(row.getProductId(), row.getProductName(), row.getVariantId(), row.getSku(),
                        row.getStockQuantity(), row.getReservedQuantity(), row.getAvailableQuantity(), threshold));
    }

    @Transactional(readOnly = true)
    public Page<CmsInventoryItem> inventory(Integer threshold, Long categoryId, Pageable pageable) {
        if (threshold != null && threshold < 0) throw new InvalidRequestException("threshold cannot be negative");
        if (pageable.getPageSize() > MAX_PAGE_SIZE) throw new InvalidRequestException("page size cannot exceed 100");
        Pageable stablePage = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                pageable.getSort().and(Sort.by("id")));
        return productVariantRepository.findInventory(categoryId, threshold, stablePage)
                .map(row -> new CmsInventoryItem(row.getProductId(), row.getProductName(), row.getVariantId(), row.getSku(),
                        row.getStockQuantity(), row.getReservedQuantity(), row.getAvailableQuantity(), threshold));
    }
}
