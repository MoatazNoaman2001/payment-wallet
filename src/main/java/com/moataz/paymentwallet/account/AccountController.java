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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
               description = "Opens a zero-balance account in the given currency. ownerPublicId is "
                           + "optional and defaults to the caller; naming another user requires "
                           + "ROLE_ADMIN. SYSTEM accounts cannot be opened here.")
    @PostMapping
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request,
                                                UriComponentsBuilder uriBuilder) {
        UUID owner = request.ownerPublicId() == null ? currentUser.publicId() : request.ownerPublicId();
        if (!owner.equals(currentUser.publicId()) && !currentUser.isStaff()) {
            throw new AccessDeniedException("Only a teller or administrator may open an account for another user");
        }
        AccountResponse created = accountService.open(request, owner, currentUser.publicId());
        URI location = uriBuilder.path("/api/accounts/{number}")
                                 .buildAndExpand(created.accountNumber())
                                 .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PreAuthorize("@ownership.canServiceAccount(#accountNumber, authentication)")
    @GetMapping("/{accountNumber}")
    public AccountResponse getOne(@PathVariable String accountNumber) {
        return accountService.findByNumber(accountNumber);
    }

    @Operation(summary = "Freeze an account",
               description = "Compliance action. A frozen account can neither send nor receive; "
                           + "settlement accounts cannot be frozen.")
    @PreAuthorize("@ownership.canFreeze(authentication)")
    @PostMapping("/{accountNumber}/freeze")
    public AccountResponse freeze(@PathVariable String accountNumber,
                                  @RequestParam(required = false) String reason) {
        return accountService.freeze(accountNumber, reason, currentUser.publicId());
    }

    @Operation(summary = "Lift a freeze", description = "Compliance action.")
    @PreAuthorize("@ownership.canFreeze(authentication)")
    @PostMapping("/{accountNumber}/unfreeze")
    public AccountResponse unfreeze(@PathVariable String accountNumber,
                                    @RequestParam(required = false) String reason) {
        return accountService.unfreeze(accountNumber, reason, currentUser.publicId());
    }

    @Operation(summary = "List accounts",
               description = "Paginated. A customer always sees their own accounts. Staff may pass "
                           + "ownerPublicId to scope to one customer, or omit it to list every account.")
    @GetMapping
    public Page<AccountResponse> list(
            @RequestParam(required = false) UUID ownerPublicId,
            @PageableDefault(size = 20, sort = "id") Pageable pageable) {

        if (currentUser.isStaff()) {
            return accountService.list(ownerPublicId, pageable);
        }
        if (ownerPublicId != null && !ownerPublicId.equals(currentUser.publicId())) {
            throw new AccessDeniedException("Not your accounts");
        }
        return accountService.list(currentUser.publicId(), pageable);
    }
}
