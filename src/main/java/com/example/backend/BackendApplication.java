package com.example.backend;

import org.springframework.boot.SpringApplication; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (SpringApplication).
import org.springframework.boot.autoconfigure.SpringBootApplication; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (SpringBootApplication).
import org.springframework.scheduling.annotation.EnableScheduling; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (EnableScheduling).

@SpringBootApplication
@EnableScheduling
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
