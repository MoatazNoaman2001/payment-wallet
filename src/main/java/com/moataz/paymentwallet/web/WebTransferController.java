package com.moataz.paymentwallet.web;

import com.moataz.paymentwallet.account.AccountService;
import com.moataz.paymentwallet.account.dto.AccountResponse;
import com.moataz.paymentwallet.auth.CurrentUser;
import com.moataz.paymentwallet.common.error.BusinessRuleException;
import com.moataz.paymentwallet.common.error.NotFoundException;
import com.moataz.paymentwallet.transfer.TransferService;
import com.moataz.paymentwallet.transfer.TransferType;
import com.moataz.paymentwallet.transfer.dto.TransferRequest;
import com.moataz.paymentwallet.transfer.dto.TransferResponse;
import com.moataz.paymentwallet.web.dto.TransferForm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class WebTransferController {

    private final TransferService transferService;
    private final AccountService accountService;
    private final CurrentUser currentUser;

    @GetMapping("/transfer")
    public String form(@RequestParam(required = false) String from, Model model) {
        List<AccountResponse> accounts = myAccounts();
        String source = accounts.stream()
                .map(AccountResponse::accountNumber)
                .filter(n -> n.equals(from))
                .findFirst()
                .orElseGet(() -> accounts.isEmpty() ? null : accounts.getFirst().accountNumber());

        model.addAttribute("accounts", accounts);
        model.addAttribute("form", new TransferForm(
                source, null, null, null, UUID.randomUUID().toString()));
        return "transfer";
    }

    @PostMapping("/transfer")
    public String send(@Valid @ModelAttribute("form") TransferForm form,
                       BindingResult binding,
                       Model model,
                       RedirectAttributes redirect) {

        List<AccountResponse> accounts = myAccounts();
        AccountResponse source = accounts.stream()
                .filter(a -> a.accountNumber().equals(form.sourceAccountNumber()))
                .findFirst()
                .orElse(null);

        if (source == null && form.sourceAccountNumber() != null && !form.sourceAccountNumber().isBlank()) {
            throw new AccessDeniedException("You may only send from your own account");
        }
        if (binding.hasErrors() || source == null) {
            model.addAttribute("accounts", accounts);
            return "transfer";
        }

        try {
            TransferResponse sent = transferService.execute(
                    new TransferRequest(form.sourceAccountNumber(), form.destAccountNumber(),
                            form.amount(), source.currencyCode(), TransferType.P2P, form.description()),
                    form.idempotencyKey(), currentUser.publicId());
            redirect.addAttribute("sent", sent.reference());
            return "redirect:/accounts/" + sent.sourceAccountNumber() + "/statement";
        } catch (NotFoundException ex) {
            binding.rejectValue("destAccountNumber", "unknown", "No account with that number");
        } catch (BusinessRuleException ex) {
            binding.reject("rejected", ex.getMessage());
        }

        model.addAttribute("accounts", accounts);
        return "transfer";
    }

    private List<AccountResponse> myAccounts() {
        return accountService.findAllForOwner(currentUser.publicId()).stream()
                .filter(a -> a.balance().compareTo(BigDecimal.ZERO) >= 0)
                .toList();
    }
}
