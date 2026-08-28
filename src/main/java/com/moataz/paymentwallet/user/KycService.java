package com.moataz.paymentwallet.user;

import com.moataz.paymentwallet.common.error.BusinessRuleException;
import com.moataz.paymentwallet.common.error.DuplicateResourceException;
import com.moataz.paymentwallet.common.error.NotFoundException;
import com.moataz.paymentwallet.user.dto.KycProfileResponse;
import com.moataz.paymentwallet.user.dto.KycSubmissionRequest;
import com.moataz.paymentwallet.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KycService {

    private static final int MINIMUM_AGE = 18;

    private final AppUserRepository userRepository;
    private final KycProfileRepository kycProfileRepository;
    private final KycLimits kycLimits;

    @Transactional
    public KycProfileResponse submit(UUID subjectPublicId, KycSubmissionRequest request) {
        AppUser subject = user(subjectPublicId);

        if (Period.between(request.dateOfBirth(), LocalDate.now()).getYears() < MINIMUM_AGE) {
            throw new BusinessRuleException(
                    "Account holders must be at least " + MINIMUM_AGE + " years old");
        }

        KycProfile profile = kycProfileRepository.findById(subject.getId()).orElse(null);
        if (profile != null && profile.isReviewed()) {
            throw new BusinessRuleException(
                    "Identity details have already been verified and can no longer be edited here: "
                    + "ask compliance to review a change");
        }
        if (profile == null) {
            profile = new KycProfile();
            profile.setUser(subject);
            profile.setTier(KycTier.BASIC);
        }
        if (!request.nationalId().equals(profile.getNationalId())
                && kycProfileRepository.existsByNationalId(request.nationalId())) {
            throw new DuplicateResourceException(
                    "That national id already belongs to another account holder");
        }

        profile.setNationalId(request.nationalId());
        profile.setDateOfBirth(request.dateOfBirth());
        profile.setAddress(request.address());
        profile.setSubmittedAt(OffsetDateTime.now(ZoneOffset.UTC));

        KycProfile saved = kycProfileRepository.save(profile);
        return response(saved, subject);
    }

    /**
     * Accepting someone's identity and letting them hold money is one decision, so it is one
     * call: the tier and the activation are written together or not at all.
     */
    @Transactional
    public UserResponse approve(UUID subjectPublicId, KycTier tier, String note, UUID reviewerPublicId) {
        AppUser subject = user(subjectPublicId);
        AppUser reviewer = user(reviewerPublicId);

        if (subject.getPublicId().equals(reviewer.getPublicId())) {
            throw new BusinessRuleException("You cannot approve your own identity");
        }

        KycProfile profile = kycProfileRepository.findById(subject.getId())
                .orElseThrow(() -> new BusinessRuleException(
                        "Nothing to review: " + subject.getFullName()
                        + " has not submitted identity details yet"));

        profile.setTier(tier);
        profile.setVerifiedAt(OffsetDateTime.now(ZoneOffset.UTC));
        profile.setReviewedBy(reviewer);
        profile.setReviewNote(note);
        subject.setStatus(UserStatus.ACTIVE);

        return UserResponse.from(subject);
    }

    @Transactional(readOnly = true)
    public Optional<KycProfileResponse> find(UUID subjectPublicId) {
        AppUser subject = user(subjectPublicId);
        return kycProfileRepository.findById(subject.getId())
                .map(profile -> response(profile, subject));
    }

    private KycProfileResponse response(KycProfile profile, AppUser subject) {
        return KycProfileResponse.from(profile,
                kycLimits.ceilingFor(profile.isReviewed() ? profile.getTier() : KycTier.BASIC)
                         .orElse(null));
    }

    private AppUser user(UUID publicId) {
        return userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new NotFoundException("No user with id " + publicId));
    }
}
