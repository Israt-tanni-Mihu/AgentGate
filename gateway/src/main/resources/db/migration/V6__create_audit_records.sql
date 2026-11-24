-- FR7: append-only audit log, hash-chained. hash = SHA-256(prev_hash + canonical JSON of the row's fields).
-- Texts that go into the hash (arguments) are stored as TEXT, not JSONB, so the stored bytes never change.
CREATE TABLE audit_records (
    seq                BIGINT       PRIMARY KEY,
    call_id            UUID         NOT NULL,
    occurred_at        TIMESTAMPTZ  NOT NULL,
    agent_id           VARCHAR(64)  NOT NULL,
    end_user_id        VARCHAR(100),
    tool_name          TEXT         NOT NULL,
    arguments          TEXT         NOT NULL,   -- redacted
    decision           VARCHAR(16)  NOT NULL,   -- ALLOWED | DENIED | ERROR
    decided_by_step    VARCHAR(64),
    rule_id            TEXT,
    policy_version     INTEGER,
    approval_id        UUID,
    approval_state     VARCHAR(24),
    approver           VARCHAR(100),
    edited_arguments   TEXT,                    -- redacted; only when an approver edited the call
    latency_ms         BIGINT       NOT NULL,
    http_status        INTEGER      NOT NULL,
    reason             VARCHAR(500),
    prev_hash          VARCHAR(64)  NOT NULL,
    hash               VARCHAR(64)  NOT NULL
);
CREATE UNIQUE INDEX ux_audit_records_call_id ON audit_records (call_id);
