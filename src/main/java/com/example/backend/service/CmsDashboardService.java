package com.example.backend.service;

import com.example.backend.dto.CmsDashboardSummaryResponse;
import com.example.backend.dto.CmsRevenueBucket;
import com.example.backend.dto.CmsRevenueTimelineResponse;
import com.example.backend.entity.OrderStatus;
import com.example.backend.entity.PaymentStatus;
import com.example.backend.exception.InvalidRequestException;
import com.example.backend.repository.OrderRepository;
import com.example.backend.repository.OrderStatusAggregate;
import com.example.backend.repository.ProductRepository;
import com.example.backend.repository.ProductVariantRepository;
import com.example.backend.repository.DailyRevenueAggregate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.DayOfWeek;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CmsDashboardService {

    private static final int LOW_STOCK_THRESHOLD = 5; // Ngưỡng tồn kho được dùng cho cảnh báo dashboard.
    private static final long MAX_RANGE_DAYS = 366; // Giới hạn khoảng truy vấn tối đa một năm.
    private static final int MAX_BUCKETS = 366; // Giới hạn số mốc dữ liệu trả về.

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
        Instant generatedAt = Instant.now(); // Giữ thời điểm tạo báo cáo trong response.
        DashboardDateRange range = resolveRange(requestedFrom, requestedTo, generatedAt); // Dùng khoảng mặc định nếu client không gửi ngày.
        Instant from = range.from();
        Instant to = range.to();

        var totals = orderRepository.summarizePaidOrders(PaymentStatus.SUCCESS, from, to); // Chỉ cộng đơn đã thanh toán thành công.
        BigDecimal revenue = totals.getRevenue() == null ? BigDecimal.ZERO : totals.getRevenue(); // Thay kết quả rỗng bằng 0.
        long paidOrderCount = totals.getOrderCount() == null ? 0 : totals.getOrderCount(); // Không có đơn thì số lượng là 0.
        BigDecimal average = paidOrderCount == 0 ? BigDecimal.ZERO // Tránh chia cho 0 khi chưa có đơn.
                : revenue.divide(BigDecimal.valueOf(paidOrderCount), 2, RoundingMode.HALF_UP);

        Map<OrderStatus, Long> ordersByStatus = new EnumMap<>(OrderStatus.class); // Dùng enum làm khóa trạng thái đơn.
        for (OrderStatus status : OrderStatus.values()) ordersByStatus.put(status, 0L); // Có đủ trạng thái, kể cả trạng thái chưa phát sinh.
        for (OrderStatusAggregate row : orderRepository.countOrdersByStatusCreatedBetween(from, to)) {
            ordersByStatus.put(row.getStatus(), row.getOrderCount()); // Ghi số đơn thực tế cho trạng thái này.
        }

        return new CmsDashboardSummaryResponse(from, to, generatedAt, revenue, paidOrderCount, average,
                Map.copyOf(ordersByStatus), productRepository.count(), // Trả số sản phẩm hiện có.
                productVariantRepository.countAvailableAtOrBelow(LOW_STOCK_THRESHOLD)); // Đếm biến thể sắp hết hàng.
    }

    @Transactional(readOnly = true)
    public CmsRevenueTimelineResponse revenue(Instant requestedFrom, Instant requestedTo, String requestedGranularity) {
        Instant generatedAt = Instant.now(); // Ghi nhận thời điểm tạo chuỗi doanh thu.
        DashboardDateRange range = resolveRange(requestedFrom, requestedTo, generatedAt); // Chuẩn hóa khoảng thời gian cần thống kê.
        Granularity granularity = Granularity.parse(requestedGranularity); // Chỉ nhận ngày, tuần hoặc tháng.
        List<Instant> bucketStarts = bucketStarts(range, granularity); // Tạo đủ mốc, kể cả kỳ không có doanh thu.

        Map<Instant, CmsRevenueBucket> results = new HashMap<>(); // Tra cứu tổng doanh thu theo đầu mỗi kỳ.
        for (DailyRevenueAggregate row : orderRepository.summarizePaidOrdersByDay(PaymentStatus.SUCCESS,
                range.from(), range.to())) {
            Instant dayStart = row.getPaidDate().atStartOfDay(ZoneOffset.UTC).toInstant(); // Repository trả theo ngày UTC.
            Instant bucketStart = bucketStart(dayStart, granularity); // Gộp ngày vào đúng tuần/tháng nếu cần.
            CmsRevenueBucket daily = new CmsRevenueBucket(bucketStart, row.getRevenue(), row.getOrderCount());
            results.merge(bucketStart, daily, (current, next) -> new CmsRevenueBucket(bucketStart,
                    current.paidRevenue().add(next.paidRevenue()), // Cộng doanh thu các ngày cùng kỳ.
                    current.paidOrderCount() + next.paidOrderCount())); // Cộng số đơn của các ngày cùng kỳ.
        }

        List<CmsRevenueBucket> buckets = bucketStarts.stream().map(start -> { // Bổ sung kỳ rỗng với doanh thu bằng 0.
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
        Instant to = requestedTo == null ? now : requestedTo; // Mặc định kết thúc ở thời điểm hiện tại.
        Instant from = requestedFrom == null ? to.minus(30, ChronoUnit.DAYS) : requestedFrom; // Mặc định lấy 30 ngày gần nhất.
        validateRange(from, to);
        return new DashboardDateRange(from, to); // Trả khoảng thời gian đã kiểm tra.
    }

    static void validateRange(Instant from, Instant to) {
        if (!from.isBefore(to)) throw new InvalidRequestException("from must be before to"); // Ngày bắt đầu phải trước ngày kết thúc.
        if (Duration.between(from, to).compareTo(Duration.ofDays(MAX_RANGE_DAYS)) > 0) {
            throw new InvalidRequestException("Date range cannot exceed 366 days");
        }
    }

    private static List<Instant> bucketStarts(DashboardDateRange range, Granularity granularity) {
        ZonedDateTime cursor = bucketStart(range.from(), granularity).atZone(ZoneOffset.UTC); // Bắt đầu từ kỳ chứa ngày from.
        List<Instant> result = new ArrayList<>(); // Lưu mốc bắt đầu của từng kỳ.
        while (cursor.toInstant().isBefore(range.to())) {
            if (result.size() == MAX_BUCKETS) throw new InvalidRequestException("Revenue timeline cannot exceed 366 buckets");
            result.add(cursor.toInstant()); // Thêm mốc để response luôn có kỳ này.
            cursor = advance(cursor, granularity); // Chuyển sang kỳ tiếp theo.
        }
        return result;
    }

    private static Instant bucketStart(Instant instant, Granularity granularity) {
        ZonedDateTime cursor = instant.atZone(ZoneOffset.UTC); // Dùng UTC để các mốc không lệch múi giờ máy chủ.
        cursor = switch (granularity) {
            case DAY -> cursor.toLocalDate().atStartOfDay(ZoneOffset.UTC); // Đầu ngày UTC.
            case WEEK -> cursor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .toLocalDate().atStartOfDay(ZoneOffset.UTC); // Thứ Hai đầu tuần UTC.
            case MONTH -> cursor.withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC); // Ngày đầu tháng UTC.
        };
        return cursor.toInstant(); // Chuẩn hóa về Instant để dùng làm khóa nhóm.
    }

    private static ZonedDateTime advance(ZonedDateTime bucket, Granularity granularity) {
        return switch (granularity) { // Tăng đúng một ngày, tuần hoặc tháng.
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
            for (Granularity granularity : values()) if (granularity.value.equals(value)) return granularity; // Đổi giá trị request thành loại kỳ thống kê.
            throw new InvalidRequestException("granularity must be day, week, or month");
        }
    }
}
