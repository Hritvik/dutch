Type: research
Status: resolved

# Learn mobile information architecture and interaction-model best practices

## Question

Which evidence-backed mobile information-architecture and interaction-design principles should Dutch use to justify what belongs in its primary navigation, what each destination is for, where actions live, how global and contextual scope differ, and how financial and collaborative workflows remain coherent across iOS and Android?

## Answer

Use scope as Dutch's primary organizing principle: distinguish app-wide, Group, relationship, Expense, and Settlement contexts at all times. Permanent navigation is reserved for stable, frequently revisited peer destinations—not actions or backend entities. Action placement communicates scope: contextual entry points prefill their Group or person, while any global expense action must ask for scope explicitly.

Every balance summary must drill into its causal Expenses and Settlements. Shared financial mutations must be attributable, predictable before commit, idempotent, recoverable, reversible through explicit compensating history, and auditable. Settlement language must never imply Dutch moved money. Cached, offline, retry, and concurrency states must be honest. The same domain semantics apply across iOS and Android while controls follow native conventions and remain usable through assistive technology and non-gesture alternatives.

Choose the final model by running fixed Dutch journeys and measuring orientation, first-action correctness, recovery, scope comprehension, consequence prediction, and access to evidence—not by aesthetic preference.

Research context: branch `research/mobile-ia-interaction-best-practices`, commit `6ed3a4a`, artifact `research/mobile-ia-interaction-best-practices.md`. The report cites current Apple HIG, Android Developers, and W3C WCAG 2.2 primary guidance.
