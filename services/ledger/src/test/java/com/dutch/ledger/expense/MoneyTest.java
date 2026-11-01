package com.dutch.ledger.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void adds_and_subtracts_exact_minor_units() {
        Money ten = new Money(1000, "INR");
        Money five = new Money(500, "INR");

        assertThat(ten.add(five)).isEqualTo(new Money(1500, "INR"));
        assertThat(ten.subtract(five)).isEqualTo(new Money(500, "INR"));
        assertThat(five.negate()).isEqualTo(new Money(-500, "INR"));
    }

    @Test
    void currencies_must_match() {
        assertThatThrownBy(() -> new Money(100, "INR").add(new Money(100, "USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currencies must match");
    }

    @Test
    void invalid_codes_and_out_of_bounds_amounts_are_rejected() {
        assertThatThrownBy(() -> new Money(100, "rupee"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(Money.MAX_MINOR_UNITS + 1, "INR"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(Long.MIN_VALUE, "INR"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Money(Money.MAX_MINOR_UNITS, "INR").minorUnits())
                .isEqualTo(Money.MAX_MINOR_UNITS);
    }
}
