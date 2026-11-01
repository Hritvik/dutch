package com.dutch.ledger.series;

import com.dutch.ledger.expense.CorrectionController;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SeriesController {

    private final SeriesService series;

    public SeriesController(SeriesService series) {
        this.series = series;
    }

    @PostMapping("/series")
    ResponseEntity<Map<String, Object>> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreateSeriesRequest request) {
        return ResponseEntity.ok(Map.of("data",
                series.create(jwt.getSubject(), idempotencyKey, request)));
    }

    @GetMapping("/series")
    ResponseEntity<Map<String, Object>> listDirect(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(Map.of("data", series.forDirectMember(jwt.getSubject())));
    }

    @GetMapping("/series/{seriesId}")
    ResponseEntity<Map<String, Object>> read(@AuthenticationPrincipal Jwt jwt,
                                             @PathVariable String seriesId) {
        return ResponseEntity.ok(Map.of("data", series.read(jwt.getSubject(), seriesId)));
    }

    @GetMapping("/groups/{groupId}/series")
    ResponseEntity<Map<String, Object>> listForGroup(@AuthenticationPrincipal Jwt jwt,
                                                     @PathVariable String groupId) {
        return ResponseEntity.ok(Map.of("data", series.forGroup(jwt.getSubject(), groupId)));
    }

    @GetMapping("/series/{seriesId}/occurrences")
    ResponseEntity<Map<String, Object>> occurrences(@AuthenticationPrincipal Jwt jwt,
                                                    @PathVariable String seriesId) {
        return ResponseEntity.ok(Map.of("data", series.occurrences(jwt.getSubject(), seriesId)));
    }

    @PostMapping("/series/{seriesId}/occurrences")
    ResponseEntity<Map<String, Object>> generate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String seriesId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("If-Match") String ifMatch) {
        return ResponseEntity.ok(Map.of("data", series.generate(jwt.getSubject(), seriesId,
                idempotencyKey, CorrectionController.parseIfMatch(ifMatch))));
    }

    @PostMapping("/series/occurrences/{occurrenceId}/skip")
    ResponseEntity<Map<String, Object>> skip(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String occurrenceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ResponseEntity.ok(Map.of("data",
                series.skip(jwt.getSubject(), occurrenceId, idempotencyKey)));
    }

    @PostMapping("/series/occurrences/{occurrenceId}/submit")
    ResponseEntity<Map<String, Object>> submit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String occurrenceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ResponseEntity.ok(Map.of("data",
                series.submit(jwt.getSubject(), occurrenceId, idempotencyKey)));
    }

    @PostMapping("/series/{seriesId}/status")
    ResponseEntity<Map<String, Object>> status(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String seriesId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody StatusRequest request) {
        return ResponseEntity.ok(Map.of("data", series.changeStatus(jwt.getSubject(), seriesId,
                idempotencyKey, request == null ? null : request.action(),
                CorrectionController.parseIfMatch(ifMatch))));
    }

    public record StatusRequest(String action) {
    }
}
