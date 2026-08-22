package com.luv2code.paymentwallet.account;

import com.luv2code.paymentwallet.account.dto.AccountResponse;
import com.luv2code.paymentwallet.account.dto.OpenAccountRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
@Tag(name = "Accounts", description = "Wallets. Balances change only through the ledger.")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

//    public AccountController(AccountService accountService) {
//        this.accountService = accountService;
//    }

    @Operation(summary = "Open an account",
               description = "Opens a zero-balance account in the given currency for an existing user.")
    @PostMapping
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request,
                                                UriComponentsBuilder uriBuilder) {
        AccountResponse created = accountService.open(request);
        URI location = uriBuilder.path("/api/accounts/{number}")
                                 .buildAndExpand(created.accountNumber())
                                 .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{accountNumber}")
    public AccountResponse getOne(@PathVariable String accountNumber) {
        return accountService.findByNumber(accountNumber);
    }

    /** Phase 4 adds the ownership check that stops user A reading user B's accounts. */
    @GetMapping
    public List<AccountResponse> listForOwner(@RequestParam UUID ownerPublicId) {
        return accountService.findAllForOwner(ownerPublicId);
    }
}
