package com.moataz.paymentwallet.web;

import com.moataz.paymentwallet.account.AccountService;
import com.moataz.paymentwallet.account.dto.AccountResponse;
import com.moataz.paymentwallet.auth.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.math.BigDecimal;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class WebAccountController {

    private final AccountService accountService;
    private final CurrentUser currentUser;

    @GetMapping("/accounts")
    public String accounts(Model model) {
        List<AccountResponse> accounts = accountService.findAllForOwner(currentUser.publicId());

        model.addAttribute("accounts", accounts);
        model.addAttribute("total", accounts.stream()
                .map(AccountResponse::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return "accounts";
    }
}
