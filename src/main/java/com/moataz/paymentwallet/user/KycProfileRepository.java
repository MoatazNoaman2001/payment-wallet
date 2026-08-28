package com.moataz.paymentwallet.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface KycProfileRepository extends JpaRepository<KycProfile, Long> {

    @Query("select k from KycProfile k join k.user u where u.publicId = :publicId")
    Optional<KycProfile> findByUserPublicId(UUID publicId);

    boolean existsByNationalId(String nationalId);

    @Query("select count(k) from KycProfile k where k.verifiedAt is null")
    long countAwaitingReview();
}
