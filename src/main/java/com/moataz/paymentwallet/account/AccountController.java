package com.moataz.paymentwallet.account;

import com.moataz.paymentwallet.account.dto.AccountResponse;
import com.moataz.paymentwallet.account.dto.OpenAccountRequest;
import com.moataz.paymentwallet.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.AccessDeniedException;
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
    private final CurrentUser currentUser;

    @Operation(summary = "Open an account",
               description = "Opens a zero-balance account in the given currency for the caller.")
    @PostMapping
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request,
                                                UriComponentsBuilder uriBuilder) {
        AccountResponse created = accountService.open(request, currentUser.publicId());
        URI location = uriBuilder.path("/api/accounts/{number}")
                                 .buildAndExpand(created.accountNumber())
                                 .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PreAuthorize("@ownership.ownsAccount(#accountNumber, authentication)")
    @GetMapping("/{accountNumber}")
    public AccountResponse getOne(@PathVariable String accountNumber) {
        return accountService.findByNumber(accountNumber);
    }

    @Operation(summary = "List accounts",
               description = "The caller's own accounts. Administrators may pass ownerPublicId "
                           + "to list someone else's.")
    @GetMapping
    public List<AccountResponse> list(@RequestParam(required = false) UUID ownerPublicId) {
        if (ownerPublicId == null || ownerPublicId.equals(currentUser.publicId())) {
            return accountService.findAllForOwner(currentUser.publicId());
        }
        if (!currentUser.isAdmin()) {
            throw new AccessDeniedException("Not your accounts");
        }
        return accountService.findAllForOwner(ownerPublicId);
    }
}
