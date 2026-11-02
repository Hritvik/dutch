package com.dutch.ledger.sync;

import com.dutch.ledger.group.GroupAccessDeniedException;
import com.dutch.ledger.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = SyncController.class)
public class SyncErrorHandler {

    @ExceptionHandler(SyncCursorExpiredException.class)
    ResponseEntity<Map<String, Object>> expired(HttpServletRequest request) {
        return error(request, HttpStatus.CONFLICT, "sync_cursor_expired",
                "The sync cursor expired. Request a full snapshot.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> invalid(HttpServletRequest request) {
        return error(request, HttpStatus.BAD_REQUEST, "invalid_request", "Request is invalid");
    }

    @ExceptionHandler(GroupAccessDeniedException.class)
    ResponseEntity<Map<String, Object>> notFound(HttpServletRequest request) {
        return error(request, HttpStatus.NOT_FOUND, "not_found", "Resource was not found");
    }

    private ResponseEntity<Map<String, Object>> error(HttpServletRequest request, HttpStatus status,
                                                      String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        String correlationId = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        if (correlationId == null) {
            correlationId = CorrelationIdFilter.resolve(request.getHeader(CorrelationIdFilter.HEADER));
        }
        error.put("correlationId", correlationId);
        return ResponseEntity.status(status).body(Map.of("error", error));
    }
}
