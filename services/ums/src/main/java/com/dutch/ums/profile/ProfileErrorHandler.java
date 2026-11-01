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
        return ResponseEntity.internalServerError().body(Map.of("error", Map.of(
                "code", "internal_error",
                "message", "Request could not be completed",
                "correlationId", correlationId)));
    }
}
