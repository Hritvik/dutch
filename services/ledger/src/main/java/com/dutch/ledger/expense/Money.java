package com.dutch.ledger.expense;

import java.util.Objects;
import java.util.regex.Pattern;

/** An exact amount in one ISO currency's minor unit. Never floating point. */
public record Money(long minorUnits, String currency) {

    /** Keeps multiplication by 10,000 safely inside a signed 64-bit long. */
    public static final long MAX_MINOR_UNITS = 900_000_000_000_000L;
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    public Money {
        if (!CURRENCY.matcher(Objects.requireNonNull(currency, "currency")).matches()) {
            throw new IllegalArgumentException("currency must be an ISO 4217 code");
        }
        if (Math.abs(minorUnits) > MAX_MINOR_UNITS) {
            throw new IllegalArgumentException("amount is outside the supported bounds");
        }
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    public boolean isPositive() {
        return minorUnits > 0;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("currencies must match");
        }
    }
}
