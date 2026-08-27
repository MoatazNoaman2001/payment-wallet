package com.moataz.paymentwallet.web;

import com.moataz.paymentwallet.account.AccountRepository;
import com.moataz.paymentwallet.auth.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(basePackages = "com.moataz.paymentwallet.web")
@RequiredArgsConstructor
public class LayoutModelAdvice {

    private final AccountRepository accountRepository;
    private final CurrentUser currentUser;

    @ModelAttribute("hasAccounts")
    public boolean hasAccounts() {
        return currentUser.publicIdIfPresent()
                .map(id -> accountRepository.countByUserPublicId(id) > 0)
                .orElse(false);
    }
}
