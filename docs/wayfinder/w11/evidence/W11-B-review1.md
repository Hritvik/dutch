# W11-B review 01

Decision: reject

Findings: HIGH the consumer required a non-null causationId, but the IAM producer always writes null, so every real onboarding event would be rejected. MEDIUM the input model and fingerprint omitted activationState and requiredProfileState; the @Transactional boundary was only exercised through an external TransactionTemplate.
