package com.example.backend.repository;

import com.example.backend.entity.Notification; // Notification (entity ánh xạ dữ liệu với bảng database).
import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).
import java.util.Collection; // tiện ích collection chuẩn Java (Collection).
import org.springframework.data.domain.Page; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Page).
import org.springframework.data.domain.Pageable; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Pageable).
import org.springframework.data.jpa.repository.JpaRepository; // kiểu Spring Data hỗ trợ truy cập/phân trang database (JpaRepository).
import org.springframework.data.jpa.repository.Modifying; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Modifying).
import org.springframework.data.jpa.repository.Query; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Query).
import org.springframework.data.repository.query.Param; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Param).

public interface NotificationRepository extends JpaRepository<Notification, Long> {

  Page<Notification> findByRecipientIdAndTypeNotIn(
      Long recipientId, Collection<String> excluded, Pageable pageable);

  Page<Notification> findByRecipientIdAndTypeNotInAndReadAtIsNull(
      Long recipientId, Collection<String> excluded, Pageable pageable);

  Page<Notification> findByRecipientIdAndTypeNotInAndReadAtIsNotNull(
      Long recipientId, Collection<String> excluded, Pageable pageable);

  Page<Notification> findByRecipientIdAndTypeIn(
      Long recipientId, Collection<String> types, Pageable pageable);

  Page<Notification> findByRecipientIdAndTypeInAndReadAtIsNull(
      Long recipientId, Collection<String> types, Pageable pageable);

  Page<Notification> findByRecipientIdAndTypeInAndReadAtIsNotNull(
      Long recipientId, Collection<String> types, Pageable pageable);

  long countByRecipientIdAndTypeNotInAndReadAtIsNull(Long recipientId, Collection<String> excluded);

  long countByRecipientIdAndTypeInAndReadAtIsNull(Long recipientId, Collection<String> types);

  boolean existsByRecipientIdAndEventKey(Long recipientId, String eventKey);

  java.util.Optional<Notification> findByIdAndRecipientId(Long id, Long recipientId);

  java.util.Optional<Notification> findByIdAndRecipientIdAndTypeIn(
      Long id, Long recipientId, Collection<String> types);

  @Modifying
  @Query(
      "update Notification n set n.readAt = :now "
          + "where n.recipient.id = :recipientId and n.type not in :excluded and n.readAt is null")
  int markAllCustomerRead(
      @Param("recipientId") Long recipientId,
      @Param("excluded") Collection<String> excluded,
      @Param("now") Instant now);

  @Modifying
  @Query(
      "update Notification n set n.readAt = :now where n.recipient.id = :recipientId and n.type in"
          + " ('CMS_NEW_ORDER', 'LOW_STOCK') and n.readAt is null")
  int markAllCmsRead(@Param("recipientId") Long recipientId, @Param("now") Instant now);
}
