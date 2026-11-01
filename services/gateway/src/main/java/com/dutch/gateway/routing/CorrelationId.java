package com.dutch.gateway.routing;

import java.util.UUID;
import java.util.regex.Pattern;

/** Pinned correlation contract: a safe opaque id echoed or regenerated per request. */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-ID";
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");

    private CorrelationId() {
    }

    public static String resolve(String supplied) {
        return supplied != null && SAFE.matcher(supplied).matches() ? supplied : generate();
    }

    public static String generate() {
        return "cor_" + UUID.randomUUID().toString().replace("-", "");
    }
}
