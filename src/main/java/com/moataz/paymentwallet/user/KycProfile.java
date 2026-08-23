package com.moataz.paymentwallet.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * @OneToOne sharing the primary key with app_user (kyc_profile.user_id is both PK and FK).
 *
 * @MapsId is the piece worth remembering: it tells Hibernate "this entity's id IS the
 * id of the associated AppUser", so you never set userId by hand — set the user and
 * Hibernate copies the id across on flush.
 */
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

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

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
