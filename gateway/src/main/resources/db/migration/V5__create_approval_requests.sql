-- FR4: one row per held call. original_arguments never changes; edited_arguments is set only when an approver edits.
CREATE TABLE approval_requests (
    id                  UUID PRIMARY KEY,
    agent_id            VARCHAR(64)  NOT NULL,
    end_user_id         VARCHAR(100),
    tool_name           VARCHAR(64)  NOT NULL,
    original_arguments  JSONB        NOT NULL,
    edited_arguments    JSONB,
    state               VARCHAR(24)  NOT NULL,
    approver            VARCHAR(100),
    reason              VARCHAR(500),
    created_at          TIMESTAMPTZ  NOT NULL,
    decided_at          TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_approval_requests_state CHECK (state IN
        ('PENDING', 'APPROVED', 'APPROVED_WITH_EDITS', 'REJECTED', 'EXPIRED', 'CANCELLED'))
);
CREATE INDEX ix_approval_requests_state_created ON approval_requests (state, created_at);
