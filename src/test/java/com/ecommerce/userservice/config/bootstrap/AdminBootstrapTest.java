package com.ecommerce.userservice.config.bootstrap;

import com.ecommerce.userservice.entity.AdminAuditLog;
import com.ecommerce.userservice.entity.AuditAction;
import com.ecommerce.userservice.entity.AuthProvider;
import com.ecommerce.userservice.entity.Role;
import com.ecommerce.userservice.entity.User;
import com.ecommerce.userservice.repository.AdminAuditLogRepository;
import com.ecommerce.userservice.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The first admin. Uses the real database (H2), so the array_contains query is really run. */
@SpringBootTest
class AdminBootstrapTest {

    @Autowired
    UserRepository users;
    @Autowired
    AdminAuditLogRepository auditLog;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    TransactionTemplate transaction;

    @AfterEach
    void clean() {
        auditLog.deleteAll();
        users.deleteAll();
    }

    private void run(String email, String password) {
        new AdminBootstrap(new AdminBootstrapProperties(email, password, "Administrator"),
                users, auditLog, passwordEncoder, transaction).run(null);
    }

    private User save(String email, Role... roles) {
        return users.save(User.builder().name("Someone").email(email).password("old-hash")
                .roles(new ArrayList<>(List.of(roles))).build());
    }

    @Test
    @DisplayName("no email set: nothing happens")
    void noEmail() {
        run("", "a-long-password");
        run(null, null);

        assertThat(users.count()).isZero();
        assertThat(auditLog.count()).isZero();
    }

    @Test
    @DisplayName("the account exists: it gets ROLE_ADMIN, its password is not touched, and the audit log says bootstrap")
    void promotesAnExistingAccount() {
        User existing = save("boss@example.com", Role.ROLE_DEFAULT);

        run("boss@example.com", "a-long-password");

        User after = users.findById(existing.getId()).orElseThrow();
        assertThat(after.getRoles()).containsExactlyInAnyOrder(Role.ROLE_DEFAULT, Role.ROLE_ADMIN);
        assertThat(after.getPassword()).isEqualTo("old-hash");
        assertThat(auditLog.findAll()).singleElement().satisfies((AdminAuditLog entry) -> {
            assertThat(entry.getAction()).isEqualTo(AuditAction.GRANT_ADMIN);
            assertThat(entry.getAdminEmail()).isEqualTo("bootstrap");
            assertThat(entry.getAdminUserId()).isNull();
            assertThat(entry.getTargetEmail()).isEqualTo("boss@example.com");
        });
    }

    @Test
    @DisplayName("the account does not exist and a password is set: it is created as an admin with a hashed password")
    void createsTheAccount() {
        run("boss@example.com", "a-long-password");

        User created = users.findByEmail("boss@example.com").orElseThrow();
        assertThat(created.getRoles()).contains(Role.ROLE_ADMIN);
        assertThat(created.getAuthProvider()).isEqualTo(AuthProvider.LOCAL);
        assertThat(created.getPassword()).isNotEqualTo("a-long-password");
        assertThat(passwordEncoder.matches("a-long-password", created.getPassword())).isTrue();
        assertThat(auditLog.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("the account does not exist and the password is missing or too short: nothing is created")
    void needsAGoodPassword() {
        run("boss@example.com", "");
        run("boss@example.com", "short");
        run("boss@example.com", null);

        assertThat(users.count()).isZero();
    }

    @Test
    @DisplayName("an admin already exists: nothing changes, so the setting can stay in place")
    void skipsWhenAnAdminExists() {
        save("first@example.com", Role.ROLE_ADMIN);
        User other = save("boss@example.com", Role.ROLE_DEFAULT);

        run("boss@example.com", "a-long-password");

        assertThat(users.findById(other.getId()).orElseThrow().getRoles()).doesNotContain(Role.ROLE_ADMIN);
        assertThat(auditLog.count()).isZero();
    }

    @Test
    @DisplayName("running it twice is the same as running it once")
    void idempotent() {
        run("boss@example.com", "a-long-password");
        run("boss@example.com", "a-long-password");

        assertThat(users.count()).isEqualTo(1);
        assertThat(auditLog.count()).isEqualTo(1);
    }
}
