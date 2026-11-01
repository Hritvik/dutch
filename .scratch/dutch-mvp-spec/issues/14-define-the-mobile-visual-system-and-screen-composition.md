Type: prototype
Status: resolved
Blocked by: 02, 09

# Define the mobile visual system and screen composition

## Question

What visual language, component hierarchy, responsive composition, native-platform adaptation, and polished state designs should express the approved Balance-First information architecture across Home, Groups, Activity, Relationship, Expense, Settlement, onboarding, error, empty, cached, and conflict experiences?

## Answer

Prototype: [mobile-visual-system-prototype.html](../prototypes/mobile-visual-system-prototype.html) (`?variant=A`, `B`, or `C`).

### Selected direction

Select **A — Calm balance-first**, borrowing B's direct quick-action clarity where it improves the approved information architecture. A places the current, per-currency Balance before feeds or decoration; scope, evidence, and next action remain visible without becoming a dashboard of unrelated cards. B's command-center density competes with financial reading, while C's relationship-first structure overemphasizes people over the settled Group/direct scope model.

### Visual system

- Use a warm off-white base, deep forest ink, restrained green for positive/primary actions, muted slate secondary text, and a single soft amber attention state. Red is reserved for destructive confirmation/error, not an amount a Member owes. Color never carries balance direction or status alone.
- Use one native system sans-serif family, four practical sizes, and tabular/monospace figures for amounts. A 4/8-point spacing scale, 44-point minimum targets, visible focus, dynamic type, screen-reader labels, and high-contrast variants are mandatory.
- Use lightly bordered, softly rounded surfaces and subtle tinted shadows only to group information. Avoid decorative gradients, currency-conversion charts, heavy gamification, and modal stacks. Motion is brief and functional: a settled confirmation, a balance refresh, and a state transition—not a claim that money moved.

### Screen composition

- **Home:** a per-currency net-position hero with “last updated” state; needs-attention items; scoped balance rows; and recent Activity. The global add button opens Expense creation; settlement starts from the relevant balance/relationship or contextual scope.
- **Groups:** a scannable Group list, then a Group overview with its currency, member positions, simplified suggested transfers, Expenses, Settlements, Activity, Members, and settings. Every contextual action is visibly prefilled with its Group.
- **Activity:** a chronological, filterable evidence feed with concise summaries and links into surviving Expense revisions. Deleted Expenses remain Activity evidence, not ordinary history-list items.
- **Relationship/direct context:** show one member-to-member balance per currency and the Direct Expenses that explain it. It never masquerades as a Group or silently combines Group balances.
- **Expense:** amount, currency, payer contributions, participant shares, and consequence preview lead; notes/category/date follow; review comes before submit. Revision History makes before/after financial effects readable.
- **Settlement:** clearly says an external payment was recorded. Scope selection supports one Group, direct balance, or Settle all; Settle all shows its allocation plan before confirmation and confirmation shows the resulting scope evidence.
- **Onboarding:** one decision per step—verify identifier, set minimal profile, review Claim Offers/invitations, then land on Home. Permission prompts appear only at their moment of value.

### State design and platform adaptation

- Empty states explain the next useful action (create Group, add Expense, invite person) rather than presenting blank surfaces. Loading uses stable skeleton geometry; `503` and offline states preserve context and distinguish unsent Local Drafts from accepted records.
- Cached screens carry offline/refreshing and last-successful-sync state. Conflicts show the latest revision beside the Local Draft and offer reload, reapply, or discard—never a silent merge. Access loss removes the scope and explains it without leaking data.
- Keep the semantic hierarchy, language, and flow identical on iOS and Android while using platform navigation transitions, keyboard behavior, share sheets, haptics, and system permission affordances. Phone layouts use one column; tablet/wide layouts retain the same order with a contextual detail pane rather than a different information architecture.
