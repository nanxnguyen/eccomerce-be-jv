package com.example.backend.repository;

import com.example.backend.entity.Order; // Order (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.OrderStatus; // OrderStatus (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.PaymentStatus; // PaymentStatus (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.PaymentMethod; // PaymentMethod (entity ánh xạ dữ liệu với bảng database).
import org.springframework.data.domain.Page; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Page).
import org.springframework.data.domain.Pageable; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Pageable).
import org.springframework.data.jpa.repository.JpaRepository; // kiểu Spring Data hỗ trợ truy cập/phân trang database (JpaRepository).
import org.springframework.data.jpa.repository.Query; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Query).
import org.springframework.data.repository.query.Param; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Param).

import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).
import java.util.List; // danh sách phần tử cùng kiểu.
import java.util.Optional; // biểu diễn kết quả có thể không tồn tại.

public interface OrderRepository extends JpaRepository<Order, Long> {
    // Spring Data cũng cung cấp save/delete/findById; findBy... được sinh query từ tên thuộc tính.
    Optional<Order> findByIdAndUserId(Long id, Long userId);
    Page<Order> findByUserId(Long userId, Pageable pageable);
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);
    List<Order> findByStatusAndExpiresAtBefore(OrderStatus status, Instant time);

    @Query("select coalesce(sum(o.totalAmount), 0) as revenue, count(p.id) as orderCount " +
            "from Order o join o.payment p where p.status = :status and p.paidAt >= :from and p.paidAt < :to")
    PaidRevenueAggregate summarizePaidOrders(@Param("status") PaymentStatus status,
                                             @Param("from") Instant from,
                                             @Param("to") Instant to);

    @Query("select o.status as status, count(o.id) as orderCount from Order o " +
            "where o.createdAt >= :from and o.createdAt < :to group by o.status")
    List<OrderStatusAggregate> countOrdersByStatusCreatedBetween(@Param("from") Instant from,
                                                                 @Param("to") Instant to);

    @Query("select cast(p.paidAt as LocalDate) as paidDate, " +
            "coalesce(sum(o.totalAmount), 0) as revenue, count(p.id) as orderCount " +
            "from Order o join o.payment p where p.status = :status and p.paidAt >= :from and p.paidAt < :to " +
            "group by cast(p.paidAt as LocalDate) order by cast(p.paidAt as LocalDate)")
    List<DailyRevenueAggregate> summarizePaidOrdersByDay(@Param("status") PaymentStatus status,
                                                         @Param("from") Instant from,
                                                         @Param("to") Instant to);

    @Query(value = "select o from Order o join o.payment p join o.user u " +
            "where (:status is null or o.status = :status) " +
            "and (:from is null or o.createdAt >= :from) and (:to is null or o.createdAt < :to) " +
            "and (:paymentStatus is null or p.status = :paymentStatus) " +
            "and (:paymentMethod is null or o.paymentMethod = :paymentMethod) " +
            "and (:buyerEmail is null or lower(u.email) = lower(:buyerEmail))",
            countQuery = "select count(o.id) from Order o join o.payment p join o.user u " +
                    "where (:status is null or o.status = :status) " +
                    "and (:from is null or o.createdAt >= :from) and (:to is null or o.createdAt < :to) " +
                    "and (:paymentStatus is null or p.status = :paymentStatus) " +
                    "and (:paymentMethod is null or o.paymentMethod = :paymentMethod) " +
                    "and (:buyerEmail is null or lower(u.email) = lower(:buyerEmail))")
    Page<Order> findCmsOrders(@Param("status") OrderStatus status,
                              @Param("from") Instant from,
                              @Param("to") Instant to,
                              @Param("paymentStatus") PaymentStatus paymentStatus,
                              @Param("paymentMethod") PaymentMethod paymentMethod,
                              @Param("buyerEmail") String buyerEmail,
                              Pageable pageable);

    @Query("select cast(p.paidAt as LocalDate) as bucketDate, o.paymentMethod as paymentMethod, o.status as orderStatus, " +
            "sum(o.totalAmount) as paidRevenue, count(o.id) as paidOrderCount from Order o join o.payment p " +
            "where p.status = :paymentStatus and p.paidAt >= :from and p.paidAt < :to " +
            "group by cast(p.paidAt as LocalDate), o.paymentMethod, o.status order by cast(p.paidAt as LocalDate)")
    List<SalesReportAggregate> salesReport(@Param("paymentStatus") PaymentStatus paymentStatus,
                                          @Param("from") Instant from, @Param("to") Instant to);

    @Query(value = "select i.product_name as \"productName\", i.sku as sku, sum(i.quantity) as \"unitsSold\", " +
            "sum(i.unit_price * i.quantity) as revenue from order_items i join orders o on o.id=i.order_id " +
            "join payments p on p.order_id=o.id where p.status=:paymentStatus and p.paid_at>=:from and p.paid_at<:to " +
            "group by i.product_name,i.sku order by sum(i.quantity) desc,i.sku",
            countQuery = "select count(*) from (select i.product_name,i.sku from order_items i " +
                    "join orders o on o.id=i.order_id join payments p on p.order_id=o.id " +
                    "where p.status=:paymentStatus and p.paid_at>=:from and p.paid_at<:to " +
                    "group by i.product_name,i.sku) product_sales", nativeQuery = true)
    Page<ProductSalesAggregate> productSalesByUnits(@Param("paymentStatus") String paymentStatus,
                                                     @Param("from") Instant from, @Param("to") Instant to,
                                                     Pageable pageable);

    @Query(value = "select i.product_name as \"productName\", i.sku as sku, sum(i.quantity) as \"unitsSold\", " +
            "sum(i.unit_price * i.quantity) as revenue from order_items i join orders o on o.id=i.order_id " +
            "join payments p on p.order_id=o.id where p.status=:paymentStatus and p.paid_at>=:from and p.paid_at<:to " +
            "group by i.product_name,i.sku order by sum(i.unit_price * i.quantity) desc,i.sku",
            countQuery = "select count(*) from (select i.product_name,i.sku from order_items i " +
                    "join orders o on o.id=i.order_id join payments p on p.order_id=o.id " +
                    "where p.status=:paymentStatus and p.paid_at>=:from and p.paid_at<:to " +
                    "group by i.product_name,i.sku) product_sales", nativeQuery = true)
    Page<ProductSalesAggregate> productSalesByRevenue(@Param("paymentStatus") String paymentStatus,
                                                       @Param("from") Instant from, @Param("to") Instant to,
                                                       Pageable pageable);
}
