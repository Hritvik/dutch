Type: grilling
Status: resolved
Blocked by: 10, 15, 16

# Define launch-market, legal, scale, and measurement targets

## Question

Which launch market and data-residency jurisdiction govern Dutch; what terms, consent, age, privacy-export/deletion, and financial-record-retention policies apply; what capacity and latency targets define the MVP; and what user-comprehension/success metrics must be met before launch?

## Answer

Legal research: [india-launch-policy-research.md](../../../research/india-launch-policy-research.md). This is a product/engineering policy, not legal advice; Indian counsel sign-off is a production gate.

### India-first launch policy

- Dutch launches India-first with primary production in AWS `ap-south-1`. This is a user-latency, processor-governance, and residency-risk-control choice—not a claim of a universal India-only hosting mandate. A different launch market requires a new legal/operational review.
- Registered accounts are 18+ only. Dutch does not initiate, route, settle, hold, or custody money. Any payment rail, UPI orchestration, wallet, custody, or payment-linked fee is a new regulated-scope decision.
- Public beta and production require accessible English and Hindi legal/support material: Terms/User Rules, Privacy Policy, grievance contact/process, account export/deletion path, and a public legal/support page. The exact intermediary-language requirement and user experience require Indian counsel confirmation.
- Before public launch, Indian counsel confirms Dutch's intermediary/CERT-In/legacy-SPDI classification; Terms/Privacy/retention wording; Grievance Officer/contact and response process; processor/transfer map; account-anonymization/financial-evidence basis; and incident/lawful-request runbooks. If classified as an intermediary, implement the researched complaint acknowledgement/resolution and evidence-retention clocks.
- Build now for DPDP notice/affirmative consent, withdrawal, rights/access/export, correction/erasure, grievance, security/breach, child, processor-deletion, and transfer controls. Core DPDP product duties begin 14 May 2027 according to the cited official research; launch policy does not defer those capabilities. Legal holds/lawful retention override ordinary deletion only under the documented retention schedule.

### Scale and performance target

- Launch capacity is 10,000 registered Members, 1,000 daily active Members, Groups of up to 100 Members, sustained/burst planning of 100 read requests per second and 20 financial-write requests per second.
- At launch, p95 authoritative reads are under 500 ms, accepted financial writes under 1 second, and incremental sync under 2 seconds under the target load. These exclude external push-provider delivery and require correct—not merely fast—Ledger results.
- Capacity tests cover database connection limits, Fargate task scaling, queue throughput/message-group distribution, outbox lag, full-sync recovery, and notification rate limiting. Failure to meet targets changes capacity/configuration before launch, not Ledger correctness rules.

### Product-quality and measurement gate

- Before public launch, moderated representative usability tests require at least 85% successful normal Expense creation and at least 80% correct pre-submit prediction for multi-payer allocation, overpayment, and Settle all effects. A participant must explain which scope/balance changes, not merely finish the screen.
- Product telemetry measures funnel completion, validation/conflict/draft-recovery rate, correction/void rate, stale-cache/cursor-reset rate, notification deep-link refresh success, support-report category, and comprehension-test outcome. It excludes raw contact data, notes, tokens, and financial record bodies.
- A material comprehension failure blocks launch or triggers an interaction-model revision; it is not papered over with tooltip text. The public beta also validates grievance/export/deletion support paths and the stated service-performance target.
