package com.dutch.ledger.sync;

import com.dutch.ledger.group.GroupAccessDeniedException;
import com.dutch.ledger.group.MemberId;
import com.dutch.ledger.group.MemberResolver;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SyncService {

    private static final int MAX_LIMIT = 200;

    private final SyncJournal journal;
    private final SyncCursorCodec codec;
    private final MemberResolver members;

    public SyncService(SyncJournal journal, SyncCursorCodec codec, MemberResolver members) {
        this.journal = journal;
        this.codec = codec;
        this.members = members;
    }

    @Transactional(readOnly = true)
    public SyncViews.Page sync(String appSubject, String cursor, int limit) {
        MemberId actor = members.resolve(appSubject).orElseThrow(GroupAccessDeniedException::new);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        long sequence = codec.verify(cursor, actor);
        List<SyncChange> changes = journal.after(actor, sequence, limit);
        long next = changes.isEmpty() ? sequence : changes.get(changes.size() - 1).sequence();
        return new SyncViews.Page(changes.stream().map(SyncViews.Change::from).toList(),
                codec.sign(actor, next));
    }

    @Transactional(readOnly = true)
    public SyncViews.Snapshot snapshot(String appSubject) {
        MemberId actor = members.resolve(appSubject).orElseThrow(GroupAccessDeniedException::new);
        long asOf = journal.maxSequence(actor);
        return new SyncViews.Snapshot(codec.signSnapshot(actor, asOf), asOf);
    }

    @Transactional(readOnly = true)
    public SyncViews.SnapshotPage readSnapshot(String appSubject, String token, String cursor,
                                               int limit) {
        MemberId actor = members.resolve(appSubject).orElseThrow(GroupAccessDeniedException::new);
        long asOf = codec.verifySnapshot(token, actor);
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        long after = 0;
        if (cursor != null && !cursor.isBlank()) {
            try {
                after = Long.parseLong(cursor);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("cursor is invalid");
            }
            if (after < 0 || after > asOf) {
                throw new IllegalArgumentException("cursor is invalid");
            }
        }
        List<SyncChange> changes = journal.snapshot(actor, asOf, after, limit);
        boolean truncated = changes.size() == limit;
        Long next = truncated ? changes.get(changes.size() - 1).sequence() : null;
        return new SyncViews.SnapshotPage(changes.stream().map(SyncViews.Change::from).toList(),
                codec.sign(actor, asOf), asOf, truncated, next);
    }
}
