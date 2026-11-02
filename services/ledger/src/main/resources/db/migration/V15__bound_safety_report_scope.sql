ALTER TABLE safety_report
    ADD CONSTRAINT safety_report_scope_id_bound
        CHECK (scope_id IS NULL OR char_length(scope_id) <= 256);
