package com.example.backend.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Service
public class PdfDocumentService {
  private final TemplateEngine templates;

  public PdfDocumentService(TemplateEngine templates) {
    this.templates = templates;
  }

    public byte[] render(String template, Context context) {
    String html = templates.process(template, context); // Điền dữ liệu vào mẫu HTML trước khi kết xuất PDF.
    try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      new PdfRendererBuilder() // Khởi tạo bộ chuyển HTML thành PDF.
          .useFont(() -> PdfDocumentService.class.getResourceAsStream("/fonts/NotoSans.ttf"), "Noto Sans")
          .withHtmlContent(html, null)
          .toStream(output)
          .run();
      return output.toByteArray(); // Trả nội dung PDF dưới dạng byte để controller gửi về.
    } catch (IOException e) {
      throw new IllegalStateException("Could not create PDF document", e);
    }
  }
}
