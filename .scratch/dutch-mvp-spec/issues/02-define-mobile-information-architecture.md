Type: prototype
Status: resolved
Blocked by: 01, 13

# Define the mobile information architecture and core interaction model

## Question

What navigation structure, screen hierarchy, and primary interaction model let members understand balances and complete the critical flows—onboarding, group management, adding or correcting an expense, viewing activity, and recording a settlement—with the least friction on both iOS and Android?

## Comments

- Prototype round one established three visually coherent hypotheses, but selection is paused. The decision must be justified by information architecture and interaction principles rather than visual preference. See **Learn mobile information architecture and interaction-model best practices**.

## Answer

Adopt a **Balance-First** information architecture whose launch purpose is to explain the signed-in Member's current financial position and anything requiring attention. This selects the structural hypothesis, not the original prototype's visual treatment.

### Top-level destinations

- **Home** — personal cross-context financial position, attention items, and open balances.
- **Groups** — stable shared spaces and their contextual financial histories.
- **Activity** — searchable app-wide evidence of what changed, who acted, when, and in which scope.

These are the only persistent bottom-navigation destinations. Profile, verification, preferences, security, and sign-out sit behind the avatar. Add Expense is an action, never a destination.

### Home

Home shows owed and owing totals separately by currency, actionable invitations/conflicts/failed operations/material changes, and open balances labelled with their Group or direct context. Every summary drills into contributing Expenses and Settlements. Home does not duplicate the Group gallery or generic Activity feed.

Selecting a person opens a **Relationship** view that summarizes balances separately by currency and itemizes direct and Group contexts without rerouting obligations. It offers contextual Add Direct Expense and Record Settlement actions.

### Groups

A Group is the authoritative workspace for one shared financial context. Its landing view exposes Group identity, fixed currency, active members, the current Member's position, outstanding member-to-member balances, chronological Expenses and Settlements, contextual Add Expense, and paths from each balance to evidence. Group-filtered Activity is reachable within the Group; membership and Admin controls live under Group settings. Group detail uses stack/back navigation rather than another bottom bar.

### Activity and history

Activity is the chronological cross-product evidence trail for Expense, Settlement, membership, invitation, and claim changes. Events identify actor, time, scope, and affected object; they deep-link to the relevant object and revision. Removed or inaccessible objects produce a safe historical explanation.

The Activity Log and object histories are projections over one canonical immutable change record. **Expense History** shows every revision and balance consequence of one Expense. Editing, deletion, restoration, and undo produce new revisions rather than erasing history.

### Actions and scope

Add Expense is the one global financial creation action. A global start asks for Group or direct scope; Group and Relationship entry points prefill scope, currency, and participants. All converge on the same flow after scope is established. Record Settlement is contextual only, beginning from a specific counterparty balance and currency.

Expense creation progressively establishes scope, core details, payer, participants, split method, and a final allocation preview. Equal is the default; exact and percentage are alternatives. The final preview must prove shares equal the Expense total and state the resulting obligations. Optional notes remain secondary. Success identifies the committed object and balance effect.

Settlement uses explicit external-payment language, allows partial settlement, previews direction and resulting balance, and ends by stating Dutch recorded—but did not move—the money.

### Collaborative changes

Expense detail identifies creator, latest editor, timestamps, allocation, and View History. Edit reuses the creation structure and previews consequences. Optimistic concurrency blocks silent overwrite and returns the latest revision for deliberate review. Delete creates an auditable reversal or tombstone; Undo creates another revision. Notification failure never implies the financial mutation failed.

### Onboarding and notifications

Onboarding verifies email or E.164 phone through IAM, creates a minimal Member Profile, lets UMS link exact Placeholder Member matches, reviews resulting Groups/invitations/approval-required merges, then enters Home. Notification permission is requested only when its value becomes contextual.

Notifications are entry points rather than a new hierarchy. Each deep-links to the narrowest current or historical context and refreshes authoritative state after authentication.

### Offline and collision behavior

Cached financial views display last-updated and offline state. Local drafts are allowed but never silently committed or queued as authoritative financial writes. On reconnection, the app reviews a draft against current membership and object versions before submission.

Commands use stable idempotency keys and expected server versions. The backend validates current membership, canonical Member IDs, currency, allocation totals, Expense version, and reviewed Settlement balance in one transaction. Conflicts preserve the draft and return a specific latest-state comparison; financial fields are never last-write-wins or automatically merged. A lost success response is recovered by retrying the same idempotency key.

### Evaluation

Validate this model with fixed journeys: invitation, all split methods, direct and Group creation, balance explanation, collaborative edit/history, deletion reconstruction, external settlement, interrupted submission, Placeholder Member claiming, and inactive membership with outstanding balances. Measure orientation, first-action correctness, recovery, scope comprehension, consequence prediction, evidence access, and accessibility—not visual preference.

Prototype context: [mobile-shell-picker.html](../prototypes/mobile-shell-picker.html). The selected structure requires a refined visual prototype; the original Balance First variant is a hypothesis artifact, not the final screen design.
