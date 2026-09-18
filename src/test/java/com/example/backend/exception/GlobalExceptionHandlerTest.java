package com.example.backend.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsResourceNotFoundTo404() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/products/missing");

        ResponseEntity<ErrorResponse> response =
                handler.handleNotFound(new ResourceNotFoundException("Product not found"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().message()).isEqualTo("Product not found");
        assertThat(response.getBody().path()).isEqualTo("/api/products/missing");
    }

    @Test
    void mapsDuplicateResourceTo409() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/categories");

        ResponseEntity<ErrorResponse> response =
                handler.handleDuplicate(new DuplicateResourceException("Slug exists"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void mapsBadCredentialsTo401() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/auth/login");

        ResponseEntity<ErrorResponse> response =
                handler.handleBadCredentials(new BadCredentialsException("Invalid email or password"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void mapsAccessDeniedTo403() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/admin/users");

        ResponseEntity<ErrorResponse> response =
                handler.handleAccessDenied(new AccessDeniedException("denied"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().message()).isEqualTo("Access denied");
    }

    @Test
    void mapsGenericExceptionTo500() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/products");

        ResponseEntity<ErrorResponse> response =
                handler.handleGeneric(new RuntimeException("boom"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().message()).isEqualTo("Unexpected error occurred");
    }

    @Test
    void mapsValidationErrorsTo400WithJoinedFieldMessages() throws NoSuchMethodException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/categories");

        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "email", "must not be blank"));
        bindingResult.addError(new FieldError("request", "name", "size must be between 1 and 100"));

        MethodParameter methodParameter = mock(MethodParameter.class);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(methodParameter, bindingResult);

        ResponseEntity<ErrorResponse> response =
                handler.handleValidation(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message())
                .contains("email: must not be blank")
                .contains("name: size must be between 1 and 100");
    }

    @Test
    void mapsHttpMessageNotReadableTo400() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/categories");

        ResponseEntity<ErrorResponse> response =
                handler.handleNotReadable(new HttpMessageNotReadableException("malformed json", (org.springframework.http.HttpInputMessage) null), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void mapsMethodArgumentTypeMismatchTo400() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/products/t-shirt/variants");

        MethodParameter methodParameter = mock(MethodParameter.class);
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "t-shirt", Long.class, "id", methodParameter, new NumberFormatException("For input string: \"t-shirt\""));

        ResponseEntity<ErrorResponse> response = handler.handleTypeMismatch(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("id").contains("Long");
    }

    @Test
    void mapsDataIntegrityViolationTo409() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/categories/1");

        ResponseEntity<ErrorResponse> response =
                handler.handleDataIntegrityViolation(new DataIntegrityViolationException("duplicate key"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void mapsNoResourceFoundTo404() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/does-not-exist");

        NoResourceFoundException ex = new NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "/api/does-not-exist", "/api/does-not-exist");

        ResponseEntity<ErrorResponse> response = handler.handleNoResourceFound(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void mapsInsufficientStockTo409() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/orders/checkout");

        ResponseEntity<ErrorResponse> response =
                handler.handleInsufficientStock(new InsufficientStockException("Insufficient stock for SKU TSHIRT-M"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().message()).isEqualTo("Insufficient stock for SKU TSHIRT-M");
    }

    @Test
    void mapsInvalidOrderStateTo409() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/orders/1/cancel");

        ResponseEntity<ErrorResponse> response =
                handler.handleInvalidOrderState(new InvalidOrderStateException("Order cannot be cancelled in status SHIPPED"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
