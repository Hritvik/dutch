# W10-B review 02

Decision: approve

Findings: B-1 fixed — the 409 references ProfileRequiredResponse with `const profile_required` and the pinned message, and a negative test rejects another code. B-2/B-3 fixed (details array, inbound correlation header). B-4 low remains: privacy tests remain partly synthetic. Note: the running UMS controller still returns the legacy shape; W-12 owns that and W10-F must not claim runtime compliance.
