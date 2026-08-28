package com.moataz.paymentwallet.web;

import com.moataz.paymentwallet.account.AccountService;
import com.moataz.paymentwallet.account.AccountType;
import com.moataz.paymentwallet.account.dto.OpenAccountRequest;
import com.moataz.paymentwallet.auth.CurrentUser;
import com.moataz.paymentwallet.common.error.BusinessRuleException;
import com.moataz.paymentwallet.common.error.DuplicateResourceException;
import com.moataz.paymentwallet.user.UserService;
import com.moataz.paymentwallet.user.dto.RegisterUserRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class WebUserController {

    private final UserService userService;
    private final AccountService accountService;
    private final CurrentUser currentUser;

    @GetMapping("/register")
    public String registerForm(Model model) {
        model.addAttribute("form", new RegisterUserRequest(null, null, null, null));
        return "register";
    }

    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("form") RegisterUserRequest form,
                           BindingResult binding,
                           RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            return "register";
        }
        try {
            userService.register(form);
        } catch (DuplicateResourceException ex) {
            String field = ex.getMessage().toLowerCase().contains("phone") ? "phone" : "email";
            binding.rejectValue(field, "duplicate", "Already registered");
            return "register";
        }
        redirect.addAttribute("registered", "");
        return "redirect:/login";
    }

    @PreAuthorize("@ownership.isStaff(authentication)")
    @GetMapping("/users")
    public String users(@PageableDefault(size = 15) Pageable pageable, Model model) {
        model.addAttribute("page", userService.list(pageable));
        return "users";
    }

    @GetMapping("/users/{publicId}")
    public String user(@PathVariable UUID publicId, Model model) {
        requireSelfOrStaff(publicId);
        model.addAttribute("user", userService.findByPublicId(publicId));
        model.addAttribute("accounts", accountService.findAllForOwner(publicId));
        model.addAttribute("types", new AccountType[]{
                AccountType.WALLET, AccountType.SAVINGS, AccountType.MERCHANT});
        model.addAttribute("staff", currentUser.isStaff());
        model.addAttribute("admin", currentUser.isAdmin());
        model.addAttribute("canFreeze", currentUser.canFreeze());
        return "user";
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/users/{publicId}/activate")
    public String activate(@PathVariable UUID publicId, RedirectAttributes redirect) {
        userService.activate(publicId);
        redirect.addAttribute("activated", "");
        return "redirect:/users/" + publicId;
    }

    @PreAuthorize("@ownership.canFreeze(authentication)")
    @PostMapping("/users/{publicId}/accounts/{accountNumber}/status")
    public String changeAccountStatus(@PathVariable UUID publicId,
                                      @PathVariable String accountNumber,
                                      @RequestParam String action,
                                      @RequestParam(required = false) String reason,
                                      RedirectAttributes redirect) {
        try {
            if ("freeze".equals(action)) {
                accountService.freeze(accountNumber, reason, currentUser.publicId());
                redirect.addAttribute("frozen", accountNumber);
            } else {
                accountService.unfreeze(accountNumber, reason, currentUser.publicId());
                redirect.addAttribute("unfrozen", accountNumber);
            }
        } catch (BusinessRuleException ex) {
            redirect.addAttribute("error", ex.getMessage());
        }
        return "redirect:/users/" + publicId;
    }

    @PostMapping("/users/{publicId}/accounts")
    public String openAccount(@PathVariable UUID publicId,
                              @RequestParam String currencyCode,
                              @RequestParam AccountType type,
                              @RequestParam(required = false) BigDecimal dailyLimit,
                              RedirectAttributes redirect) {
        requireSelfOrStaff(publicId);
        try {
            accountService.open(new OpenAccountRequest(publicId, currencyCode, type, dailyLimit),
                                publicId, currentUser.publicId());
            redirect.addAttribute("opened", "");
        } catch (BusinessRuleException ex) {
            redirect.addAttribute("error", ex.getMessage());
        }
        return "redirect:/users/" + publicId;
    }

    private void requireSelfOrStaff(UUID publicId) {
        if (!currentUser.isStaff() && !publicId.equals(currentUser.publicId())) {
            throw new AccessDeniedException("Not your profile");
        }
    }
}
