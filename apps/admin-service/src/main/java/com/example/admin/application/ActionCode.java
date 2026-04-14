package com.example.admin.application;

public enum ActionCode {
    ACCOUNT_LOCK,
    ACCOUNT_UNLOCK,
    SESSION_REVOKE,
    AUDIT_QUERY,
    // TASK-BE-029-2: operator self 2FA enrollment + verify. target_type=OPERATOR.
    OPERATOR_2FA_ENROLL,
    OPERATOR_2FA_VERIFY,
    // TASK-BE-029-3: operator self-login (password + optional 2FA). target_type=OPERATOR,
    // permission_used=auth.login, twofa_used is set per-outcome on the audit row.
    OPERATOR_LOGIN
}
