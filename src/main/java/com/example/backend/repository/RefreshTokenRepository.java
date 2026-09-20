package com.example.backend.repository;

import com.example.backend.entity.RefreshToken; // RefreshToken (entity ánh xạ dữ liệu với bảng database).
import org.springframework.data.jpa.repository.JpaRepository; // kiểu Spring Data hỗ trợ truy cập/phân trang database (JpaRepository).
import org.springframework.data.jpa.repository.Modifying; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Modifying).
import org.springframework.data.jpa.repository.Query; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Query).
import org.springframework.data.repository.query.Param; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Param).

import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).
import java.util.Optional; // biểu diễn kết quả có thể không tồn tại.

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    long countByUser_IdAndRevokedAtIsNull(Long userId);

    // Dọn row đã hết hạn - không dọn thì bảng phình vô hạn theo số lần rotate (mỗi /refresh tạo 1
    // row mới với expiresAt MỚI 30 ngày kể từ lúc rotate, row cũ giữ lại để phát hiện reuse chứ
    // không xoá ngay - xem AuthService.refresh()). Bulk DELETE (không phải derived deleteBy...)
    // vì derived delete của Spring Data load từng row thành entity managed rồi xoá từng cái một -
    // đúng thứ bảng này KHÔNG được làm khi nó có thể có hàng nghìn row hết hạn cùng lúc.
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);

    // UPDATE có điều kiện thay vì đọc rồi ghi (select-then-update): Postgres tự khóa row trong lúc
    // UPDATE, request thứ 2 chạy đồng thời trên CÙNG token phải đợi request 1 commit rồi mới đọc lại
    // điều kiện WHERE - lúc đó revoked_at đã khác NULL nên không match, trả về 0 dòng. Nhờ vậy 2 lần
    // gọi /refresh đồng thời với cùng 1 token chỉ có đúng 1 lần thành công, không cần lock thủ công.
    // Cũng ghi lastUsedAt vì rotate = dùng token đó lần cuối trước khi nó bị thay thế.
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now, t.lastUsedAt = :now where t.id = :id and t.revokedAt is null")
    int revokeIfActive(@Param("id") Long id, @Param("now") Instant now);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.sessionId = :sessionId and t.revokedAt is null")
    int revokeSession(@Param("sessionId") String sessionId, @Param("now") Instant now);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.user.id = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") Long userId, @Param("now") Instant now);
}
