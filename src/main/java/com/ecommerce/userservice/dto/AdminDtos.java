package com.ecommerce.userservice.dto;

import com.ecommerce.userservice.entity.AdminAuditLog;
import com.ecommerce.userservice.entity.AuditAction;
import com.ecommerce.userservice.entity.AuthProvider;
import com.ecommerce.userservice.entity.Role;
import com.ecommerce.userservice.entity.User;

import java.time.Instant;
import java.util.List;

/** Request and response shapes of the admin endpoints (/api/v1/user/admin/...). */
public final class AdminDtos {

    private AdminDtos() {
    }

    /** One account in the admin list. The password is never part of it. */
    public record AdminUser(Long id, String name, String email, List<Role> roles, AuthProvider authProvider,
                            boolean locked, boolean admin) {
        public static AdminUser from(User user) {
            return new AdminUser(user.getId(), user.getName(), user.getEmail(), user.getRoles(),
                    user.getAuthProvider(), user.isLocked(), user.getRoles().contains(Role.ROLE_ADMIN));
        }
    }

    /**
     * PATCH body. A field that is missing (null) means "do not change it".
     *
     * @param locked true locks the account, false unlocks it
     * @param admin  true gives ROLE_ADMIN, false takes it away
     */
    public record UserChange(Boolean locked, Boolean admin) {
    }

    public record AuditEntry(Long id, Long adminUserId, String adminEmail, AuditAction action, Long targetUserId,
                             String targetEmail, Instant createdAt) {
        public static AuditEntry from(AdminAuditLog log) {
            return new AuditEntry(log.getId(), log.getAdminUserId(), log.getAdminEmail(), log.getAction(),
                    log.getTargetUserId(), log.getTargetEmail(), log.getCreatedAt());
        }
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }
}
