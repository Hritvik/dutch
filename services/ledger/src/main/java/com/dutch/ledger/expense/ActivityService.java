package com.dutch.ledger.expense;

import com.dutch.ledger.activity.ActivityEntry;
import com.dutch.ledger.activity.ActivityRepository;
import com.dutch.ledger.group.Group;
import com.dutch.ledger.group.GroupAccessDeniedException;
import com.dutch.ledger.group.GroupId;
import com.dutch.ledger.group.GroupRepository;
import com.dutch.ledger.group.MemberId;
import com.dutch.ledger.group.MemberResolver;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivityService {

    private final GroupRepository groups;
    private final MemberResolver members;
    private final ActivityRepository activity;

    public ActivityService(GroupRepository groups, MemberResolver members, ActivityRepository activity) {
        this.groups = groups;
        this.members = members;
        this.activity = activity;
    }

    @Transactional(readOnly = true)
    public List<ActivityEntry> forGroup(String appSubject, String groupId) {
        MemberId actor = members.resolve(appSubject).orElseThrow(GroupAccessDeniedException::new);
        Group group = groups.findById(new GroupId(groupId)).orElseThrow(GroupAccessDeniedException::new);
        if (!group.canRead(actor)) {
            throw new GroupAccessDeniedException();
        }
        return activity.forScope(CorrectionService.scopeId(group, group.currency()));
    }
}
