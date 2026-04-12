package com.example.account.domain.status;

public enum StatusChangeReason {
    ADMIN_LOCK,
    AUTO_DETECT,
    PASSWORD_FAILURE_THRESHOLD,
    ADMIN_UNLOCK,
    USER_RECOVERY,
    DORMANT_365D,
    USER_LOGIN,
    USER_REQUEST,
    ADMIN_DELETE,
    REGULATED_DELETION,
    WITHIN_GRACE_PERIOD
}
