package com.example.backend.controller.cms;

import com.example.backend.dto.CmsInventoryItem; // CmsInventoryItem (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.CmsProductSalesReportRow; // CmsProductSalesReportRow (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.CmsSalesReportRow; // CmsSalesReportRow (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.exception.InvalidRequestException; // InvalidRequestException (loại lỗi nghiệp vụ hoặc dữ liệu).
import com.example.backend.service.CmsReportService; // CmsReportService (service xử lý nghiệp vụ).
import java.io.ByteArrayOutputStream; // thiết bị đọc/ghi dữ liệu chuẩn Java (ByteArrayOutputStream).
import java.io.IOException; // thiết bị đọc/ghi dữ liệu chuẩn Java (IOException).
import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).
import java.util.function.BiConsumer; // tiện ích collection chuẩn Java (BiConsumer).
import java.util.function.Function; // tiện ích collection chuẩn Java (Function).
import org.apache.poi.ss.usermodel.Row; // thư viện Apache POI đọc/ghi file Excel (Row).
import org.apache.poi.ss.usermodel.Sheet; // thư viện Apache POI đọc/ghi file Excel (Sheet).
import org.apache.poi.xssf.streaming.SXSSFWorkbook; // thư viện Apache POI đọc/ghi file Excel (SXSSFWorkbook).
import org.springframework.data.domain.Page; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Page).
import org.springframework.data.domain.PageRequest; // kiểu Spring Data hỗ trợ truy cập/phân trang database (PageRequest).
import org.springframework.data.domain.Pageable; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Pageable).
import org.springframework.http.HttpHeaders; // kiểu HTTP như status, header hoặc response body (HttpHeaders).
import org.springframework.http.MediaType; // kiểu HTTP như status, header hoặc response body (MediaType).
import org.springframework.http.ResponseEntity; // kiểu HTTP như status, header hoặc response body (ResponseEntity).
import org.springframework.security.access.prepost.PreAuthorize; // thành phần Spring Security cho xác thực/phân quyền (PreAuthorize).
import org.springframework.web.bind.annotation.*; // annotation Spring MVC để khai báo route/đọc request (*).
import org.thymeleaf.context.Context;

@RestController
@RequestMapping("/api/cms/reports")
@PreAuthorize("hasRole('ADMIN')")
public class CmsReportController {
  private static final int MAX_EXPORT_ROWS = 50_000, EXPORT_PAGE_SIZE = 100;
  private static final MediaType XLSX =
      MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
  private static final int PDF_MAX_ROWS = 10_000;
  private final CmsReportService service;
  private final com.example.backend.service.PdfDocumentService pdfDocuments;

  public CmsReportController(CmsReportService service,
      com.example.backend.service.PdfDocumentService pdfDocuments) {
    this.service = service;
    this.pdfDocuments = pdfDocuments;
  }

