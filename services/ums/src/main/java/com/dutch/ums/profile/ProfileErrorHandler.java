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
        Object attribute = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        String correlationId = attribute == null ? "cor_unknown" : attribute.toString();
        return ResponseEntity.internalServerError().body(Map.of("error", Map.of(
                "code", "internal_error",
                "message", "Request could not be completed",
                "correlationId", correlationId)));
    }
}
