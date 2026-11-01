Type: grilling
Status: resolved
Blocked by: 05, 07

# Define financial dates, currency, and input policy

## Question

What time-zone, occurred-date, recorded-date, future/backdate, supported-currency, currency-registry, amount/description/note-limit, and pre-history Group-currency-change rules make Expense and Settlement input predictable, exact, and internationally safe?

## Answer

### Dates and ordering

- Every Expense and Settlement requires a user-selected occurred date. It may optionally include a user-entered local time. When supplied, the Ledger stores the entered local date/time, IANA timezone, and resolved instant; when absent, it stores only the calendar date and never invents midnight or another time. Server-recorded time remains a separate immutable audit fact.
- Activity is ordered by server-recorded time. Expense/Settlement views group by occurred date; timed items order by their entered local time, while date-only items appear in a clearly labelled `Time not recorded` portion of that day ordered by recorded time and a stable identifier. Balances do not depend on display ordering.
- MVP rejects future occurred dates/times. A planned cost remains a Local Draft until it occurs and is explicitly submitted. Date/time corrections follow the immutable Expense or Settlement correction model.

### Currency policy

- A versioned ISO-4217 registry owned by the Ledger validates code, minor-unit scale, and configured launch availability. The launch-market ticket selects the initial visible currency set; unsupported currencies fail validation rather than being silently rounded or converted.
- A Group currency can change only before its first financial record. Direct Expenses and Settlements each use exactly one supported currency. A financial correction may change currency only when its target scope permits it; Group history never gains a second currency.

### Input validation

- Financial input accepts positive amounts only. Amount policy is per currency and bounded safely below `int64`; server validation, not a mobile-only limit, enforces it.
- Description is required, trimmed plain text of 1–160 characters. Note is optional trimmed plain text of at most 2,000 characters. Dutch renders neither markup nor URLs as rich content and redacts these fields from standard operational logs.
