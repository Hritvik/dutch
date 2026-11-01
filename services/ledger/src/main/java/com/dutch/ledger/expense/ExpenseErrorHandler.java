package com.dutch.ledger.expense;

import com.dutch.ledger.group.GroupAccessDeniedException;
import com.dutch.ledger.group.GroupForbiddenException;
import com.dutch.ledger.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ExpenseController.class)
final class ExpenseErrorHandler {

    @ExceptionHandler(GroupAccessDeniedException.class)
    ResponseEntity<Map<String, Object>> notFound(HttpServletRequest request) {
        return error(request, HttpStatus.NOT_FOUND, "not_found", "Resource was not found");
    }

    @ExceptionHandler(GroupForbiddenException.class)
    ResponseEntity<Map<String, Object>> forbidden(HttpServletRequest request) {
        return error(request, HttpStatus.FORBIDDEN, "forbidden", "Access is denied");
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<Map<String, Object>> idempotencyConflict(HttpServletRequest request) {
        return error(request, HttpStatus.CONFLICT, "idempotency_conflict",
                "The idempotency key was reused with a different request.");
    }

    @ExceptionHandler({IllegalArgumentException.class, NullPointerException.class,
            HttpMessageNotReadableException.class, MissingRequestHeaderException.class})
    ResponseEntity<Map<String, Object>> invalid(HttpServletRequest request) {
        return error(request, HttpStatus.BAD_REQUEST, "invalid_request", "Request is invalid");
    }

    private static ResponseEntity<Map<String, Object>> error(HttpServletRequest request, HttpStatus status,
                                                             String code, String message) {
        String correlationId = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        if (correlationId == null) {
            correlationId = CorrelationIdFilter.resolve(request.getHeader(CorrelationIdFilter.HEADER));
        }
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("correlationId", correlationId);
        return ResponseEntity.status(status).body(Map.of("error", error));
    }
}
