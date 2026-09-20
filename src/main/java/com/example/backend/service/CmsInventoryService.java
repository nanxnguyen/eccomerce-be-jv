package com.example.backend.service;

import com.example.backend.dto.CmsInventoryItem;
import com.example.backend.exception.InvalidRequestException;
import com.example.backend.repository.ProductVariantRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        int threshold = requestedThreshold == null ? DEFAULT_THRESHOLD : requestedThreshold; // Dùng ngưỡng mặc định nếu request không gửi ngưỡng.
        if (threshold < 0) throw new InvalidRequestException("threshold cannot be negative"); // Không chấp nhận ngưỡng tồn kho âm.
        if (pageable.getPageSize() > MAX_PAGE_SIZE) throw new InvalidRequestException("page size cannot exceed 100");

        Pageable stablePage = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                pageable.getSort().and(Sort.by("id"))); // Thêm ID làm thứ tự phụ để các trang ổn định.
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
