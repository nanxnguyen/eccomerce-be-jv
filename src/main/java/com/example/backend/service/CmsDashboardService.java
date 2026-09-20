package com.example.backend.service;

import com.example.backend.dto.CmsDashboardSummaryResponse; // CmsDashboardSummaryResponse (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.CmsRevenueBucket; // CmsRevenueBucket (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.CmsRevenueTimelineResponse; // CmsRevenueTimelineResponse (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.entity.OrderStatus; // OrderStatus (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.PaymentStatus; // PaymentStatus (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.exception.InvalidRequestException; // InvalidRequestException (loại lỗi nghiệp vụ hoặc dữ liệu).
import com.example.backend.repository.OrderRepository; // OrderRepository (repository truy vấn/lưu dữ liệu qua JPA).
import com.example.backend.repository.OrderStatusAggregate; // OrderStatusAggregate (repository truy vấn/lưu dữ liệu qua JPA).
import com.example.backend.repository.ProductRepository; // ProductRepository (repository truy vấn/lưu dữ liệu qua JPA).
import com.example.backend.repository.ProductVariantRepository; // ProductVariantRepository (repository truy vấn/lưu dữ liệu qua JPA).
import com.example.backend.repository.DailyRevenueAggregate; // DailyRevenueAggregate (repository truy vấn/lưu dữ liệu qua JPA).
import org.springframework.stereotype.Service; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Service).
import org.springframework.transaction.annotation.Transactional; // quản lý transaction database (Transactional).

import java.math.BigDecimal; // tính tiền chính xác, tránh sai số số thực.
import java.math.RoundingMode; // tiện ích toán học chuẩn Java (RoundingMode).
import java.time.Duration; // kiểu/thao tác thời gian chuẩn Java (Duration).
import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).
import java.time.temporal.ChronoUnit; // kiểu/thao tác thời gian chuẩn Java (ChronoUnit).
import java.time.DayOfWeek; // kiểu/thao tác thời gian chuẩn Java (DayOfWeek).
import java.time.ZoneOffset; // kiểu/thao tác thời gian chuẩn Java (ZoneOffset).
import java.time.ZonedDateTime; // kiểu/thao tác thời gian chuẩn Java (ZonedDateTime).
import java.time.temporal.TemporalAdjusters; // kiểu/thao tác thời gian chuẩn Java (TemporalAdjusters).
import java.util.ArrayList; // danh sách có thể thêm phần tử.
import java.util.EnumMap; // tiện ích collection chuẩn Java (EnumMap).
import java.util.HashMap; // bản đồ khóa–giá trị trong bộ nhớ.
import java.util.List; // danh sách phần tử cùng kiểu.
import java.util.Map; // bản đồ khóa–giá trị.

@Service
public class CmsDashboardService {

    private static final int LOW_STOCK_THRESHOLD = 5;
    private static final long MAX_RANGE_DAYS = 366;
    private static final int MAX_BUCKETS = 366;

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;

    public CmsDashboardService(OrderRepository orderRepository,
                               ProductRepository productRepository,
                               ProductVariantRepository productVariantRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.productVariantRepository = productVariantRepository;
    }

    @Transactional(readOnly = true)
    public CmsDashboardSummaryResponse summary(Instant requestedFrom, Instant requestedTo) {
        Instant generatedAt = Instant.now();
        DashboardDateRange range = resolveRange(requestedFrom, requestedTo, generatedAt);
        Instant from = range.from();
        Instant to = range.to();

        var totals = orderRepository.summarizePaidOrders(PaymentStatus.SUCCESS, from, to);
        BigDecimal revenue = totals.getRevenue() == null ? BigDecimal.ZERO : totals.getRevenue();
        long paidOrderCount = totals.getOrderCount() == null ? 0 : totals.getOrderCount();
        BigDecimal average = paidOrderCount == 0 ? BigDecimal.ZERO
                : revenue.divide(BigDecimal.valueOf(paidOrderCount), 2, RoundingMode.HALF_UP);

        Map<OrderStatus, Long> ordersByStatus = new EnumMap<>(OrderStatus.class);
        for (OrderStatus status : OrderStatus.values()) ordersByStatus.put(status, 0L);
        for (OrderStatusAggregate row : orderRepository.countOrdersByStatusCreatedBetween(from, to)) {
            ordersByStatus.put(row.getStatus(), row.getOrderCount());
        }

        return new CmsDashboardSummaryResponse(from, to, generatedAt, revenue, paidOrderCount, average,
                Map.copyOf(ordersByStatus), productRepository.count(),
                productVariantRepository.countAvailableAtOrBelow(LOW_STOCK_THRESHOLD));
    }

