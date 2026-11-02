-- Access changes are their own journal resource so they never replace the Group projection.
ALTER TABLE member_sync_change
    DROP CONSTRAINT IF EXISTS member_sync_change_resource_type_check;

ALTER TABLE member_sync_change
    ADD CONSTRAINT member_sync_change_resource_type_check
        CHECK (resource_type IN (
            'GROUP', 'EXPENSE', 'DIRECT_EXPENSE', 'SETTLEMENT', 'ACTIVITY', 'SERIES',
            'OCCURRENCE', 'ACCESS'));
