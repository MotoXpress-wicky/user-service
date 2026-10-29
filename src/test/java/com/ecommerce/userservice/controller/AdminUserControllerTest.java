package com.ecommerce.userservice.controller;

import com.ecommerce.userservice.entity.AuditAction;
import com.ecommerce.userservice.entity.RefreshToken;
import com.ecommerce.userservice.entity.Role;
import com.ecommerce.userservice.entity.User;
import com.ecommerce.userservice.repository.AdminAuditLogRepository;
import com.ecommerce.userservice.repository.RefreshTokenRepository;
import com.ecommerce.userservice.repository.UserRepository;
import com.ecommerce.userservice.util.JwtUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real security filters, the real JWT cookie and the real (H2) database.
 * This is where "only ROLE_ADMIN may call /api/v1/user/admin" is proven.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminUserControllerTest {

    private static final String USERS = "/api/v1/user/admin/users";
    private static final String AUDIT = "/api/v1/user/admin/audit-log";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    UserRepository users;
    @Autowired
    AdminAuditLogRepository auditLog;
    @Autowired
    RefreshTokenRepository refreshTokens;
    @Autowired
    JwtUtil jwtUtil;

    @AfterEach
    void clean() {
        auditLog.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
    }

    private User user(String name, String email, Role... roles) {
        return users.save(User.builder().name(name).email(email).password("x").roles(new ArrayList<>(List.of(roles))).build());
    }

    private Cookie cookie(User user) {
        return new Cookie("access_token", jwtUtil.generateToken(user.getId(), user.getName(), user.getEmail(), user.getRoles()));
    }

    // ---------------------------------------------------------------------
    // who may call
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("no login: 401. A customer: 403 with the shared error JSON. Only an admin may call")
    void onlyAdminsMayCall() throws Exception {
        User customer = user("Nimal", "nimal@example.com", Role.ROLE_DEFAULT);
        User admin = user("Admin", "admin@example.com", Role.ROLE_DEFAULT, Role.ROLE_ADMIN);

        mockMvc.perform(get(USERS)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(USERS).cookie(cookie(customer)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.path").value(USERS));
        mockMvc.perform(patch(USERS + "/" + admin.getId()).cookie(cookie(customer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"locked\":true}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(AUDIT).cookie(cookie(customer))).andExpect(status().isForbidden());
        mockMvc.perform(get(USERS).cookie(cookie(admin))).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------------
    // list
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("the list shows newest first, never the password, and q finds part of a name or email")
    void listAndSearch() throws Exception {
        User admin = user("Admin", "admin@example.com", Role.ROLE_DEFAULT, Role.ROLE_ADMIN);
        user("Nimal Perera", "nimal@example.com", Role.ROLE_DEFAULT);
        user("Amara Silva", "amara@shop.lk", Role.ROLE_DEFAULT);

        mockMvc.perform(get(USERS).cookie(cookie(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].email").value("amara@shop.lk"))
                .andExpect(jsonPath("$.content[0].password").doesNotExist())
                .andExpect(jsonPath("$.content[2].admin").value(true));

        mockMvc.perform(get(USERS + "?q=PERERA").cookie(cookie(admin)))
                .andExpect(jsonPath("$.content[*].email", contains("nimal@example.com")));
        mockMvc.perform(get(USERS + "?q=shop.lk").cookie(cookie(admin)))
                .andExpect(jsonPath("$.content[*].email", contains("amara@shop.lk")));
        // % and _ are taken as the characters themselves, not as wildcards
        mockMvc.perform(get(USERS + "?q=%25").cookie(cookie(admin))).andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get(USERS + "?q=_").cookie(cookie(admin))).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("a page is at most 100 accounts")
    void pagesAreLimited() throws Exception {
        User admin = user("Admin", "admin@example.com", Role.ROLE_ADMIN);

        mockMvc.perform(get(USERS + "?size=100000").cookie(cookie(admin)))
                .andExpect(jsonPath("$.size").value(100));
    }

    // ---------------------------------------------------------------------
    // lock and unlock
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("lock: the account is locked, its refresh tokens end, and the audit log says who did it")
    void lockAnAccount() throws Exception {
        User admin = user("Admin", "admin@example.com", Role.ROLE_ADMIN);
        User target = user("Nimal", "nimal@example.com", Role.ROLE_DEFAULT);
        refreshTokens.save(RefreshToken.builder().tokenHash("a".repeat(64)).user(target)
                .expiresAt(Instant.now().plusSeconds(3600)).build());

        mockMvc.perform(patch(USERS + "/" + target.getId()).cookie(cookie(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"locked\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true));

        assertThat(users.findById(target.getId()).orElseThrow().isLocked()).isTrue();
        assertThat(refreshTokens.findAll()).allSatisfy(t -> assertThat(t.getRevokedAt()).isNotNull());
        assertThat(auditLog.findAll()).singleElement().satisfies(entry -> {
            assertThat(entry.getAction()).isEqualTo(AuditAction.LOCK);
            assertThat(entry.getAdminUserId()).isEqualTo(admin.getId());
            assertThat(entry.getAdminEmail()).isEqualTo("admin@example.com");
            assertThat(entry.getTargetUserId()).isEqualTo(target.getId());
            assertThat(entry.getTargetEmail()).isEqualTo("nimal@example.com");
        });

        mockMvc.perform(patch(USERS + "/" + target.getId()).cookie(cookie(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"locked\":false}"))
                .andExpect(jsonPath("$.locked").value(false));
        assertThat(auditLog.findAll()).extracting("action").containsExactlyInAnyOrder(AuditAction.LOCK, AuditAction.UNLOCK);
    }

    @Test
    @DisplayName("a change that changes nothing is not written to the audit log")
    void noChangeNoAuditLine() throws Exception {
        User admin = user("Admin", "admin@example.com", Role.ROLE_ADMIN);
        User target = user("Nimal", "nimal@example.com", Role.ROLE_DEFAULT);

        mockMvc.perform(patch(USERS + "/" + target.getId()).cookie(cookie(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"locked\":false,\"admin\":false}"))
                .andExpect(status().isOk());

        assertThat(auditLog.count()).isZero();
    }

    // ---------------------------------------------------------------------
    // grant and revoke admin
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("grant admin and revoke admin change the roles and are written to the audit log")
    void grantAndRevokeAdmin() throws Exception {
        User admin = user("Admin", "admin@example.com", Role.ROLE_ADMIN);
        User target = user("Nimal", "nimal@example.com", Role.ROLE_DEFAULT);

        mockMvc.perform(patch(USERS + "/" + target.getId()).cookie(cookie(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"admin\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admin").value(true))
                .andExpect(jsonPath("$.roles", hasItem("ROLE_ADMIN")))
                .andExpect(jsonPath("$.roles", hasItem("ROLE_DEFAULT")));
        assertThat(users.findById(target.getId()).orElseThrow().getRoles()).contains(Role.ROLE_ADMIN);

        mockMvc.perform(patch(USERS + "/" + target.getId()).cookie(cookie(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"admin\":false}"))
                .andExpect(jsonPath("$.admin").value(false));
        assertThat(users.findById(target.getId()).orElseThrow().getRoles()).doesNotContain(Role.ROLE_ADMIN);

        mockMvc.perform(get(AUDIT).cookie(cookie(admin)))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].action").value("REVOKE_ADMIN"))
                .andExpect(jsonPath("$.content[1].action").value("GRANT_ADMIN"));
    }

    // ---------------------------------------------------------------------
    // the rules
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("an admin cannot change their own account")
    void notYourself() throws Exception {
        User admin = user("Admin", "admin@example.com", Role.ROLE_ADMIN);

        mockMvc.perform(patch(USERS + "/" + admin.getId()).cookie(cookie(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"locked\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_RULE_VIOLATION"));
        mockMvc.perform(patch(USERS + "/" + admin.getId()).cookie(cookie(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"admin\":false}"))
                .andExpect(status().isConflict());

        assertThat(users.findById(admin.getId()).orElseThrow().isLocked()).isFalse();
        assertThat(auditLog.count()).isZero();
    }

    @Test
    @DisplayName("an old token of a demoted admin does not work any more, even though it still says ADMIN")
    void staleTokenOfADemotedAdmin() throws Exception {
        User admin = user("Admin", "admin@example.com", Role.ROLE_ADMIN);
        User target = user("Nimal", "nimal@example.com", Role.ROLE_DEFAULT);
        Cookie oldToken = cookie(admin);

        admin.setRoles(new ArrayList<>(List.of(Role.ROLE_DEFAULT)));
        users.save(admin);

        mockMvc.perform(patch(USERS + "/" + target.getId()).cookie(oldToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"locked\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_RULE_VIOLATION"));
        assertThat(users.findById(target.getId()).orElseThrow().isLocked()).isFalse();
    }

    @Test
    @DisplayName("a locked admin cannot act, even with a token that has not expired")
    void lockedAdminCannotAct() throws Exception {
        User admin = user("Admin", "admin@example.com", Role.ROLE_ADMIN);
        User target = user("Nimal", "nimal@example.com", Role.ROLE_DEFAULT);
        Cookie token = cookie(admin);

        admin.setLocked(true);
        users.save(admin);

        mockMvc.perform(patch(USERS + "/" + target.getId()).cookie(token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"admin\":true}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("unknown account: 404. Empty body: 400")
    void unknownAndEmpty() throws Exception {
        User admin = user("Admin", "admin@example.com", Role.ROLE_ADMIN);
        User target = user("Nimal", "nimal@example.com", Role.ROLE_DEFAULT);

        mockMvc.perform(patch(USERS + "/999999").cookie(cookie(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"locked\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(patch(USERS + "/" + target.getId()).cookie(cookie(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
