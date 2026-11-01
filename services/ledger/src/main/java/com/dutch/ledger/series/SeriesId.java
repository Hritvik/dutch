package com.dutch.ledger.series;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;
import java.util.regex.Pattern;

public record SeriesId(String value) {
    private static final Pattern PATTERN = Pattern.compile("^ser_[A-Za-z0-9_-]{16,128}$");

    public SeriesId {
        Objects.requireNonNull(value, "series id");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("series id does not match the pinned format");
        }
    }

    @JsonCreator
    public static SeriesId of(String value) {
        return new SeriesId(value);
    }

    @JsonValue
    @Override
    public String value() {
        return value;
    }
}
