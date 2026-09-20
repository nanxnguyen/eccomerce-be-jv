package com.example.backend.entity;

import jakarta.persistence.*; // annotation/API JPA để ánh xạ entity với database (*).
import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).
import lombok.Getter; // Lombok tự sinh mã Java lặp lại lúc biên dịch (Getter).
import lombok.NoArgsConstructor; // Lombok tự sinh mã Java lặp lại lúc biên dịch (NoArgsConstructor).
import lombok.Setter; // Lombok tự sinh mã Java lặp lại lúc biên dịch (Setter).
import org.hibernate.annotations.CreationTimestamp; // tính năng Hibernate/JPA cho database (CreationTimestamp).

@Entity
@Table(
    name = "notifications",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_notifications_recipient_event",
            columnNames = {"recipient_user_id", "event_key"}),
    indexes =
        @Index(
            name = "ix_notifications_recipient_unread_created",
            columnList = "recipient_user_id,read_at,created_at"))
@Getter
@Setter
@NoArgsConstructor
public class Notification {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "recipient_user_id", nullable = false)
  private User recipient;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "order_id")
  private Order order;

  @Column(name = "event_key", nullable = false, length = 200)
  private String eventKey;

  @Column(nullable = false, length = 50)
  private String type;

  @Column(nullable = false, length = 160)
  private String title;

  @Column(nullable = false, length = 500)
  private String message;

  @Column(name = "read_at")
  private Instant readAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;
}
