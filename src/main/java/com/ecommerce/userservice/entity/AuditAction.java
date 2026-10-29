package com.ecommerce.userservice.entity;

/** What an admin did to an account. Saved in admin_audit_log. */
public enum AuditAction {
    LOCK,
    UNLOCK,
    GRANT_ADMIN,
    REVOKE_ADMIN
}
