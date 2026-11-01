package com.dutch.ledger.expense;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;
import java.util.regex.Pattern;

public record ExpenseId(String value) {
    private static final Pattern PATTERN = Pattern.compile("^exp_[A-Za-z0-9_-]{16,128}$");

    public ExpenseId {
        Objects.requireNonNull(value, "expense id");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("expense id does not match the pinned format");
        }
    }

    @JsonValue
    @Override
    public String value() {
        return value;
    }
}
