package com.example.backend.service;

import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnExpression("'${spring.mail.host:}' != ''")
public class EmailOutboxWorker {
  private static final int BATCH = 20;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final JavaMailSender mailSender;
  private final String from;
  private final int maxAttempts;

  public EmailOutboxWorker(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      JavaMailSender mailSender,
      @Value("${app.mail.from}") String from,
      @Value("${app.mail.max-attempts:5}") int maxAttempts) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.mailSender = mailSender;
    this.from = from;
    this.maxAttempts = maxAttempts;
  }


  @Scheduled(fixedDelayString = "${app.mail.poll-ms:5000}")
  public void processBatch() {
    List<MailRow> batch =
        transactions.execute(
            status -> {
              List<MailRow> rows =
                  jdbc.query(
                      "select id,event_key,event_type,recipient_email,attempts from"
                          + " notification_outbox where (status in ('PENDING','RETRY') and"
                          + " next_attempt_at<=now()) or (status='SENDING' and lease_until<now())"
                          + " order by id for update skip locked limit ?",
                      (rs, n) ->
                          new MailRow(
                              rs.getLong(1),
                              rs.getString(2),
                              rs.getString(3),
                              rs.getString(4),
                              rs.getInt(5)),
                      BATCH); // Chỉ lấy một lô nhỏ để xử lý mỗi lượt.
              for (MailRow row : rows)
                jdbc.update(
                    "update notification_outbox set"
                        + " status='SENDING',attempts=attempts+1,lease_until=?,updated_at=now()"
                        + " where id=?",
                    Instant.now().plusSeconds(120),
                    row.id()); // Đánh dấu email đang được worker giữ xử lý.
              return rows;
            });
    if (batch == null) return;
    for (MailRow row : batch) deliver(row);
  }

  void deliver(MailRow row) {
    try {
      MimeMessage message = mailSender.createMimeMessage();
      MimeMessageHelper helper =
          new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
      helper.setFrom(from);
      helper.setTo(row.email());
      helper.setSubject(subject(row.type()));
      String orderId = row.key().split(":")[1];
      String text = "[template v1] " + subject(row.type()) + " for order #" + orderId + ".";
      String html =
          "<p>" + subject(row.type()) + " for order <strong>#" + orderId + "</strong>.</p>";
      helper.setText(text, html);
      mailSender.send(message); // Gửi email qua SMTP.
      jdbc.update(
          "update notification_outbox set"
              + " status='SENT',sent_at=now(),lease_until=null,last_error=null,updated_at=now()"
              + " where id=?",
          row.id());
    } catch (Exception failure) {
      int attempt = row.attempts() + 1;
      boolean failed = attempt >= maxAttempts; // Chuyển sang lỗi cuối khi hết số lần thử.
      long delay = Math.min(3600L, 30L << Math.min(attempt - 1, 7));
      String detail = failure.getClass().getSimpleName();
      jdbc.update(
          "update notification_outbox set"
              + " status=?,next_attempt_at=?,lease_until=null,last_error=?,updated_at=now() where"
              + " id=?",
          failed ? "FAILED" : "RETRY",
          Instant.now().plusSeconds(delay),
          detail,
          row.id());
    }
  }

    private static String subject(String type) {
    return switch (type) {
      case "ORDER_PLACED" -> "Order received";
      case "PAYMENT_CONFIRMED" -> "Payment confirmed";
      case "PAYMENT_FAILED" -> "Payment failed";
      case "ORDER_SHIPPED" -> "Order shipped";
      case "ORDER_DELIVERED" -> "Order delivered";
      default -> "Order update";
    };
  }

  record MailRow(long id, String key, String type, String email, int attempts) {}
}
