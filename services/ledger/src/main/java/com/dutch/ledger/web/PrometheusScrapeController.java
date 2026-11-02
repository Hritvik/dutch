package com.dutch.ledger.web;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit scrape path; the platform scrapes /actuator/prometheus. */
@RestController
public class PrometheusScrapeController {

    private final PrometheusMeterRegistry registry;

    public PrometheusScrapeController(PrometheusMeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(value = "/actuator/prometheus", produces = "text/plain;version=0.0.4;charset=utf-8")
    String scrape() {
        return registry.scrape();
    }
}
