package com.dutch.notification.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {NotificationController.class, InternalEventController.class})
public class NotificationErrorHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> invalid(HttpServletRequest request) {
        return error(request, HttpStatus.BAD_REQUEST, "invalid_request", "Request is invalid");
    }

    private ResponseEntity<Map<String, Object>> error(HttpServletRequest request, HttpStatus status,
                                                      String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("correlationId", String.valueOf(request.getHeader("X-Correlation-Id")));
        return ResponseEntity.status(status).body(Map.of("error", error));
    }
}
