CREATE TABLE safety_report (
    report_id TEXT PRIMARY KEY CHECK (report_id ~ '^rep_[A-Za-z0-9_-]{16,128}$'),
    reporter_member_id TEXT NOT NULL CHECK (reporter_member_id ~ '^mem_[A-Za-z0-9_-]{16,128}$'),
    category TEXT NOT NULL CHECK (category IN ('ABUSE', 'HARASSMENT', 'SPAM', 'FRAUD', 'OTHER')),
    scope_type TEXT NOT NULL CHECK (scope_type IN ('GROUP', 'DIRECT_EXPENSE', 'MEMBER', 'OTHER')),
    scope_id TEXT,
    details TEXT NOT NULL CHECK (char_length(details) BETWEEN 1 AND 1000),
    status TEXT NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED', 'TRIAGED', 'CLOSED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX safety_report_status_idx ON safety_report (status, created_at);
