package com.ecommerce.userservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** One line of the admin audit log. Lines are only added, never changed. */
@Entity
@Table(name = "admin_audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Empty when the start-up bootstrap made the change. */
    private Long adminUserId;

    @Column(nullable = false)
    private String adminEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditAction action;

    @Column(nullable = false)
    private Long targetUserId;

    @Column(nullable = false)
    private String targetEmail;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
