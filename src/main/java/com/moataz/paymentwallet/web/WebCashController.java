package com.moataz.paymentwallet.web;

import com.moataz.paymentwallet.account.AccountService;
import com.moataz.paymentwallet.account.AccountType;
import com.moataz.paymentwallet.account.dto.AccountRow;
import com.moataz.paymentwallet.auth.CurrentUser;
import com.moataz.paymentwallet.common.error.BusinessRuleException;
import com.moataz.paymentwallet.common.error.NotFoundException;
import com.moataz.paymentwallet.transfer.CashService;
import com.moataz.paymentwallet.transfer.dto.CashRequest;
import com.moataz.paymentwallet.transfer.dto.TransferResponse;
import com.moataz.paymentwallet.web.dto.CashForm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

@Controller
@PreAuthorize("@ownership.isStaff(authentication)")
@RequiredArgsConstructor
public class WebCashController {

    private final CashService cashService;
    private final AccountService accountService;
    private final CurrentUser currentUser;

    @GetMapping("/cash")
    public String desk(@RequestParam(required = false) String account, Model model) {
        model.addAttribute("query", account);
        model.addAttribute("form", new CashForm(account, null, null, UUID.randomUUID().toString()));

        if (account != null && !account.isBlank()) {
            try {
                AccountRow row = accountService.findRowByNumber(account.trim().toUpperCase());
                if (row.type() == AccountType.SYSTEM) {
                    model.addAttribute("lookupError",
                            "That is a settlement account. Enter the customer's account number.");
                } else {
                    model.addAttribute("account", row);
                }
            } catch (NotFoundException ex) {
                model.addAttribute("lookupError", "No account with that number.");
            }
        }
        return "cash";
    }

    @PostMapping("/cash/deposit")
    public String deposit(@Valid @ModelAttribute("form") CashForm form,
                          BindingResult binding, Model model, RedirectAttributes redirect) {
        return handle(form, binding, model, redirect, true);
    }

    @PostMapping("/cash/withdraw")
    public String withdraw(@Valid @ModelAttribute("form") CashForm form,
                           BindingResult binding, Model model, RedirectAttributes redirect) {
        return handle(form, binding, model, redirect, false);
    }

    private String handle(CashForm form, BindingResult binding, Model model,
                          RedirectAttributes redirect, boolean deposit) {
        if (!binding.hasErrors()) {
            try {
                CashRequest request = new CashRequest(form.amount(), form.description());
                TransferResponse done = deposit
                        ? cashService.deposit(form.accountNumber(), currentUser.publicId(),
                                              request, form.idempotencyKey())
                        : cashService.withdraw(form.accountNumber(), currentUser.publicId(),
                                               request, form.idempotencyKey());

                redirect.addAttribute("account", form.accountNumber());
                redirect.addAttribute("done", done.reference());
                return "redirect:/cash";
            } catch (NotFoundException ex) {
                binding.rejectValue("accountNumber", "unknown", ex.getMessage());
            } catch (BusinessRuleException ex) {
                binding.reject("rejected", ex.getMessage());
            }
        }

        model.addAttribute("query", form.accountNumber());
        try {
            model.addAttribute("account", accountService.findRowByNumber(form.accountNumber()));
        } catch (NotFoundException ignored) {
            model.addAttribute("lookupError", "No account with that number.");
        }
        return "cash";
    }
}