    @Transactional(readOnly = true)
    public CmsRevenueTimelineResponse revenue(Instant requestedFrom, Instant requestedTo, String requestedGranularity) {
        Instant generatedAt = Instant.now();
        DashboardDateRange range = resolveRange(requestedFrom, requestedTo, generatedAt);
        Granularity granularity = Granularity.parse(requestedGranularity);
        List<Instant> bucketStarts = bucketStarts(range, granularity);

        Map<Instant, CmsRevenueBucket> results = new HashMap<>();
        for (DailyRevenueAggregate row : orderRepository.summarizePaidOrdersByDay(PaymentStatus.SUCCESS,
                range.from(), range.to())) {
            Instant dayStart = row.getPaidDate().atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant bucketStart = bucketStart(dayStart, granularity);
            CmsRevenueBucket daily = new CmsRevenueBucket(bucketStart, row.getRevenue(), row.getOrderCount());
            results.merge(bucketStart, daily, (current, next) -> new CmsRevenueBucket(bucketStart,
                    current.paidRevenue().add(next.paidRevenue()), current.paidOrderCount() + next.paidOrderCount()));
        }

        List<CmsRevenueBucket> buckets = bucketStarts.stream().map(start -> {
            CmsRevenueBucket row = results.get(start);
            return row == null ? new CmsRevenueBucket(start, BigDecimal.ZERO, 0) : row;
        }).toList();
        return new CmsRevenueTimelineResponse(range.from(), range.to(), granularity.value, buckets);
    }

    public DashboardDateRange resolveRange(Instant requestedFrom, Instant requestedTo) {
        return resolveRange(requestedFrom, requestedTo, Instant.now());
    }

    public DashboardDateRange validatedRange(Instant requestedFrom, Instant requestedTo) {
        return resolveRange(requestedFrom, requestedTo, Instant.now());
    }

    private DashboardDateRange resolveRange(Instant requestedFrom, Instant requestedTo, Instant now) {
        Instant to = requestedTo == null ? now : requestedTo;
        Instant from = requestedFrom == null ? to.minus(30, ChronoUnit.DAYS) : requestedFrom;
        validateRange(from, to);
        return new DashboardDateRange(from, to);
    }

    static void validateRange(Instant from, Instant to) {
        if (!from.isBefore(to)) throw new InvalidRequestException("from must be before to");
        if (Duration.between(from, to).compareTo(Duration.ofDays(MAX_RANGE_DAYS)) > 0) {
            throw new InvalidRequestException("Date range cannot exceed 366 days");
        }
    }

    private static List<Instant> bucketStarts(DashboardDateRange range, Granularity granularity) {
        ZonedDateTime cursor = bucketStart(range.from(), granularity).atZone(ZoneOffset.UTC);
        List<Instant> result = new ArrayList<>();
        while (cursor.toInstant().isBefore(range.to())) {
            if (result.size() == MAX_BUCKETS) throw new InvalidRequestException("Revenue timeline cannot exceed 366 buckets");
            result.add(cursor.toInstant());
            cursor = advance(cursor, granularity);
        }
        return result;
    }

    private static Instant bucketStart(Instant instant, Granularity granularity) {
        ZonedDateTime cursor = instant.atZone(ZoneOffset.UTC);
        cursor = switch (granularity) {
            case DAY -> cursor.toLocalDate().atStartOfDay(ZoneOffset.UTC);
            case WEEK -> cursor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .toLocalDate().atStartOfDay(ZoneOffset.UTC);
            case MONTH -> cursor.withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC);
        };
        return cursor.toInstant();
    }

    private static ZonedDateTime advance(ZonedDateTime bucket, Granularity granularity) {
        return switch (granularity) {
            case DAY -> bucket.plusDays(1);
            case WEEK -> bucket.plusWeeks(1);
            case MONTH -> bucket.plusMonths(1);
        };
    }

    private enum Granularity {
        DAY("day"), WEEK("week"), MONTH("month");

        private final String value;

        Granularity(String value) {
            this.value = value;
        }

        private static Granularity parse(String value) {
            for (Granularity granularity : values()) if (granularity.value.equals(value)) return granularity;
            throw new InvalidRequestException("granularity must be day, week, or month");
        }
    }
}
