package com.moataz.paymentwallet.user.dto;

import com.moataz.paymentwallet.user.KycProfile;
import com.moataz.paymentwallet.user.KycTier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record KycProfileResponse(
        String nationalId,
        LocalDate dateOfBirth,
        String address,
        KycTier tier,
        boolean reviewed,
        OffsetDateTime submittedAt,
        OffsetDateTime verifiedAt,
        String reviewedBy,
        String reviewNote,
        BigDecimal dailyCeiling
) {
    public static KycProfileResponse from(KycProfile profile, BigDecimal dailyCeiling) {
        return new KycProfileResponse(
                profile.maskedNationalId(),
                profile.getDateOfBirth(),
                profile.getAddress(),
                profile.getTier(),
                profile.isReviewed(),
                profile.getSubmittedAt(),
                profile.getVerifiedAt(),
                profile.getReviewedBy() == null ? null : profile.getReviewedBy().getFullName(),
                profile.getReviewNote(),
                dailyCeiling);
    }
}
