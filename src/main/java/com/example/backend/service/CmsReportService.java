package com.example.backend.service;

import com.example.backend.dto.*; // * (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.entity.PaymentStatus; // PaymentStatus (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.exception.InvalidRequestException; // InvalidRequestException (loại lỗi nghiệp vụ hoặc dữ liệu).
import com.example.backend.repository.OrderRepository; // OrderRepository (repository truy vấn/lưu dữ liệu qua JPA).
import java.time.DayOfWeek; // kiểu/thao tác thời gian chuẩn Java (DayOfWeek).
import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).
import java.time.LocalDate; // kiểu/thao tác thời gian chuẩn Java (LocalDate).
import java.time.temporal.TemporalAdjusters; // kiểu/thao tác thời gian chuẩn Java (TemporalAdjusters).
import java.util.HashMap; // bản đồ khóa–giá trị trong bộ nhớ.
import java.util.List; // danh sách phần tử cùng kiểu.
import java.util.Map; // bản đồ khóa–giá trị.
import org.springframework.data.domain.*; // kiểu Spring Data hỗ trợ truy cập/phân trang database (*).
import org.springframework.stereotype.Service; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Service).
import org.springframework.transaction.annotation.Transactional; // quản lý transaction database (Transactional).

@Service
public class CmsReportService {
  private static final int MAX_PAGE_SIZE = 100, DEFAULT_THRESHOLD = 5;
  private final OrderRepository orders;
  private final CmsInventoryService inventory;
  private final CmsDashboardService dashboard;

  public CmsReportService(
      OrderRepository orders, CmsInventoryService inventory, CmsDashboardService dashboard) {
    this.orders = orders;
    this.inventory = inventory;
    this.dashboard = dashboard;
  }

  @Transactional(readOnly = true)
  public Page<CmsSalesReportRow> sales(
      Instant from, Instant to, String granularity, Pageable pageable) {
    DashboardDateRange range = range(from, to);
    if (!List.of("day", "week", "month").contains(granularity))
      throw new InvalidRequestException("granularity must be day, week, or month");
    Map<SalesKey, CmsSalesReportRow> grouped = new HashMap<>();
    for (var row : orders.salesReport(PaymentStatus.SUCCESS, range.from(), range.to())) {
      LocalDate bucket =
          switch (granularity) {
            case "week" ->
                row.getBucketDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month" -> row.getBucketDate().withDayOfMonth(1);
            default -> row.getBucketDate();
          };
      SalesKey key = new SalesKey(bucket, row.getPaymentMethod(), row.getOrderStatus());
      grouped.merge(
          key,
          new CmsSalesReportRow(
              bucket,
              row.getPaymentMethod(),
              row.getOrderStatus(),
              row.getPaidRevenue(),
              row.getPaidOrderCount()),
          (a, b) ->
              new CmsSalesReportRow(
                  bucket,
                  a.paymentMethod(),
                  a.orderStatus(),
                  a.paidRevenue().add(b.paidRevenue()),
                  a.paidOrderCount() + b.paidOrderCount()));
    }
    List<CmsSalesReportRow> rows =
        grouped.values().stream()
            .sorted(
                java.util.Comparator.comparing(CmsSalesReportRow::bucketStart)
                    .thenComparing(r -> r.paymentMethod().name())
                    .thenComparing(r -> r.orderStatus().name()))
            .toList();
    return page(rows, pageable);
  }

  @Transactional(readOnly = true)
  public Page<CmsProductSalesReportRow> products(
      Instant from, Instant to, String sort, Pageable pageable) {
    DashboardDateRange range = range(from, to);
    if (!List.of("units", "revenue").contains(sort))
      throw new InvalidRequestException("sort must be units or revenue");
    if (pageable.getPageSize() > MAX_PAGE_SIZE)
      throw new InvalidRequestException("page size cannot exceed 100");
    PageRequest bounded = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    var report =
        sort.equals("units")
            ? orders.productSalesByUnits(
                PaymentStatus.SUCCESS.name(), range.from(), range.to(), bounded)
            : orders.productSalesByRevenue(
                PaymentStatus.SUCCESS.name(), range.from(), range.to(), bounded);
    return report.map(
        r ->
            new CmsProductSalesReportRow(
                r.getProductName(), r.getSku(), r.getUnitsSold(), r.getRevenue()));
  }

  @Transactional(readOnly = true)
  public Page<CmsInventoryItem> inventory(Long categoryId, Integer threshold, Pageable pageable) {
    return inventory.inventory(threshold, categoryId, pageable);
  }

  private DashboardDateRange range(Instant from, Instant to) {
    return dashboard.validatedRange(from, to);
  }

  private record SalesKey(
      LocalDate date,
      com.example.backend.entity.PaymentMethod method,
      com.example.backend.entity.OrderStatus status) {}

  private <T> Page<T> page(List<T> rows, Pageable pageable) {
    if (pageable.getPageSize() > MAX_PAGE_SIZE)
      throw new InvalidRequestException("page size cannot exceed 100");
    int start = (int) Math.min(pageable.getOffset(), rows.size()),
        end = Math.min(start + pageable.getPageSize(), rows.size());
    return new PageImpl<>(rows.subList(start, end), pageable, rows.size());
  }
}
