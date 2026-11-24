-- FR10: per-tool timeout in milliseconds. NULL = use agentgate.resilience.default-timeout from application.yml.
ALTER TABLE tools ADD COLUMN timeout_ms INTEGER;
ALTER TABLE tools ADD CONSTRAINT ck_tools_timeout_ms CHECK (timeout_ms IS NULL OR timeout_ms BETWEEN 100 AND 10000);
