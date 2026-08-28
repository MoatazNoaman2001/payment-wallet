package com.moataz.paymentwallet.user;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class KycLimits {

    private final KycProfileRepository kycProfileRepository;

    @Value("${limits.kyc.basic:5000}")
    private BigDecimal basic;

    @Value("${limits.kyc.verified:50000}")
    private BigDecimal verified;

    @Value("${limits.kyc.enhanced:0}")
    private BigDecimal enhanced;

    public KycTier effectiveTier(AppUser user) {
        return kycProfileRepository.findById(user.getId())
                .filter(KycProfile::isReviewed)
                .map(KycProfile::getTier)
                .orElse(KycTier.BASIC);
    }

    public Optional<BigDecimal> dailyCeiling(AppUser user) {
        return ceilingFor(effectiveTier(user));
    }

    public Optional<BigDecimal> ceilingFor(KycTier tier) {
        BigDecimal ceiling = switch (tier) {
            case BASIC -> basic;
            case VERIFIED -> verified;
            case ENHANCED -> enhanced;
        };
        return ceiling == null || ceiling.signum() <= 0 ? Optional.empty() : Optional.of(ceiling);
    }
}
