package com.ecommerce.userservice.service;

import com.ecommerce.userservice.dto.AdminDtos.AdminUser;
import com.ecommerce.userservice.dto.AdminDtos.AuditEntry;
import com.ecommerce.userservice.dto.AdminDtos.PageResponse;
import com.ecommerce.userservice.dto.AdminDtos.UserChange;
import com.ecommerce.userservice.dto.CurrentUser;
import com.ecommerce.userservice.entity.AdminAuditLog;
import com.ecommerce.userservice.entity.AuditAction;
import com.ecommerce.userservice.entity.Role;
import com.ecommerce.userservice.entity.User;
import com.ecommerce.userservice.exception.AdminRuleException;
import com.ecommerce.userservice.exception.UserNotFoundException;
import com.ecommerce.userservice.repository.AdminAuditLogRepository;
import com.ecommerce.userservice.repository.RefreshTokenRepository;
import com.ecommerce.userservice.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * What an admin may do with accounts: look at them, lock or unlock them, give or take ROLE_ADMIN.
 * Every change is written to the audit log in the same transaction.
 * <p>
 * Rules that keep the system from locking itself out:
 * <ul>
 *   <li>an admin cannot change their own account;</li>
 *   <li>the acting admin must still be an unlocked admin in the database (a token can be up to 15 minutes old).</li>
 * </ul>
 * Because of these two rules there is always at least one admin left who can act.
 */
@Slf4j
@Service
public class AdminUserService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final AdminAuditLogRepository auditLogRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    public AdminUserService(UserRepository userRepository,
                            AdminAuditLogRepository auditLogRepository,
                            RefreshTokenRepository refreshTokenRepository) {
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /** Newest accounts first. q matches part of the name or email (wildcards in q are taken literally). */
    @Transactional(readOnly = true)
    public PageResponse<AdminUser> list(String q, Integer page, Integer size) {
        Specification<User> spec = (root, query, cb) -> {
            if (q == null || q.isBlank()) {
                return cb.conjunction();
            }
            String pattern = "%" + escapeLike(q.trim().toLowerCase()) + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("email")), pattern, '\\'),
                    cb.like(cb.lower(root.get("name")), pattern, '\\'));
        };
        Page<User> result = userRepository.findAll(spec, pageable(page, size, Sort.by(Sort.Direction.DESC, "id")));
        return toResponse(result, AdminUser::from);
    }

    @Transactional
    public AdminUser change(CurrentUser actor, Long targetId, UserChange change) {
        if (change.locked() == null && change.admin() == null) {
            throw new IllegalArgumentException("Nothing to change. Send locked, admin or both.");
        }

        User admin = userRepository.findById(actor.getId()).orElseThrow(() -> new UserNotFoundException(actor.getId()));
        if (admin.isLocked() || !admin.getRoles().contains(Role.ROLE_ADMIN)) {
            // The token still says ADMIN, but the database says no (changed in the last minutes).
            throw new AdminRuleException("Your account is not an active admin any more.");
        }
        if (admin.getId().equals(targetId)) {
            throw new AdminRuleException("You cannot change your own account.");
        }

        User target = userRepository.findById(targetId).orElseThrow(() -> new UserNotFoundException(targetId));

        if (change.locked() != null && change.locked() != target.isLocked()) {
            target.setLocked(change.locked());
            if (change.locked()) {
                // A locked account must not keep a way to get new access tokens.
                refreshTokenRepository.revokeAllForUser(target.getId(), Instant.now());
            }
            record(admin, target, change.locked() ? AuditAction.LOCK : AuditAction.UNLOCK);
        }

        if (change.admin() != null) {
            boolean isAdmin = target.getRoles().contains(Role.ROLE_ADMIN);
            if (change.admin() && !isAdmin) {
                List<Role> roles = new ArrayList<>(target.getRoles());
                roles.add(Role.ROLE_ADMIN);
                target.setRoles(roles);
                record(admin, target, AuditAction.GRANT_ADMIN);
            } else if (!change.admin() && isAdmin) {
                List<Role> roles = new ArrayList<>(target.getRoles());
                roles.remove(Role.ROLE_ADMIN);
                target.setRoles(roles);
                record(admin, target, AuditAction.REVOKE_ADMIN);
            }
        }

        userRepository.save(target);
        return AdminUser.from(target);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditEntry> auditLog(Integer page, Integer size) {
        Page<AdminAuditLog> result = auditLogRepository.findAll(
                pageable(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return toResponse(result, AuditEntry::from);
    }

    private void record(User admin, User target, AuditAction action) {
        auditLogRepository.save(AdminAuditLog.builder()
                .adminUserId(admin.getId())
                .adminEmail(admin.getEmail())
                .action(action)
                .targetUserId(target.getId())
                .targetEmail(target.getEmail())
                .build());
        log.info("Admin {} did {} on user {}", admin.getId(), action, target.getId());
    }

    private static PageRequest pageable(Integer page, Integer size, Sort sort) {
        int safePage = page == null ? 0 : Math.max(page, 0);
        int safeSize = size == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize, sort);
    }

    private static <E, T> PageResponse<T> toResponse(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    /** % and _ are wildcards in LIKE. A person who types them means the characters themselves. */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
