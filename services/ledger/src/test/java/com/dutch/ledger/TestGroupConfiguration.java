package com.dutch.ledger;

import com.dutch.ledger.group.ConcurrentGroupUpdateException;
import com.dutch.ledger.activity.ActivityEntry;
import com.dutch.ledger.activity.ActivityRepository;
import com.dutch.ledger.expense.BalanceProjection;
import com.dutch.ledger.expense.CorrectionRepository;
import com.dutch.ledger.expense.Expense;
import com.dutch.ledger.expense.ExpenseCorrection;
import com.dutch.ledger.expense.ExpenseId;
import com.dutch.ledger.expense.ExpenseRepository;
import com.dutch.ledger.expense.IdempotencyStore;
import com.dutch.ledger.direct.DirectExpense;
import com.dutch.ledger.direct.DirectExpenseRepository;
import com.dutch.ledger.direct.DirectScope;
import com.dutch.ledger.expense.ObligationEntry;
import com.dutch.ledger.settlement.Settlement;
import com.dutch.ledger.settlement.SettlementId;
import com.dutch.ledger.settlement.SettlementRepository;
import com.dutch.ledger.settlement.SettlementVoid;
import com.dutch.ledger.group.Group;
import com.dutch.ledger.group.GroupId;
import com.dutch.ledger.group.GroupRepository;
import com.dutch.ledger.group.MemberDirectory;
import com.dutch.ledger.group.MemberId;
import com.dutch.ledger.group.MemberResolver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@TestConfiguration
public class TestGroupConfiguration {

    public static final String ADMIN_SUBJECT = "aps_admin_0123456789012345678901";
    public static final String MEMBER_SUBJECT = "aps_member_012345678901234567890";
    public static final String OUTSIDER_SUBJECT = "aps_outsider_01234567890123456789";
    public static final MemberId ADMIN = new MemberId("mem_012345678901234567");
    public static final MemberId MEMBER = new MemberId("mem_112345678901234567");
    public static final MemberId OUTSIDER = new MemberId("mem_212345678901234567");

    private static final Map<String, MemberId> MEMBERS = Map.of(
            ADMIN_SUBJECT, ADMIN,
            MEMBER_SUBJECT, MEMBER,
            OUTSIDER_SUBJECT, OUTSIDER);

    @Bean
    @Primary
    GroupRepository groupRepository() {
        return new InMemoryGroupRepository();
    }

    @Bean
    @Primary
    MemberResolver memberResolver() {
        return subject -> Optional.ofNullable(MEMBERS.get(subject));
    }

    @Bean
    @Primary
    MemberDirectory memberDirectory() {
        java.util.Set<MemberId> active = java.util.Set.of(ADMIN, MEMBER, OUTSIDER);
        return active::contains;
    }

    @Bean
    JdbcTemplate jdbcTemplate() {
        return Mockito.mock(JdbcTemplate.class);
    }

    @Bean
    @Primary
    ExpenseRepository expenseRepository() {
        return new InMemoryExpenseRepository();
    }

    @Bean
    @Primary
    IdempotencyStore idempotencyStore() {
        return new InMemoryIdempotencyStore();
    }

    @Bean
    @Primary
    CorrectionRepository correctionRepository(ExpenseRepository expenses) {
        return new InMemoryCorrectionRepository(expenses);
    }

    @Bean
    @Primary
    ActivityRepository activityRepository() {
        return new InMemoryActivityRepository();
    }

    @Bean
    @Primary
    SettlementRepository settlementRepository() {
        return new InMemorySettlementRepository();
    }

    @Bean
    @Primary
    DirectExpenseRepository directExpenseRepository() {
        return new InMemoryDirectExpenseRepository();
    }

    @Bean
    PlatformTransactionManager transactionManager() {
        return new NoOpTransactionManager();
    }

    public static final class InMemoryGroupRepository implements GroupRepository {
        private final Map<GroupId, Group> groups = new ConcurrentHashMap<>();

        @Override
        public Optional<Group> findById(GroupId id) {
            Group stored = groups.get(id);
            return stored == null ? Optional.empty() : Optional.of(copy(stored));
        }

        @Override
        public Optional<Group> findByIdForUpdate(GroupId id) {
            return findById(id);
        }

        @Override
        public List<Group> findReadableBy(MemberId memberId) {
            return groups.values().stream()
                    .filter(group -> group.canRead(memberId))
                    .map(TestGroupConfiguration::copy)
                    .toList();
        }

        @Override
        public void saveNew(Group group) {
            groups.put(group.id(), copy(group));
        }

        @Override
        public void save(Group group, long expectedVersion) {
            Group stored = groups.get(group.id());
            if (stored == null || stored.version() != expectedVersion) {
                throw new ConcurrentGroupUpdateException();
            }
            groups.put(group.id(), copy(group));
        }
    }

    private static Group copy(Group group) {
        return Group.restore(group.id(), group.name(), group.currency(), group.state(),
                group.version(), group.memberships());
    }

    static final class InMemoryCorrectionRepository implements CorrectionRepository {
        private final java.util.List<ExpenseCorrection> saved = new CopyOnWriteArrayList<>();
        private final ExpenseRepository expenses;

