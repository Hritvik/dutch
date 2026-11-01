package com.dutch.ledger.settlement;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;
import java.util.regex.Pattern;

public record SettlementId(String value) {
    private static final Pattern PATTERN = Pattern.compile("^stl_[A-Za-z0-9_-]{16,128}$");

    public SettlementId {
        Objects.requireNonNull(value, "settlement id");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("settlement id does not match the pinned format");
        }
    }

    @JsonCreator
    public static SettlementId of(String value) {
        return new SettlementId(value);
    }

    @JsonValue
    @Override
    public String value() {
        return value;
    }
}
