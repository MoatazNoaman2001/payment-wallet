package com.moataz.paymentwallet.web;

import com.moataz.paymentwallet.account.AccountService;
import com.moataz.paymentwallet.account.dto.AccountRow;
import com.moataz.paymentwallet.auth.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.math.BigDecimal;

@Controller
@RequiredArgsConstructor
public class WebAccountController {

    private final AccountService accountService;
    private final CurrentUser currentUser;

    @GetMapping("/accounts")
    public String accounts(@PageableDefault(size = 10) Pageable pageable, Model model) {
        boolean staff = currentUser.isStaff();
        model.addAttribute("staff", staff);

        if (staff) {
            model.addAttribute("owners", accountService.listByOwner(pageable));
            model.addAttribute("settlement", accountService.settlementAccounts());
            return "accounts-staff";
        }

        Page<AccountRow> page = accountService.listRows(currentUser.publicId(), pageable);
        model.addAttribute("page", page);
        model.addAttribute("total", page.getContent().stream()
                .map(AccountRow::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return "accounts";
    }
}
