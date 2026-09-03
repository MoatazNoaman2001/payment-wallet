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

    @ModelAttribute("staff")
    public boolean staff() {
        return currentUser.isStaff();
    }

    @ModelAttribute("browsing")
    public boolean browsing() {
        return currentUser.canBrowseCustomers();
    }

    @ModelAttribute("admin")
    public boolean admin() {
        return currentUser.isAdmin();
    }

    @ModelAttribute("needsVerification")
    public boolean needsVerification() {
        // employees are not account holders: prompting an auditor to submit a national id
        // was the same role-list drift, one list short of naming every kind of staff
        if (currentUser.canBrowseCustomers()) {
            return false;
        }
        return currentUser.publicIdIfPresent()
                .map(id -> !kycProfileRepository.findByUserPublicId(id)
                        .filter(KycProfile::isReviewed)
                        .isPresent())
                .orElse(false);
    }
}
