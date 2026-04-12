package com.example.admin.application;

public enum OperatorRole {
    SUPER_ADMIN,
    ACCOUNT_ADMIN,
    AUDITOR;

    public static OperatorRole fromString(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OperatorRole.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
