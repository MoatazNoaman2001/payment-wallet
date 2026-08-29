package com.moataz.paymentwallet.web;

import com.moataz.paymentwallet.account.AccountService;
import com.moataz.paymentwallet.auth.CurrentUser;
import com.moataz.paymentwallet.common.error.BusinessRuleException;
import com.moataz.paymentwallet.common.error.ProviderUnavailableException;
import com.moataz.paymentwallet.funding.FundingService;
import com.moataz.paymentwallet.funding.PaymentDirection;
import com.moataz.paymentwallet.funding.PaymentProvider;
import com.moataz.paymentwallet.funding.WebhookService;
import com.moataz.paymentwallet.funding.dto.FundingRequest;
import com.moataz.paymentwallet.funding.dto.PaymentIntentResponse;
import com.moataz.paymentwallet.funding.provider.SandboxAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class WebFundingController {

    private final FundingService fundingService;
    private final WebhookService webhookService;
    private final SandboxAdapter sandboxAdapter;
    private final AccountService accountService;
    private final CurrentUser currentUser;

    @GetMapping("/funding")
    public String funding(Model model) {
        UUID me = currentUser.publicId();
        model.addAttribute("accounts", accountService.findAllForOwner(me));
        model.addAttribute("depositProviders", fundingService.available(PaymentDirection.DEPOSIT));
        model.addAttribute("withdrawProviders", fundingService.available(PaymentDirection.WITHDRAWAL));
        model.addAttribute("payments", fundingService.history(me, PageRequest.of(0, 10)).getContent());
        // the same one-time key trick the transfer page uses: a double click or a refresh
        // reuses it, and the engine returns the original payment instead of starting a second
        model.addAttribute("idempotencyKey", UUID.randomUUID().toString());
        return "funding";
    }

    @PostMapping("/funding")
    public String start(@RequestParam String accountNumber,
                        @RequestParam PaymentProvider provider,
                        @RequestParam BigDecimal amount,
                        @RequestParam PaymentDirection direction,
                        @RequestParam String idempotencyKey,
                        RedirectAttributes redirect) {
        FundingRequest request = new FundingRequest(accountNumber, provider, amount, null);
        PaymentIntentResponse payment;
        try {
            payment = direction == PaymentDirection.DEPOSIT
                    ? fundingService.deposit(request, currentUser.publicId(), idempotencyKey)
                    : fundingService.withdraw(request, currentUser.publicId(), idempotencyKey);
        } catch (BusinessRuleException | ProviderUnavailableException ex) {
            redirect.addAttribute("error", ex.getMessage());
            return "redirect:/funding";
        }

        if (payment.redirectUrl() != null) {
            return "redirect:" + payment.redirectUrl();
        }
        redirect.addAttribute("started", payment.reference());
        return "redirect:/funding";
    }

    /**
     * The sandbox provider's own page. Everything past this point is the real code path: the
     * button below signs a webhook and posts it back, and it is verified like any other.
     */
    @GetMapping("/funding/sandbox/{reference}")
    public String sandbox(@PathVariable String reference, Model model) {
        requireOwner(reference);
        model.addAttribute("payment", fundingService.find(reference));
        return "funding-sandbox";
    }

    @PostMapping("/funding/sandbox/{reference}")
    public String sandboxOutcome(@PathVariable String reference,
                                 @RequestParam String outcome,
                                 RedirectAttributes redirect) {
        requireOwner(reference);
        PaymentIntentResponse payment = fundingService.find(reference);

        String body = """
                {"id":"evt_%s","type":"sandbox.payment.%s","reference":"%s","status":"%s"}"""
                .formatted(UUID.randomUUID().toString().replace("-", ""),
                           outcome, payment.providerReference(), outcome);

        webhookService.handle(PaymentProvider.SANDBOX, body,
                Map.of("x-sandbox-signature", sandboxAdapter.sign(body)));

        redirect.addAttribute("settled", reference);
        return "redirect:/funding";
    }

    /** Where a real provider sends the customer back afterwards. */
    @GetMapping("/funding/return/{reference}")
    public String providerReturn(@PathVariable String reference,
                                 @RequestParam(required = false) String outcome,
                                 RedirectAttributes redirect) {
        requireOwner(reference);
        // the browser coming back is not proof of anything; the webhook is what settles it
        redirect.addAttribute("returned", reference);
        if (outcome != null) {
            redirect.addAttribute("outcome", outcome);
        }
        return "redirect:/funding";
    }

    private void requireOwner(String reference) {
        if (!fundingService.ownerOf(reference).equals(currentUser.publicId())) {
            throw new AccessDeniedException("Not your payment");
        }
    }
}
