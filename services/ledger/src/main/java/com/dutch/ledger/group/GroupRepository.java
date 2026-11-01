package com.dutch.ledger.group;

import java.util.List;
import java.util.Optional;

public interface GroupRepository {
    Optional<Group> findById(GroupId id);

    /** Loads the aggregate while holding its row lock, for a command transaction. */
    Optional<Group> findByIdForUpdate(GroupId id);

    List<Group> findReadableBy(MemberId memberId);

    void saveNew(Group group);

    /**
     * Writes only if the stored aggregate version still equals {@code expectedVersion};
     * otherwise another writer won the race.
     */
    void save(Group group, long expectedVersion);
}
