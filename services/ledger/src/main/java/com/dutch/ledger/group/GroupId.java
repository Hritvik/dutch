package com.dutch.ledger.group;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;
import java.util.regex.Pattern;

/** A Dutch-owned Group identifier. */
public record GroupId(String value) {

    private static final Pattern PATTERN = Pattern.compile("^grp_[A-Za-z0-9_-]{16,128}$");

    public GroupId {
        Objects.requireNonNull(value, "group id");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("group id does not match the pinned format");
        }
    }

    @JsonValue
    @Override
    public String value() {
        return value;
    }
}