  @GetMapping("/sales")
  public Page<CmsSalesReportRow> sales(
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "day") String granularity,
      Pageable pageable) {
    return service.sales(from, to, granularity, pageable);
  }

  @GetMapping("/products")
  public Page<CmsProductSalesReportRow> products(
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "revenue") String sort,
      Pageable pageable) {
    return service.products(from, to, sort, pageable);
  }

  @GetMapping("/inventory")
  public Page<CmsInventoryItem> inventory(
      @RequestParam(required = false) Long categoryId,
      @RequestParam(required = false) Integer threshold,
      Pageable pageable) {
    return service.inventory(categoryId, threshold, pageable);
  }

  @GetMapping("/{type}/export.xlsx")
  public ResponseEntity<byte[]> export(
      @PathVariable String type,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "day") String granularity,
      @RequestParam(defaultValue = "revenue") String sort,
      @RequestParam(required = false) Long categoryId,
      @RequestParam(required = false) Integer threshold)
      throws IOException {
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      workbook.setCompressTempFiles(true);
      Sheet sheet = workbook.createSheet(type);
      switch (type) {
        case "sales" -> {
          header(
              sheet,
              "Bucket start",
              "Payment method",
              "Order status",
              "Paid revenue",
              "Paid orders");
          writeRows(
              sheet,
              pageable -> service.sales(from, to, granularity, pageable),
              (row, r) -> {
                text(row, 0, r.bucketStart().toString());
                text(row, 1, r.paymentMethod().name());
                text(row, 2, r.orderStatus().name());
                row.createCell(3).setCellValue(r.paidRevenue().doubleValue());
                row.createCell(4).setCellValue(r.paidOrderCount());
              });
        }
        case "products" -> {
          header(sheet, "Product name snapshot", "SKU snapshot", "Units sold", "Gross sales");
          writeRows(
              sheet,
              pageable -> service.products(from, to, sort, pageable),
              (row, r) -> {
                text(row, 0, r.productName());
                text(row, 1, r.sku());
                row.createCell(2).setCellValue(r.unitsSold());
                row.createCell(3).setCellValue(r.revenue().doubleValue());
              });
        }
        case "inventory" -> {
          header(sheet, "Product", "SKU", "Stock", "Reserved", "Available", "Threshold");
          writeRows(
              sheet,
              pageable -> service.inventory(categoryId, threshold, pageable),
              (row, r) -> {
                text(row, 0, r.productName());
                text(row, 1, r.sku());
                row.createCell(2).setCellValue(r.stockQuantity());
                row.createCell(3).setCellValue(r.reservedQuantity());
                row.createCell(4).setCellValue(r.availableQuantity());
                row.createCell(5).setCellValue(r.threshold());
              });
        }
        default ->
            throw new InvalidRequestException("report type must be sales, products, or inventory");
      }
      for (int c = 0; c < sheet.getRow(0).getLastCellNum(); c++)
        sheet.setColumnWidth(c, Math.min(60 * 256, 24 * 256));
      workbook.write(output);
      String filename = type + "-" + Instant.now().toString().replace(":", "-") + ".xlsx";
      return ResponseEntity.ok()
          .contentType(XLSX)
          .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
          .body(output.toByteArray());
    }
  }

  @GetMapping(value = "/{type}/export.pdf", produces = "application/pdf")
  public ResponseEntity<byte[]> exportPdf(
      @PathVariable String type,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "day") String granularity,
      @RequestParam(defaultValue = "revenue") String sort,
      @RequestParam(required = false) Long categoryId,
      @RequestParam(required = false) Integer threshold) {
    Context context = new Context(java.util.Locale.forLanguageTag("vi"));
    context.setVariable("period", type.equals("inventory")
        ? "Ảnh chụp tồn kho tại thời điểm xuất báo cáo"
        : "Thời gian: " + (from == null ? "mặc định" : from) + " đến " + (to == null ? "hiện tại" : to));
    switch (type) {
      case "sales" -> {
        context.setVariable("title", "Báo cáo doanh thu");
        context.setVariable("headers", java.util.List.of("Thời điểm", "Thanh toán", "Trạng thái đơn", "Doanh thu", "Số đơn"));
        context.setVariable("rows", fetchRows(page -> service.sales(from, to, granularity, page), row ->
            java.util.List.of(row.bucketStart().toString(), row.paymentMethod().name(), row.orderStatus().name(),
                row.paidRevenue().toPlainString(), Long.toString(row.paidOrderCount()))));
      }
      case "products" -> {
        context.setVariable("title", "Báo cáo sản phẩm bán chạy");
        context.setVariable("headers", java.util.List.of("Sản phẩm", "SKU", "Số lượng bán", "Doanh thu"));
        context.setVariable("rows", fetchRows(page -> service.products(from, to, sort, page), row ->
            java.util.List.of(row.productName(), row.sku(), Long.toString(row.unitsSold()), row.revenue().toPlainString())));
      }
      case "inventory" -> {
        context.setVariable("title", "Báo cáo tồn kho");
        context.setVariable("headers", java.util.List.of("Sản phẩm", "SKU", "Tồn kho", "Đã giữ", "Khả dụng", "Ngưỡng"));
        context.setVariable("rows", fetchRows(page -> service.inventory(categoryId, threshold, page), row ->
            java.util.List.of(row.productName(), row.sku(), Integer.toString(row.stockQuantity()),
                Integer.toString(row.reservedQuantity()), Integer.toString(row.availableQuantity()),
                java.util.Objects.toString(row.threshold(), ""))));
      }
      default -> throw new InvalidRequestException("report type must be sales, products, or inventory");
    }
    byte[] pdf = pdfDocuments.render("pdf/report", context);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + type + "-report.pdf\"")
        .body(pdf);
  }

  private static <T> java.util.List<java.util.List<String>> fetchRows(
      Function<Pageable, Page<T>> fetch, Function<T, java.util.List<String>> convert) {
    var result = new java.util.ArrayList<java.util.List<String>>();
    for (int page = 0; page <= PDF_MAX_ROWS / EXPORT_PAGE_SIZE; page++) {
      Page<T> rows = fetch.apply(PageRequest.of(page, EXPORT_PAGE_SIZE));
      if ((long) page * EXPORT_PAGE_SIZE + rows.getNumberOfElements() > PDF_MAX_ROWS)
        throw new InvalidRequestException("PDF export exceeds 10000 rows");
      rows.getContent().stream().map(convert).forEach(result::add);
      if (!rows.hasNext()) return result;
    }
    return result;
  }

  private static <T> void writeRows(
      Sheet sheet, Function<Pageable, Page<T>> fetch, BiConsumer<Row, T> write) {
    for (int page = 0; page <= MAX_EXPORT_ROWS / EXPORT_PAGE_SIZE; page++) {
      Page<T> rows = fetch.apply(PageRequest.of(page, EXPORT_PAGE_SIZE));
      if ((long) page * EXPORT_PAGE_SIZE + rows.getNumberOfElements() > MAX_EXPORT_ROWS)
        throw new InvalidRequestException("Export exceeds 50000 rows");
      for (int i = 0; i < rows.getContent().size(); i++) {
        Row row = sheet.createRow(1 + page * EXPORT_PAGE_SIZE + i);
        write.accept(row, rows.getContent().get(i));
      }
      if (!rows.hasNext()) return;
    }
  }

  private static void header(Sheet s, String... names) {
    Row r = s.createRow(0);
    for (int i = 0; i < names.length; i++) text(r, i, names[i]);
  }

  private static void text(Row r, int column, String value) {
    r.createCell(column).setCellValue(value == null ? "" : value);
  }
}
