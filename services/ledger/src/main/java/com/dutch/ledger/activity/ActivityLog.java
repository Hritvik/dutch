package com.dutch.ledger.activity;

import com.dutch.ledger.group.GroupRuleException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Append-only, duplicate-id-safe Activity evidence. */
public final class ActivityLog {

    private final List<ActivityEntry> entries = new CopyOnWriteArrayList<>();
    private final Set<String> ids = ConcurrentHashMap.newKeySet();

    public synchronized void append(ActivityEntry entry) {
        if (!ids.add(entry.activityId())) {
            throw new GroupRuleException("The activity id has already been recorded.");
        }
        entries.add(entry);
    }

    public List<ActivityEntry> entries() {
        return List.copyOf(entries);
    }
}
