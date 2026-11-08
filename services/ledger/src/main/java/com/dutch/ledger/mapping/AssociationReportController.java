package com.dutch.ledger.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated ingress for the UMS association report.
 *
 * <p>UMS owns the schema; Dutch owns applying it. The request body is the pinned
 * envelope from {@code contracts/imported/ums/}. Acknowledgement happens only after
 * the consumer returns, so a 202 means the fact is durably applied and a later
 * redelivery is a no-op.
 */
@RestController
@RequestMapping("/internal/ums")
public class AssociationReportController {

    private static final Logger log = LoggerFactory.getLogger(AssociationReportController.class);
    private static final String HEADER = "X-Service-Token";

    private final JdbcMemberAssociationReportConsumer consumer;
    private final String serviceToken;

    public AssociationReportController(JdbcMemberAssociationReportConsumer consumer,
                                       @Value("${dutch.ums.service-token:}") String serviceToken) {
        this.consumer = consumer;
        // Never fall back to a shared constant: blank means this instance accepts no
        // producer until an operator configures the same secret on both sides.
        if (serviceToken == null || serviceToken.isBlank()) {
            log.warn("DUTCH_UMS_SERVICE_TOKEN is unset; this instance accepts no report");
        }
        this.serviceToken = serviceToken == null || serviceToken.isBlank()
                ? UUID.randomUUID().toString() : serviceToken;
    }

    @PostMapping("/association-reports")
    ResponseEntity<Void> receive(@RequestHeader(value = HEADER, required = false) String token,
                                 @RequestBody(required = false) JsonNode envelope) {
        if (token == null || !MessageDigest.isEqual(
                serviceToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).build();
        }
        if (envelope == null || envelope.isNull()) {
            return ResponseEntity.badRequest().build();
        }

        MemberAssociationReport report;
        try {
            report = parse(envelope);
        } catch (IllegalArgumentException | DateTimeParseException invalid) {
            log.info("rejected malformed association report: {}", invalid.getMessage());
            return ResponseEntity.badRequest().build();
        }

        try {
            boolean applied = consumer.consume(report);
            return ResponseEntity.status(applied ? 202 : 200)
                    .cacheControl(CacheControl.noStore())
                    .build();
        } catch (MemberMappingConflictException reuse) {
            return ResponseEntity.status(409).build();
        } catch (RetiredGenerationException retired) {
            return ResponseEntity.status(422).build();
        } catch (ForeignAssociationReportException foreign) {
            return ResponseEntity.status(403).build();
        }
    }

    private static MemberAssociationReport parse(JsonNode envelope) {
        JsonNode payload = envelope.get("payload");
        JsonNode aggregate = envelope.get("aggregate");
        JsonNode destination = envelope.get("destination");
        if (payload == null || aggregate == null || destination == null) {
            throw new IllegalArgumentException("aggregate, destination, and payload are required");
        }
        if (!"ums.profile.association-report.v1".equals(text(envelope, "eventType"))) {
            throw new IllegalArgumentException("unexpected eventType");
        }
        if (envelope.path("schemaVersion").asInt(-1) != 1) {
            throw new IllegalArgumentException("unexpected schemaVersion");
        }
        Long generation = optionalLong(payload, "generation");
        Long retired = optionalLong(payload, "retiredGeneration");
        return new MemberAssociationReport(
                text(envelope, "eventId"),
                text(aggregate, "id"),
                text(payload, "appSubject"),
                text(destination, "appRegistrationId"),
                text(envelope, "environment"),
                aggregate.path("version").asLong(0),
                text(payload, "profileState"),
                text(payload, "lifecycleState"),
                optionalText(payload, "lifecycleReason"),
                generation,
                optionalText(payload, "profileRevision"),
                retired,
                payload.path("contentObligation").asBoolean(false),
                OffsetDateTime.parse(text(envelope, "occurredAt")).toInstant());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.asText();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long optionalLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.canConvertToExactIntegral()) {
            return null;
        }
        return value.asLong();
    }
}
