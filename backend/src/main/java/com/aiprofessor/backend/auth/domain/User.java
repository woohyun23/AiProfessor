package com.aiprofessor.backend.auth.domain;

import com.aiprofessor.backend.support.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Getter
@Table(name = "users")
public class User extends BaseEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 10)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    public static User create(
            String email,
            String passwordHash,
            String displayName
    ) {
        User user = new User();

        user.id = UUID.randomUUID();
        user.email = email;
        user.passwordHash = passwordHash;
        user.displayName = displayName;
        user.role = UserRole.USER;
        user.status = UserStatus.ACTIVE;

        return user;
    }

    public void  recordLogin(Instant LoginAt) {
        this.lastLoginAt = Objects.requireNonNull(LoginAt, "LoginAt must not be null");
    }
}
