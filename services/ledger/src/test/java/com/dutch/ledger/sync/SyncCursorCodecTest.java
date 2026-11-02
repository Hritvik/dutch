package com.dutch.ledger.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dutch.ledger.group.MemberId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SyncCursorCodecTest {

    private static final MemberId A = new MemberId("mem_012345678901234567");
    private static final MemberId B = new MemberId("mem_112345678901234567");

    @Test
    void signs_and_verifies_the_recipient_sequence() {
        SyncCursorCodec codec = new SyncCursorCodec("secret");
        String cursor = codec.sign(A, 42);
        assertThat(codec.verify(cursor, A)).isEqualTo(42);
    }

    @Test
    void rejects_a_foreign_recipient_a_tampered_sequence_or_an_expired_token() {
        SyncCursorCodec codec = new SyncCursorCodec("secret");
        String cursor = codec.sign(A, 7);
        assertThatThrownBy(() -> codec.verify(cursor, B)).isInstanceOf(SyncCursorExpiredException.class);

        String tampered = cursor.replaceFirst("\\.7\\.", ".9.");
        assertThatThrownBy(() -> codec.verify(tampered, A))
                .isInstanceOf(SyncCursorExpiredException.class);

        Clock issued = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
        SyncCursorCodec expiring = new SyncCursorCodec("secret", issued);
        String old = expiring.sign(A, 1);
        Clock later = Clock.fixed(Instant.parse("2026-12-15T00:00:00Z"), ZoneOffset.UTC);
        assertThatThrownBy(() -> new SyncCursorCodec("secret", later).verify(old, A))
                .isInstanceOf(SyncCursorExpiredException.class);
    }

    @Test
    void the_key_id_and_feed_generation_are_bound_into_the_token() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
        SyncCursorCodec first = new SyncCursorCodec("secret", clock, "key-1", "gen-1");
        String cursor = first.sign(A, 3);
        assertThat(cursor).doesNotContain(A.value());
        assertThat(first.verify(cursor, A)).isEqualTo(3);
        assertThatThrownBy(() -> new SyncCursorCodec("secret", clock, "key-2", "gen-1")
                .verify(cursor, A)).isInstanceOf(SyncCursorExpiredException.class);
        assertThatThrownBy(() -> new SyncCursorCodec("secret", clock, "key-1", "gen-2")
                .verify(cursor, A)).isInstanceOf(SyncCursorExpiredException.class);
    }

    @Test
    void snapshot_tokens_are_separate_from_incremental_cursors() {
        SyncCursorCodec codec = new SyncCursorCodec("secret");
        assertThat(codec.verifySnapshot(codec.signSnapshot(A, 9), A)).isEqualTo(9);
        assertThatThrownBy(() -> codec.verifySnapshot(codec.sign(A, 9), A))
                .isInstanceOf(SyncCursorExpiredException.class);
    }
}
