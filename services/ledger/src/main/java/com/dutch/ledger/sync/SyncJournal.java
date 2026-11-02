package com.dutch.ledger.sync;

import com.dutch.ledger.group.MemberId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface SyncJournal {

    /** Appends the same compact resource change for each recipient in this transaction. */
    void append(Collection<MemberId> recipients, String resourceType, String resourceId,
                String operation, long resourceVersion, Map<String, Object> payload,
                String actionId, Instant occurredAt);

    List<SyncChange> after(MemberId recipient, long sequence, int limit);

    long maxSequence(MemberId recipient);

    /** Latest upsert per resource, excluding tombstones. */
    List<SyncChange> snapshot(MemberId recipient, long asOfSequence, int limit);
}
