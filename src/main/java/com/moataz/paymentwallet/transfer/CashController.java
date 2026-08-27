package com.moataz.paymentwallet.transfer;

import com.moataz.paymentwallet.transfer.dto.CashRequest;
import com.moataz.paymentwallet.transfer.dto.TransferResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.moataz.paymentwallet.auth.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/accounts/{accountNumber}")
@Tag(name = "Cash", description = "Deposits and withdrawals against the settlement account")
@Validated
public class CashController {
    private final CashService cashService;

    private final CurrentUser currentUser;

    public CashController(CashService cashService, CurrentUser currentUser) {
        this.cashService = cashService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Deposit into an account",
               description = "SYSTEM settlement account -> wallet, recorded as a TOPUP transfer.")
    @PreAuthorize("@ownership.canOperateCash(#accountNumber, authentication)")
    @PostMapping("/deposits")
    public ResponseEntity<TransferResponse> deposit(
            @Parameter(description = "The customer wallet to credit, e.g. PW0012345678901234. "
                     + "Not a SYSTEM account: the settlement side is chosen for you.",
                       example = "PW0012345678901234")
            @PathVariable String accountNumber,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
            @Valid @RequestBody CashRequest request) {
        return ResponseEntity.status(201)
                .body(cashService.deposit(accountNumber, currentUser.publicId(), request, idempotencyKey));
    }

    @Operation(summary = "Withdraw from an account",
               description = "Wallet -> SYSTEM settlement account, recorded as a WITHDRAWAL transfer.")
    @PreAuthorize("@ownership.canOperateCash(#accountNumber, authentication)")
    @PostMapping("/withdrawals")
    public ResponseEntity<TransferResponse> withdraw(
            @Parameter(description = "The customer wallet to debit, e.g. PW0012345678901234.",
                       example = "PW0012345678901234")
            @PathVariable String accountNumber,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
            @Valid @RequestBody CashRequest request) {
        return ResponseEntity.status(201)
                .body(cashService.withdraw(accountNumber, currentUser.publicId(), request, idempotencyKey));
    }
}
