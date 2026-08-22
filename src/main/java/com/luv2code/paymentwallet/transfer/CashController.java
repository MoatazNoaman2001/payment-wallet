package com.luv2code.paymentwallet.transfer;

import com.luv2code.paymentwallet.transfer.dto.CashRequest;
import com.luv2code.paymentwallet.transfer.dto.TransferResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/accounts/{accountNumber}")
@Tag(name = "Cash", description = "Deposits and withdrawals against the settlement account")
@Validated
public class CashController {

    private final CashService cashService;

    public CashController(CashService cashService) {
        this.cashService = cashService;
    }

    @Operation(summary = "Deposit into an account",
               description = "SYSTEM settlement account -> wallet, recorded as a TOPUP transfer.")
    @PostMapping("/deposits")
    public ResponseEntity<TransferResponse> deposit(
            @Parameter(description = "The customer wallet to credit, e.g. PW0012345678901234. "
                     + "Not a SYSTEM account: the settlement side is chosen for you.",
                       example = "PW0012345678901234")
            @PathVariable String accountNumber,
            @RequestParam UUID initiatorPublicId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
            @Valid @RequestBody CashRequest request) {

        return ResponseEntity.status(201)
                .body(cashService.deposit(accountNumber, initiatorPublicId, request, idempotencyKey));
    }

    @Operation(summary = "Withdraw from an account",
               description = "Wallet -> SYSTEM settlement account, recorded as a WITHDRAWAL transfer.")
    @PostMapping("/withdrawals")
    public ResponseEntity<TransferResponse> withdraw(
            @Parameter(description = "The customer wallet to debit, e.g. PW0012345678901234.",
                       example = "PW0012345678901234")
            @PathVariable String accountNumber,
            @RequestParam UUID initiatorPublicId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
            @Valid @RequestBody CashRequest request) {

        return ResponseEntity.status(201)
                .body(cashService.withdraw(accountNumber, initiatorPublicId, request, idempotencyKey));
    }
}
