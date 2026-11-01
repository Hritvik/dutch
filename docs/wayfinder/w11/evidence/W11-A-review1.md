# W11-A review 01

Decision: reject

Findings: MEDIUM the V3 migration drops the prototype tables with no V2 upgrade/retention proof, contradicting the packet's acceptance line. LOW migration tests asserted almost no V3 constraints; outbox identity columns were under-constrained; the unused IdentityVerifiedEvent record remains.
