# W10-B review 01

Decision: reject

Findings: MEDIUM B-1 the 409 response referenced the generic ErrorResponse, so any error code validated and the packet's headline profile_required behaviour was not pinned. LOW B-2 the pin record claimed details support that the schema lacked; B-3 no inbound correlation parameter; B-4 privacy tests ran on synthetic objects only. Note: the running UMS controller still returns the legacy identityId shape (W-12 scope).
