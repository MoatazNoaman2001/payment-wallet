package com.moataz.paymentwallet.web;

import com.moataz.paymentwallet.account.AccountService;
import com.moataz.paymentwallet.account.AccountType;
import com.moataz.paymentwallet.account.dto.OpenAccountRequest;
import com.moataz.paymentwallet.auth.CurrentUser;
import com.moataz.paymentwallet.common.error.BusinessRuleException;
import com.moataz.paymentwallet.common.error.DuplicateResourceException;
import com.moataz.paymentwallet.user.KycService;
import com.moataz.paymentwallet.user.KycTier;
import com.moataz.paymentwallet.user.UserService;
import com.moataz.paymentwallet.user.dto.KycSubmissionRequest;
import com.moataz.paymentwallet.user.dto.RegisterUserRequest;
import com.moataz.paymentwallet.web.dto.CounterRegistrationForm;
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
    private final KycService kycService;
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

    @PreAuthorize("@ownership.canBrowseCustomers(authentication)")
    @GetMapping("/users")
    public String users(@PageableDefault(size = 15) Pageable pageable, Model model) {
        model.addAttribute("page", userService.list(pageable));
        return "users";
    }

    @PreAuthorize("@ownership.isStaff(authentication)")
    @GetMapping("/users/new")
    public String counterForm(Model model) {
        model.addAttribute("form", CounterRegistrationForm.empty());
        return "user-new";
    }

    @PreAuthorize("@ownership.isStaff(authentication)")
    @PostMapping("/users/new")
    public String registerAtCounter(@Valid @ModelAttribute("form") CounterRegistrationForm form,
                                    BindingResult binding,
                                    RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            return "user-new";
        }
        UUID created;
        try {
            created = userService.registerAtCounter(form.toRequest(), currentUser.publicId()).publicId();
        } catch (DuplicateResourceException ex) {
            String message = ex.getMessage().toLowerCase();
            String field = message.contains("phone") ? "phone"
                         : message.contains("national") ? "nationalId" : "email";
            binding.rejectValue(field, "duplicate", "Already registered");
            return "user-new";
        } catch (BusinessRuleException ex) {
            binding.reject("rule", ex.getMessage());
            return "user-new";
        }
        redirect.addAttribute("registered", "");
        return "redirect:/users/" + created;
    }

    @GetMapping("/users/{publicId}")
    public String user(@PathVariable UUID publicId, Model model) {
        requireCanView(publicId);
        model.addAttribute("user", userService.findByPublicId(publicId));
        model.addAttribute("accounts", accountService.findAllForOwner(publicId));
        model.addAttribute("kyc", kycService.find(publicId).orElse(null));
        model.addAttribute("registrar", userService.registrarNameOf(publicId));
        model.addAttribute("tiers", KycTier.values());
        model.addAttribute("types", new AccountType[]{
                AccountType.WALLET, AccountType.SAVINGS, AccountType.MERCHANT});
        model.addAttribute("canFreeze", currentUser.canFreeze());
        model.addAttribute("canApprove", currentUser.canApproveIdentity());
        model.addAttribute("canOpen", canAct(publicId));
        return "user";
    }

    @GetMapping("/verify")
    public String verifyForm(Model model) {
        UUID me = currentUser.publicId();
        model.addAttribute("user", userService.findByPublicId(me));
        model.addAttribute("kyc", kycService.find(me).orElse(null));
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new KycSubmissionRequest(null, null, null));
        }
        return "verify";
    }

    @PostMapping("/verify")
    public String verify(@Valid @ModelAttribute("form") KycSubmissionRequest form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            return verifyForm(model);
        }
        try {
            kycService.submit(currentUser.publicId(), form);
        } catch (DuplicateResourceException | BusinessRuleException ex) {
            binding.reject("rule", ex.getMessage());
            return verifyForm(model);
        }
        redirect.addAttribute("submitted", "");
        return "redirect:/verify";
    }

    @PreAuthorize("@ownership.isSelf(#publicId, authentication)")
    @PostMapping("/users/{publicId}/kyc")
    public String submitKyc(@PathVariable UUID publicId,
                            @Valid @ModelAttribute KycSubmissionRequest form,
                            BindingResult binding,
                            RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            redirect.addAttribute("error", "Check the identity details and try again");
            return "redirect:/users/" + publicId;
        }
        try {
            kycService.submit(publicId, form);
            redirect.addAttribute("kycSubmitted", "");
        } catch (DuplicateResourceException | BusinessRuleException ex) {
            redirect.addAttribute("error", ex.getMessage());
        }
        return "redirect:/users/" + publicId;
    }

    @PreAuthorize("@ownership.canApproveIdentity(authentication)")
    @PostMapping("/users/{publicId}/kyc-review")
    public String review(@PathVariable UUID publicId,
                         @RequestParam KycTier tier,
                         @RequestParam(required = false) String note,
                         RedirectAttributes redirect) {
        try {
            kycService.approve(publicId, tier, note, currentUser.publicId());
            redirect.addAttribute("approved", tier.name());
        } catch (BusinessRuleException ex) {
            redirect.addAttribute("error", ex.getMessage());
        }
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
        if (!canAct(publicId)) {
            throw new AccessDeniedException("Reading a customer file is not the same as acting on it");
        }
        try {
            accountService.open(new OpenAccountRequest(publicId, currencyCode, type, dailyLimit),
                                publicId, currentUser.publicId());
            redirect.addAttribute("opened", "");
        } catch (BusinessRuleException ex) {
            redirect.addAttribute("error", ex.getMessage());
        }
        return "redirect:/users/" + publicId;
    }

    private void requireCanView(UUID publicId) {
        if (!currentUser.canBrowseCustomers() && !publicId.equals(currentUser.publicId())) {
            throw new AccessDeniedException("Not your profile");
        }
    }

    /** Compliance and audit read every file; opening an account is still a counter job. */
    private boolean canAct(UUID publicId) {
        return currentUser.isStaff() || publicId.equals(currentUser.publicId());
    }
}
