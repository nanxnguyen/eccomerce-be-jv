package com.example.backend.config;

import io.swagger.v3.oas.models.Components; // thư viện/kiểu Components được dùng trong file này.
import io.swagger.v3.oas.models.OpenAPI; // thư viện/kiểu OpenAPI được dùng trong file này.
import io.swagger.v3.oas.models.info.Info; // thư viện/kiểu Info được dùng trong file này.
import io.swagger.v3.oas.models.security.SecurityRequirement; // thư viện/kiểu SecurityRequirement được dùng trong file này.
import io.swagger.v3.oas.models.security.SecurityScheme; // thư viện/kiểu SecurityScheme được dùng trong file này.
import org.springframework.context.annotation.Bean; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Bean).
import org.springframework.context.annotation.Configuration; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Configuration).

@Configuration
public class OpenApiConfig {

    // springdoc tự sinh tài liệu OpenAPI từ các @RestController có sẵn.
    // Bean này chỉ thêm metadata (tên/mô tả API) và khai báo scheme JWT Bearer
    // để nút "Authorize" trên Swagger UI có thể gắn header Authorization: Bearer <token>
    // vào các request thử nghiệm ngay trên giao diện, không cần Postman/curl.
    @Bean
    public OpenAPI backendOpenApi() {
        String bearerScheme = "bearerAuth";

        return new OpenAPI()
                .info(new Info()
                        .title("Ecommerce Backend API")
                        .version("v1")
                        .description("Auth/User + Product/Catalog API"))
                .components(new Components()
                        .addSecuritySchemes(bearerScheme, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(bearerScheme));
    }
}
