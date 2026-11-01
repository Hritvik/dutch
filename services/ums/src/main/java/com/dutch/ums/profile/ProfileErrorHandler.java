package com.dutch.ums.profile;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ProfileStateController.class)
final class ProfileErrorHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> invalidRequest(
            IllegalArgumentException ignored, HttpServletRequest request) {
        String correlationId = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        return ResponseEntity.badRequest().body(Map.of("error", Map.of(
                "code", "invalid_request",
                "message", "Request is invalid",
                "correlationId", correlationId)));
    }
}
