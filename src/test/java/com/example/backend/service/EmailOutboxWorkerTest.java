package com.example.backend.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

class EmailOutboxWorkerTest {

  @Test
  void successfulDeliveryMarksOutboxRowSent() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JavaMailSender sender = mock(JavaMailSender.class);
    when(sender.createMimeMessage())
        .thenReturn(new MimeMessage(Session.getInstance(new Properties())));
    worker(jdbc, sender)
        .deliver(
            new EmailOutboxWorker.MailRow(
                12L, "order:12:ORDER_PLACED", "ORDER_PLACED", "buyer@example.com", 0));
    verify(sender).send(any(MimeMessage.class));
    verify(jdbc).update(contains("status='SENT'"), eq(12L));
  }

  @Test
  void transientFailureSchedulesRetryAndIncrementsAttempts() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JavaMailSender sender = mock(JavaMailSender.class);
    when(sender.createMimeMessage())
        .thenReturn(new MimeMessage(Session.getInstance(new Properties())));
    doThrow(new MailSendException("temporary failure")).when(sender).send(any(MimeMessage.class));
    worker(jdbc, sender)
        .deliver(
            new EmailOutboxWorker.MailRow(
                12L, "order:12:ORDER_PLACED", "ORDER_PLACED", "buyer@example.com", 0));
    verify(jdbc)
        .update(contains("status=?"), eq("RETRY"), any(), contains("MailSendException"), eq(12L));
  }

  private static EmailOutboxWorker worker(JdbcTemplate jdbc, JavaMailSender sender) {
    return new EmailOutboxWorker(jdbc, null, sender, "no-reply@example.com", 5);
  }
}
