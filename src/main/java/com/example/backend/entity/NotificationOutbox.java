package com.example.backend.entity;

import jakarta.persistence.*; // annotation/API JPA để ánh xạ entity với database (*).
import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).
import lombok.Getter; // Lombok tự sinh mã Java lặp lại lúc biên dịch (Getter).
import lombok.NoArgsConstructor; // Lombok tự sinh mã Java lặp lại lúc biên dịch (NoArgsConstructor).
import lombok.Setter; // Lombok tự sinh mã Java lặp lại lúc biên dịch (Setter).
import org.hibernate.annotations.CreationTimestamp; // tính năng Hibernate/JPA cho database (CreationTimestamp).
import org.hibernate.annotations.UpdateTimestamp; // tính năng Hibernate/JPA cho database (UpdateTimestamp).

@Entity
@Table(
    name = "notification_outbox",
    indexes =
        @Index(
            name = "ix_notification_outbox_due",
            columnList = "status,next_attempt_at,lease_until"))
@Getter
@Setter
@NoArgsConstructor
public class NotificationOutbox {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "event_key", nullable = false, unique = true, length = 200)
  private String eventKey;

  @Column(name = "event_type", nullable = false, length = 50)
  private String eventType;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "recipient_user_id")
  private User recipient;

  @Column(name = "recipient_email", nullable = false)
  private String recipientEmail;

  @Column(name = "template_data", nullable = false, columnDefinition = "text")
  private String templateData = "{}";

  @Column(nullable = false, length = 20)
  private String status = "PENDING";

  @Column(nullable = false)
  private int attempts;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt = Instant.now();

  @Column(name = "lease_until")
  private Instant leaseUntil;

  @Column(name = "sent_at")
  private Instant sentAt;

  @Column(name = "last_error", length = 500)
  private String lastError;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
