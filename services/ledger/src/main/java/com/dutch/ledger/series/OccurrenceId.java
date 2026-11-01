package com.dutch.ledger.series;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;
import java.util.regex.Pattern;

public record OccurrenceId(String value) {
    private static final Pattern PATTERN = Pattern.compile("^occ_[A-Za-z0-9_-]{16,128}$");

    public OccurrenceId {
        Objects.requireNonNull(value, "occurrence id");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("occurrence id does not match the pinned format");
        }
    }

    @JsonCreator
    public static OccurrenceId of(String value) {
        return new OccurrenceId(value);
    }

    @JsonValue
    @Override
    public String value() {
        return value;
    }
}
