package com.dutch.ledger.expense;

/** Immutable identifier for the deterministic allocation rules in this package. */
public final class AllocationAlgorithmVersion {
    public static final String EXPENSE_ALLOCATION_V1 = "expense-allocation-v1";
    public static final String SIMPLIFIED_PLAN_V1 = "simplified-plan-v1";
    public static final String SETTLEMENT_ALLOCATION_V1 = "settlement-allocation-v1";

    private AllocationAlgorithmVersion() {
    }
}