        InMemoryCorrectionRepository(ExpenseRepository expenses) {
            this.expenses = expenses;
        }

        @Override
        public void save(ExpenseCorrection correction) {
            saved.add(correction);
            expenses.save(correction.replacement());
        }

        int size() {
            return saved.size();
        }
    }

    static final class InMemoryActivityRepository implements ActivityRepository {
        private final java.util.List<ActivityEntry> entries = new CopyOnWriteArrayList<>();

        @Override
        public void append(ActivityEntry entry) {
            entries.add(entry);
        }

        @Override
        public java.util.List<ActivityEntry> forScope(String scopeId) {
            return entries.stream().filter(entry -> entry.scopeId().equals(scopeId)).toList();
        }
    }

    static final class InMemoryDirectExpenseRepository implements DirectExpenseRepository {
        private final java.util.List<DirectExpense> saved = new CopyOnWriteArrayList<>();

        @Override
        public void save(DirectExpense expense) {
            saved.add(expense);
        }

        @Override
        public Optional<DirectExpense> find(com.dutch.ledger.expense.ExpenseId id) {
            return saved.stream()
                    .filter(expense -> expense.id().equals(id))
                    .max(java.util.Comparator.comparingLong(DirectExpense::revision));
        }

        @Override
        public java.util.List<String> streamIds(com.dutch.ledger.expense.ExpenseId id) {
            return find(id).map(expense -> expense.obligationEntries().stream()
                    .map(entry -> DirectScope.streamId(entry.debtor(), entry.creditor(),
                            expense.currency()))
                    .distinct().sorted().toList()).orElse(java.util.List.of());
        }
    }

    static final class InMemorySettlementRepository implements SettlementRepository {
        private final Map<SettlementId, Settlement> saved = new ConcurrentHashMap<>();
        private final Map<SettlementId, String> statuses = new ConcurrentHashMap<>();

        @Override
        public void save(Settlement settlement) {
            saved.put(settlement.id(), settlement);
            statuses.put(settlement.id(), "ACTIVE");
        }

        @Override
        public void voidSettlement(SettlementVoid voidAction) {
            if (!"ACTIVE".equals(statuses.get(voidAction.settlementId()))) {
                throw new com.dutch.ledger.group.GroupRuleException(
                        "This settlement has already been voided.");
            }
            statuses.put(voidAction.settlementId(), "VOIDED");
        }

        @Override
        public Optional<Settlement> find(SettlementId id) {
            return Optional.ofNullable(saved.get(id));
        }

        @Override
        public String status(SettlementId id) {
            return statuses.get(id);
        }
    }

    static final class InMemoryExpenseRepository implements ExpenseRepository {
        private final java.util.List<Expense> saved = new CopyOnWriteArrayList<>();

        @Override
        public void save(Expense expense) {
            saved.add(expense);
        }

        @Override
        public java.util.Optional<Expense> findExpense(ExpenseId id) {
            return saved.stream()
                    .filter(expense -> expense.id().equals(id))
                    .max(java.util.Comparator.comparingLong(Expense::revision));
        }

        @Override
        public java.util.List<ObligationEntry> entriesFor(GroupId groupId, String currency) {
            // Only the latest revision of each expense is live here: the in-memory
            // adapter has no reversal entries, so the latest revision is its net.
            return saved.stream()
                    .filter(expense -> expense.groupId().equals(groupId)
                            && expense.currency().equals(currency))
                    .collect(java.util.stream.Collectors.groupingBy(Expense::id,
                            java.util.stream.Collectors.maxBy(
                                    java.util.Comparator.comparingLong(Expense::revision))))
                    .values().stream()
                    .flatMap(latest -> latest.stream())
                    .flatMap(expense -> expense.obligationEntries().stream())
                    .toList();
        }

        @Override
        public BalanceProjection projectionFor(GroupId groupId, String currency) {
            return BalanceProjection.from(entriesFor(groupId, currency), currency);
        }

        int size() {
            return saved.size();
        }
    }

    static final class InMemoryIdempotencyStore implements IdempotencyStore {
        private record Key(String actor, String command, String key) {
        }

        private final java.util.Map<Key, Outcome> outcomes = new ConcurrentHashMap<>();

        @Override
        public java.util.Optional<Outcome> find(String actorMemberId, String command, String idempotencyKey) {
            return java.util.Optional.ofNullable(outcomes.get(new Key(actorMemberId, command, idempotencyKey)));
        }

        @Override
        public boolean reserve(String actorMemberId, String command, String idempotencyKey, String requestHash) {
            return outcomes.putIfAbsent(new Key(actorMemberId, command, idempotencyKey),
                    new Outcome(requestHash, "{}")) == null;
        }

        @Override
        public void complete(String actorMemberId, String command, String idempotencyKey, String responseJson) {
            outcomes.computeIfPresent(new Key(actorMemberId, command, idempotencyKey),
                    (key, outcome) -> new Outcome(outcome.requestHash(), responseJson));
        }
    }

    static final class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}