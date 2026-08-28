package com.moataz.paymentwallet.web;

import com.moataz.paymentwallet.account.AccountRepository;
import com.moataz.paymentwallet.auth.CurrentUser;
import com.moataz.paymentwallet.user.KycProfile;
import com.moataz.paymentwallet.user.KycProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(basePackages = "com.moataz.paymentwallet.web")
@RequiredArgsConstructor
public class LayoutModelAdvice {

    private final AccountRepository accountRepository;
    private final KycProfileRepository kycProfileRepository;
    private final CurrentUser currentUser;

    @ModelAttribute("hasAccounts")
    public boolean hasAccounts() {
        return currentUser.publicIdIfPresent()
                .map(id -> accountRepository.countByUserPublicId(id) > 0)
                .orElse(false);
    }

    @ModelAttribute("needsVerification")
    public boolean needsVerification() {
        if (currentUser.isStaff() || currentUser.canApproveIdentity()) {
            return false;
        }
        return currentUser.publicIdIfPresent()
                .map(id -> !kycProfileRepository.findByUserPublicId(id)
                        .filter(KycProfile::isReviewed)
                        .isPresent())
                .orElse(false);
    }
}
