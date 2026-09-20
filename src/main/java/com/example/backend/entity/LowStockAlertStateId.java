package com.example.backend.entity;

import java.io.Serializable; // thiết bị đọc/ghi dữ liệu chuẩn Java (Serializable).
import java.util.Objects; // tiện ích kiểm tra/xử lý object.

public class LowStockAlertStateId implements Serializable {
  private Long variantId;
  private Integer threshold;

  public LowStockAlertStateId() {}

  public LowStockAlertStateId(Long variantId, Integer threshold) {
    this.variantId = variantId;
    this.threshold = threshold;
  }

  @Override
  public boolean equals(Object value) {
    return value instanceof LowStockAlertStateId other
        && Objects.equals(variantId, other.variantId)
        && Objects.equals(threshold, other.threshold);
  }

  @Override
  public int hashCode() {
    return Objects.hash(variantId, threshold);
  }
}
