package com.dutch.ledger.activity;

import java.util.List;

public interface ActivityRepository {
    void append(ActivityEntry entry);

    List<ActivityEntry> forScope(String scopeId);
}
