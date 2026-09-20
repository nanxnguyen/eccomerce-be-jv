package com.example.backend.entity;

import jakarta.persistence.*; // annotation/API JPA để ánh xạ entity với database (*).
import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).
import lombok.Getter; // Lombok tự sinh mã Java lặp lại lúc biên dịch (Getter).
import lombok.NoArgsConstructor; // Lombok tự sinh mã Java lặp lại lúc biên dịch (NoArgsConstructor).
import lombok.Setter; // Lombok tự sinh mã Java lặp lại lúc biên dịch (Setter).
import org.hibernate.annotations.UpdateTimestamp; // tính năng Hibernate/JPA cho database (UpdateTimestamp).

@Entity
@Table(name = "low_stock_alert_states")
@IdClass(LowStockAlertStateId.class)
@Getter
@Setter
@NoArgsConstructor
public class LowStockAlertState {
  @Id
  @Column(name = "variant_id")
  private Long variantId;

  @Id private Integer threshold;

  @Column(nullable = false)
  private boolean active;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
