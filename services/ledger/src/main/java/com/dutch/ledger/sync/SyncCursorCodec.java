package com.dutch.ledger.sync;

import com.dutch.ledger.group.MemberId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signs opaque incremental and snapshot tokens. A token binds the recipient and
 * the last delivered sequence; it carries no financial or contact data.
 */
@Component
public class SyncCursorCodec {

    private static final String CURSOR = "v1";
    private static final String SNAPSHOT = "s1";

    private final byte[] secret;
    private final java.time.Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public SyncCursorCodec(
            @Value("${dutch.sync.cursor-secret:dev-sync-cursor-secret-change-me}") String secret) {
        this(secret, java.time.Clock.systemUTC());
    }

    SyncCursorCodec(String secret, java.time.Clock clock) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public String sign(MemberId recipient, long sequence) {
        return sign(CURSOR, recipient, sequence, Instant.now(clock).plusSeconds(30L * 24 * 3600));
    }

    public long verify(String token, MemberId recipient) {
        Token parsed = verify(token, CURSOR, recipient);
        return parsed.sequence();
    }

    public String signSnapshot(MemberId recipient, long asOfSequence) {
        return sign(SNAPSHOT, recipient, asOfSequence, Instant.now(clock).plusSeconds(15 * 60));
    }

    public long verifySnapshot(String token, MemberId recipient) {
        return verify(token, SNAPSHOT, recipient).sequence();
    }

    private String sign(String kind, MemberId recipient, long sequence, Instant expiry) {
        String payload = kind + "." + recipient.value() + "." + sequence + "."
                + Instant.now(clock).getEpochSecond() + "." + expiry.getEpochSecond();
        return payload + "." + encode(hmac(payload));
    }

    private Token verify(String token, String kind, MemberId recipient) {
        if (token == null || token.isBlank()) {
            throw new SyncCursorExpiredException();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 6 || !parts[0].equals(kind) || !parts[1].equals(recipient.value())) {
            throw new SyncCursorExpiredException();
        }
        String payload = String.join(".", parts[0], parts[1], parts[2], parts[3], parts[4]);
        byte[] expected = hmac(payload);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(parts[5]);
        } catch (IllegalArgumentException exception) {
            throw new SyncCursorExpiredException(exception);
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new SyncCursorExpiredException();
        }
        try {
            long sequence = Long.parseLong(parts[2]);
            long expiry = Long.parseLong(parts[4]);
            if (sequence < 0 || expiry < Instant.now(clock).getEpochSecond()) {
                throw new SyncCursorExpiredException();
            }
            return new Token(sequence);
        } catch (NumberFormatException exception) {
            throw new SyncCursorExpiredException(exception);
        }
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private record Token(long sequence) {
    }
}
