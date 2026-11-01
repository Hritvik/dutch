package com.dutch.ledger.group;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;
import java.util.regex.Pattern;

/** A canonical Dutch Member identifier resolved from the local UMS projection. */
public record MemberId(String value) implements Comparable<MemberId> {

    private static final Pattern PATTERN = Pattern.compile("^mem_[A-Za-z0-9_-]{16,128}$");

    public MemberId {
        Objects.requireNonNull(value, "member id");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("member id does not match the pinned format");
        }
    }

    @JsonValue
    @Override
    public String value() {
        return value;
    }

    @Override
    public int compareTo(MemberId other) {
        return value.compareTo(other.value);
    }
}
