-- TASK-BE-028a: extend admin_actions with RBAC context columns.
-- TODO(TASK-BE-028b): migrate admin_actions.id to BIGINT AUTO_INCREMENT and
--                    enforce operator_id NOT NULL + FK to admin_operators(id).
-- operator_id is nullable here because existing rows carry no operator FK
-- and the trigger-guarded UPDATE path cannot backfill. New rows (both SUCCESS
-- and DENIED) populate it from OperatorContext. FK constraint + NOT NULL will
-- be re-established in TASK-BE-028b when admin_actions.id is migrated to BIGINT.
-- DENIED is added to the set of outcome values; admin_actions.outcome is already
-- VARCHAR(20), so no ENUM widening is required.

ALTER TABLE admin_actions
    ADD COLUMN operator_id     VARCHAR(36) NULL AFTER actor_role,
    ADD COLUMN permission_used VARCHAR(80) NULL AFTER operator_id;

CREATE INDEX idx_admin_actions_operator_time ON admin_actions (operator_id, started_at);
CREATE INDEX idx_admin_actions_outcome_time  ON admin_actions (outcome, started_at);
