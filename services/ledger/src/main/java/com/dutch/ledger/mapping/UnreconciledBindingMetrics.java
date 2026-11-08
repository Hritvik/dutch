package com.dutch.ledger.mapping;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Makes the two unreconciled shapes observable instead of inferable from a denial:
 * reports with no Dutch member, and active members with no UMS report (the
 * provider-outage case).
 */
@Component
public class UnreconciledBindingMetrics {

    public UnreconciledBindingMetrics(MeterRegistry registry,
                                      JdbcMemberAssociationReportConsumer consumer) {
        Gauge.builder("dutch.ledger.association.unreconciled", consumer,
                        source -> source.unreconciledSubjects().size())
                .description("Association reports without a Dutch member mapping")
                .register(registry);
        Gauge.builder("dutch.ledger.association.unmapped", consumer,
                        source -> source.unmappedReports().size())
                .description("Active members with no UMS association report")
                .register(registry);
    }
}
