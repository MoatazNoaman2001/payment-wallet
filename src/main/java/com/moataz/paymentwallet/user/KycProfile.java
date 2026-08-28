package com.moataz.paymentwallet.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(name = "kyc_profile")
@Getter
@Setter
public class KycProfile {
    @Id
    @Column(name = "user_id")
    private Long userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Column(name = "national_id", nullable = false, length = 20)
    private String nationalId;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(length = 255)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KycTier tier = KycTier.BASIC;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private OffsetDateTime submittedAt = OffsetDateTime.now(java.time.ZoneOffset.UTC);

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private AppUser reviewedBy;

    @Column(name = "review_note", length = 255)
    private String reviewNote;

    public boolean isReviewed() {
        return verifiedAt != null;
    }

    public String maskedNationalId() {
        if (nationalId == null || nationalId.length() <= 4) {
            return "****";
        }
        return "*".repeat(nationalId.length() - 4) + nationalId.substring(nationalId.length() - 4);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof KycProfile other)) return false;
        return Objects.equals(nationalId, other.nationalId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nationalId);
    }
}
