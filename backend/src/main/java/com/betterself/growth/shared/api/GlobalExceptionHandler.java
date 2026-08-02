package com.betterself.growth.shared.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiEnvelope<ApiError>> handleApiException(ApiException exception, HttpServletRequest request) {
        ApiError error = new ApiError(
            exception.status().value(),
            exception.code(),
            exception.getMessage(),
            exception.details()
        );
        return ResponseEntity.status(exception.status())
            .body(ApiEnvelope.of(error, requestId(request), clock));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiEnvelope<ApiError>> handleValidation(
        MethodArgumentNotValidException exception,
        HttpServletRequest request
    ) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ApiError error = new ApiError(400, "VALIDATION_FAILED", "请检查输入内容", Map.of("fields", fields));
        return ResponseEntity.badRequest().body(ApiEnvelope.of(error, requestId(request), clock));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiEnvelope<ApiError>> handleNotFound(
        NoResourceFoundException exception,
        HttpServletRequest request
    ) {
        ApiError error = ApiError.of(404, "RESOURCE_NOT_FOUND", "资源不存在");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiEnvelope.of(error, requestId(request), clock));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiEnvelope<ApiError>> handleUnreadable(
        HttpMessageNotReadableException exception,
        HttpServletRequest request
    ) {
        ApiError error = ApiError.of(400, "INVALID_REQUEST_BODY", "请求内容格式不正确");
        return ResponseEntity.badRequest().body(ApiEnvelope.of(error, requestId(request), clock));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiEnvelope<ApiError>> handleMethodNotSupported(
        HttpRequestMethodNotSupportedException exception,
        HttpServletRequest request
    ) {
        ApiError error = new ApiError(
            405,
            "METHOD_NOT_ALLOWED",
            "请求方法不支持",
            Map.of("method", request.getMethod(), "path", request.getRequestURI())
        );
        HttpHeaders headers = new HttpHeaders();
        if (exception.getSupportedHttpMethods() != null) {
            headers.setAllow(exception.getSupportedHttpMethods());
        }
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .headers(headers)
            .body(ApiEnvelope.of(error, requestId(request), clock));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiEnvelope<ApiError>> handleUnexpected(Exception exception, HttpServletRequest request) {
        String requestId = requestId(request);
        LOGGER.error(
            "Unhandled request failure requestId={} method={} path={} exceptionType={}",
            requestId,
            request.getMethod(),
            request.getRequestURI(),
            exception.getClass().getName(),
            exception
        );
        ApiError error = ApiError.of(500, "INTERNAL_ERROR", "服务暂时不可用，请稍后重试");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiEnvelope.of(error, requestId, clock));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute("requestId");
        return value == null ? "unknown" : value.toString();
    }
}
