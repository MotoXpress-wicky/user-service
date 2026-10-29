package com.ecommerce.userservice.controller;

import com.ecommerce.userservice.dto.AdminDtos.AdminUser;
import com.ecommerce.userservice.dto.AdminDtos.AuditEntry;
import com.ecommerce.userservice.dto.AdminDtos.PageResponse;
import com.ecommerce.userservice.dto.AdminDtos.UserChange;
import com.ecommerce.userservice.dto.CurrentUser;
import com.ecommerce.userservice.service.AdminUserService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin: accounts and the audit log. SecurityConfig lets only ROLE_ADMIN reach /api/v1/user/admin/**. */
@RestController
@RequestMapping("/api/v1/user/admin")
public class AdminUserController {

    private final AdminUserService service;

    public AdminUserController(AdminUserService service) {
        this.service = service;
    }

    /** Filters: q (part of the name or email), page, size. Newest first. */
    @GetMapping("/users")
    public PageResponse<AdminUser> users(@RequestParam(required = false) String q,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return service.list(q, page, size);
    }

    /** Lock or unlock an account, and give or take ROLE_ADMIN. Send only the fields to change. */
    @PatchMapping("/users/{id}")
    public AdminUser change(@AuthenticationPrincipal CurrentUser admin,
                            @PathVariable Long id,
                            @Valid @RequestBody UserChange change) {
        return service.change(admin, id, change);
    }

    @GetMapping("/audit-log")
    public PageResponse<AuditEntry> auditLog(@RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return service.auditLog(page, size);
    }
}
