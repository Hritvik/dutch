# W-11 integration map

```text
W11-A schema/repository ─┐
                         ├─> W11-C fault proof ─> W11-R ─> W11-F
W11-B consumer/event ────┘
```

A owns the migration and persistence; B owns the event/consumer. C owns the fault matrix and evidence.
