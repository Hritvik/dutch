package com.dutch.ledger.group;

import java.util.List;
import java.util.Optional;

public interface GroupRepository {
    Optional<Group> findById(GroupId id);

    List<Group> findReadableBy(MemberId memberId);

    void save(Group group);
}
