package com.luv2code.paymentwallet.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Maps the app_user table from V1__init.sql.
 *
 * Deliberate choices (each one is an interview question):
 *  - @Getter/@Setter from Lombok, but NEVER @Data on an entity: @Data generates
 *    equals/hashCode/toString over every field, which touches lazy associations
 *    and triggers extra queries (or LazyInitializationException) at random moments.
 *  - equals/hashCode use publicId, a business key assigned in Java before the insert.
 *    Using the database id breaks for unsaved entities: two new users both have
 *    id == null, so a HashSet would treat them as equal.
 *  - Timestamps are OffsetDateTime because the columns are TIMESTAMPTZ.
 *    Instant would map to plain TIMESTAMP and ddl-auto: validate would reject it.
 */
@Entity
@Table(name = "app_user")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // BIGSERIAL
    private Long id;

    /** The id that is safe to expose in URLs and JSON. Never expose the BIGSERIAL. */
    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId = UUID.randomUUID();

    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Enumerated(EnumType.STRING)      // the default is ORDINAL, which stores 0,1,2...
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.PENDING;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version                          // optimistic locking, maps the version column
    private Long version;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    /**
     * Careful: this LAZY is a lie, and it is worth knowing why.
     *
     * This is the INVERSE side of the @OneToOne (mappedBy). The FK lives in
     * kyc_profile, not in app_user, so Hibernate cannot know whether a profile
     * exists without asking — and a proxy for "maybe null" is impossible. So it
     * runs a SELECT on kyc_profile every time an AppUser is loaded, LAZY or not.
     * Confirmed by turning on show-sql: loading one user fires two queries.
     *
     * Fixes, in order of preference:
     *  1. Delete this field. Load KycProfile through KycProfileRepository when you
     *     actually need it — the owning side (KycProfile.user) IS genuinely lazy.
     *  2. Turn on Hibernate bytecode enhancement (hibernate-enhance-maven-plugin),
     *     which rewrites the field access at build time so it can be deferred.
     *
     * Kept here for now because it demonstrates the trap in your own schema and personally for
     * learning about the pitfalls of JPA/Hibernate.
     */
    @OneToOne(mappedBy = "user", fetch = FetchType.LAZY,
              cascade = CascadeType.ALL, orphanRemoval = true)
    private KycProfile kycProfile;

    public void addRole(Role role) {
        roles.add(role);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppUser other)) return false;
        return Objects.equals(publicId, other.publicId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(publicId);
    }

    @Override
    public String toString() {
        // Only non-lazy scalars. Never print roles/kycProfile here.
        return "AppUser{publicId=" + publicId + ", email='" + email + "', status=" + status + '}';
    }
}
