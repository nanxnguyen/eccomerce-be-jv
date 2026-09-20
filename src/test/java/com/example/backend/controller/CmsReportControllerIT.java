package com.example.backend.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.backend.entity.*;
import com.example.backend.repository.*;
import com.example.backend.security.JwtService;
import com.example.backend.support.TestDataCleaner;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.MethodName.class)
class CmsReportControllerIT {
  private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z"),
      TO = Instant.parse("2026-02-01T00:00:00Z");
  @Autowired MockMvc mvc;
  @Autowired UserRepository users;
  @Autowired CategoryRepository categories;
  @Autowired ProductRepository products;
  @Autowired ProductVariantRepository variants;
  @Autowired OrderRepository orders;
  @Autowired JwtService jwt;
  @Autowired TestDataCleaner cleaner;
  String admin;

  @BeforeEach
  void setup() {
    cleaner.cleanAll();
    User u =
        users.save(
            User.builder()
                .name("Admin")
                .email("report-admin@example.com")
                .passwordHash("x")
                .role(Role.ADMIN)
                .build());
    admin = jwt.generateToken(u.getId(), u.getRole().name());
  }

  @Test
  void aReportsUsePaidOrdersAndOrderItemSnapshots() throws Exception {
    Category c = categories.save(Category.builder().name("Tools").slug("tools").build());
    Product p =
        products.save(
            Product.builder()
                .category(c)
                .name("Current Name")
                .slug("current-name")
                .status(ProductStatus.ACTIVE)
                .build());
    ProductVariant v =
        variants.save(
            ProductVariant.builder()
                .product(p)
                .sku("CURRENT-SKU")
                .price(new BigDecimal("999.00"))
                .stockQuantity(12)
                .reservedQuantity(3)
                .build());
    saveOrder(
        v, "Snapshot Hammer", "OLD-SKU", "10.25", 2, PaymentStatus.SUCCESS, FROM.plusSeconds(1));
    saveOrder(v, "Ignored", "IGNORED", "50.00", 1, PaymentStatus.PENDING, FROM.plusSeconds(2));
    mvc.perform(
            get("/api/cms/reports/products")
                .header("Authorization", "Bearer " + admin)
                .param("from", FROM.toString())
                .param("to", TO.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].productName").value("Snapshot Hammer"))
        .andExpect(jsonPath("$.content[0].sku").value("OLD-SKU"))
        .andExpect(jsonPath("$.content[0].unitsSold").value(2))
        .andExpect(jsonPath("$.content[0].revenue").value(20.5));
    mvc.perform(
            get("/api/cms/reports/sales")
                .header("Authorization", "Bearer " + admin)
                .param("from", FROM.toString())
                .param("to", TO.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].paidRevenue").value(20.5))
        .andExpect(jsonPath("$.content[0].paidOrderCount").value(1));
    mvc.perform(
            get("/api/cms/reports/inventory")
                .header("Authorization", "Bearer " + admin)
                .param("categoryId", c.getId().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].availableQuantity").value(9));

    assertExportFirstCell(
        "sales",
        "2026-01-01",
        get("/api/cms/reports/sales/export.xlsx")
            .param("from", FROM.toString())
            .param("to", TO.toString()));
    assertExportFirstCell(
        "products",
        "Snapshot Hammer",
        get("/api/cms/reports/products/export.xlsx")
            .param("from", FROM.toString())
            .param("to", TO.toString()));
    assertExportFirstCell(
        "inventory",
        "Current Name",
        get("/api/cms/reports/inventory/export.xlsx").param("threshold", "20"));
  }

  @Test
  void bXlsxIsReadableAndBadRangeAndAnonymousAreRejected() throws Exception {
    var response =
        mvc.perform(
                get("/api/cms/reports/sales/export.xlsx")
                    .header("Authorization", "Bearer " + admin)
                    .param("from", FROM.toString())
                    .param("to", TO.toString()))
            .andExpect(status().isOk())
            .andExpect(
                header()
                    .string(
                        "Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(
                header()
                    .string(
                        "Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment; filename=\"sales-")))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(response))) {
      assertEquals("sales", workbook.getSheetAt(0).getSheetName());
      assertEquals(
          "Bucket start", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
    }
    mvc.perform(
            get("/api/cms/reports/sales").param("from", FROM.toString()).param("to", TO.toString()))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/cms/reports/sales/export.xlsx")
                .header("Authorization", "Bearer " + admin)
                .param("from", TO.toString())
                .param("to", FROM.toString()))
        .andExpect(status().isBadRequest());
  }

  private void saveOrder(
      ProductVariant v,
      String name,
      String sku,
      String price,
      int qty,
      PaymentStatus status,
      Instant paidAt) {
    User u = users.findByEmail("report-admin@example.com").orElseThrow();
    Order o =
        Order.builder()
            .user(u)
            .status(OrderStatus.CONFIRMED)
            .paymentMethod(PaymentMethod.COD)
            .recipientName("Test")
            .phone("000")
            .addressLine("Test")
            .totalAmount(new BigDecimal(price).multiply(BigDecimal.valueOf(qty)))
            .build();
    OrderItem item =
        OrderItem.builder()
            .order(o)
            .variant(v)
            .productName(name)
            .sku(sku)
            .unitPrice(new BigDecimal(price))
            .quantity(qty)
            .build();
    o.addItem(item);
    o.assignPayment(
        Payment.builder().gateway(PaymentMethod.COD).status(status).paidAt(paidAt).build());
    orders.saveAndFlush(o);
  }

  private void assertExportFirstCell(
      String type, String expected, MockHttpServletRequestBuilder request) throws Exception {
    byte[] content =
        mvc.perform(request.header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andExpect(
                header()
                    .string(
                        "Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
      var sheet = workbook.getSheetAt(0);
      assertEquals(type, sheet.getSheetName());
      assertEquals(expected, sheet.getRow(1).getCell(0).getStringCellValue());
    }
  }
}
