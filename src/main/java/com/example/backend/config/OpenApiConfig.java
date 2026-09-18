package com.example.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
