Type: grilling
Status: resolved
Blocked by: 05, 07, 09, 20

# Define recurring Expense series lifecycle

## Question

How do recurring Expense schedules express cadence, timezone, due dates, start/end/skip/pause/resume behavior, template changes, membership/split/currency changes, explicit occurrence review/submission, corrections, Activity, notifications, and offline recovery—without creating future financial obligations before an occurrence is accepted?

## Answer

### Scope, schedule, and template

- A Recurring Expense Series may belong to a Group or a direct relationship. It supports weekly or monthly cadence, optional end date, and exactly one supported currency; a Group Series uses that Group's fixed currency. Daily/custom recurrence is out of MVP.
- The Series owns a current template: description, default amount, Payer Contributions, participants/split, category/note, cadence, end date, and pinned IANA schedule timezone. Direct Series have their own participant snapshot; any current registered participant may manage it or submit a due occurrence. Series edits affect only future periods.
- Placeholder and Invited Members may appear in templates and Pending Occurrences under the same allocation rules as an ordinary Expense. They receive no due notification and cannot manage or submit until linked to an Identity.

### Pending occurrences and acceptance

- At each due period, a server worker creates at most one idempotent `Pending Occurrence` with a snapshot of the then-current template. It is durable but nonfinancial: it never creates a balance, Obligation Entry, Activity financial mutation, or implied debt.
- A pending item remains until an eligible Active Group Member or current registered Direct Series participant explicitly reviews and submits it as an ordinary Expense, or explicitly skips it. Later due periods continue generating independently; missed items are never automatically submitted, skipped, or treated as debt.
- The reviewer may adjust any Expense field before submission. Submission revalidates current membership, currency, split/contribution totals, scope authorization, and idempotency exactly as a manual Expense; it then creates a normal immutable Expense with a source-series/period reference.
- A generated Pending Occurrence retains its creation snapshot. Subsequent Series edits apply only to not-yet-generated periods, preventing a later amount/split change from silently changing an already-due item.

### Lifecycle, evidence, and recovery

- Pause stops future generation; resume continues at the next due period; end permanently stops future generation. Skip records that period's nonfinancial decision. These schedule actions are append-only Activity facts and never rewrite accepted Expenses.
- Archiving a Group automatically pauses all of its Series and stops future Pending Occurrences. Reopening leaves them paused until an Admin explicitly resumes each Series.
- Corrections to an accepted occurrence use the ordinary immutable Expense-revision flow; they do not mutate the Series or other occurrences. Series/direct participant changes preserve prior snapshots and history under the settled scope-lifecycle policy.
- Eligible participants receive a privacy-preserving due notification; notification failure never affects schedule or financial state. Offline clients may read cached pending items and prepare a Local Draft, but explicit online review/submission is required. Sync returns Series, Pending Occurrences, skip/pause/resume state, and accepted source references through the established cursor contract.
