package com.moataz.paymentwallet.funding;

import com.moataz.paymentwallet.auth.CurrentUser;
import com.moataz.paymentwallet.funding.dto.FundingRequest;
import com.moataz.paymentwallet.funding.dto.PaymentIntentResponse;
import com.moataz.paymentwallet.funding.dto.ProviderSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/funding")
@RequiredArgsConstructor
@Tag(name = "Funding", description = "Deposits and withdrawals through external payment providers")
public class FundingController {

    private final FundingService fundingService;
    private final CurrentUser currentUser;

    @Operation(summary = "List payment providers",
               description = "Which providers this deployment has credentials for, and which "
                           + "directions each can handle.")
    @GetMapping("/providers")
    public List<ProviderSummary> providers() {
        return fundingService.providers();
    }

    @Operation(summary = "Start a deposit",
               description = "Records the intent and asks the provider to collect. Nothing "
                           + "reaches the ledger until the provider confirms by webhook.")
    @PostMapping("/deposits")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PaymentIntentResponse deposit(@Valid @RequestBody FundingRequest request,
                                         @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return fundingService.deposit(request, currentUser.publicId(), idempotencyKey);
    }

    @Operation(summary = "Start a withdrawal",
               description = "Debits the wallet immediately, then asks the provider to pay out. "
                           + "A payout that later fails is returned with a compensating credit.")
    @PostMapping("/withdrawals")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PaymentIntentResponse withdraw(@Valid @RequestBody FundingRequest request,
                                          @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return fundingService.withdraw(request, currentUser.publicId(), idempotencyKey);
    }

    @Operation(summary = "Fetch one payment")
    @GetMapping("/payments/{reference}")
    public PaymentIntentResponse findOne(@PathVariable String reference) {
        if (!currentUser.canBrowseCustomers()
                && !fundingService.ownerOf(reference).equals(currentUser.publicId())) {
            throw new AccessDeniedException("Not your payment");
        }
        return fundingService.find(reference);
    }

    @Operation(summary = "Your deposits and withdrawals, newest first")
    @GetMapping("/payments")
    public Page<PaymentIntentResponse> history(@PageableDefault(size = 20) Pageable pageable) {
        return fundingService.history(currentUser.publicId(), pageable);
    }
}
