package com.ecommerce.userservice.config.bootstrap;

import com.ecommerce.userservice.entity.AdminAuditLog;
import com.ecommerce.userservice.entity.AuditAction;
import com.ecommerce.userservice.entity.AuthProvider;
import com.ecommerce.userservice.entity.Role;
import com.ecommerce.userservice.entity.User;
import com.ecommerce.userservice.repository.AdminAuditLogRepository;
import com.ecommerce.userservice.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Makes sure a new system has a first admin, because nobody can promote anyone before that.
 * <p>
 * On every start: if admin.bootstrap.email is set AND no account has ROLE_ADMIN yet:
 * <ul>
 *   <li>the account exists: it gets ROLE_ADMIN (its password is not touched);</li>
 *   <li>the account does not exist and a password is set: it is created with ROLE_ADMIN;</li>
 *   <li>otherwise: a warning is logged and nothing changes.</li>
 * </ul>
 * Once any admin exists this does nothing, so it is safe to leave the setting in place.
 * <p>
 * It must never stop the service from starting. If two copies start at the same moment, both may try to create
 * the account and one hits the unique email rule. That is not an error: the other copy did the work.
 */
@Slf4j
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final AdminBootstrapProperties properties;
    private final UserRepository userRepository;
    private final AdminAuditLogRepository auditLogRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transaction;

    public AdminBootstrap(AdminBootstrapProperties properties,
                          UserRepository userRepository,
                          AdminAuditLogRepository auditLogRepository,
                          PasswordEncoder passwordEncoder,
                          TransactionTemplate transaction) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
        this.passwordEncoder = passwordEncoder;
        this.transaction = transaction;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = properties.email();
        if (email == null || email.isBlank()) {
            return;
        }
        try {
            transaction.executeWithoutResult(status -> bootstrap(email.trim()));
        } catch (DataIntegrityViolationException e) {
            log.info("Admin bootstrap: another copy of the service created the account at the same time. Nothing to do.");
        }
    }

    private void bootstrap(String email) {
        if (userRepository.existsAdmin()) {
            log.info("Admin bootstrap skipped: an admin already exists");
            return;
        }

        Optional<User> existing = userRepository.findByEmail(email);
        User admin;
        if (existing.isPresent()) {
            admin = existing.get();
            List<Role> roles = new ArrayList<>(admin.getRoles());
            roles.add(Role.ROLE_ADMIN);
            admin.setRoles(roles);
            userRepository.save(admin);
            log.info("Admin bootstrap: user id {} is now an admin", admin.getId());
        } else {
            String password = properties.password();
            if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
                log.warn("Admin bootstrap: the account does not exist and admin.bootstrap.password is missing or "
                        + "shorter than {} characters. Register the account first and restart, or set the password.",
                        MIN_PASSWORD_LENGTH);
                return;
            }
            String name = properties.name() == null || properties.name().isBlank() ? "Administrator" : properties.name();
            admin = userRepository.save(User.builder()
                    .name(name)
                    .email(email)
                    .password(passwordEncoder.encode(password))
                    .authProvider(AuthProvider.LOCAL)
                    .roles(new ArrayList<>(List.of(Role.ROLE_DEFAULT, Role.ROLE_ADMIN)))
                    .build());
            log.info("Admin bootstrap: created the admin account with user id {}", admin.getId());
        }

        auditLogRepository.save(AdminAuditLog.builder()
                .adminEmail("bootstrap")
                .action(AuditAction.GRANT_ADMIN)
                .targetUserId(admin.getId())
                .targetEmail(admin.getEmail())
                .build());
    }
}
