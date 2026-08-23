package com.moataz.paymentwallet.auth;

import com.moataz.paymentwallet.account.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Referenced from @PreAuthorize as @ownership. Roles say what kind of thing you may do;
 * this says whose rows you may do it to. Without it, any authenticated customer could
 * read every other customer's statement.
 */
@Component("ownership")
@RequiredArgsConstructor
public class AccountOwnership {

    private final AccountRepository accountRepository;

    public boolean ownsAccount(String accountNumber, Authentication authentication) {
        if (isAdmin(authentication)) {
            return true;
        }
        UUID caller = callerPublicId(authentication);
        return caller != null && accountNumber != null
                && accountRepository.findByAccountNumberAndUserPublicId(accountNumber, caller).isPresent();
    }

    /** A transfer is visible to whoever owns either side of it. */
    public boolean canSeeTransfer(String reference, Authentication authentication) {
        if (isAdmin(authentication)) {
            return true;
        }
        UUID caller = callerPublicId(authentication);
        return caller != null && reference != null
                && accountRepository.existsByTransferReferenceAndOwner(reference, caller);
    }

    public boolean isSelf(UUID publicId, Authentication authentication) {
        return isAdmin(authentication) || publicId != null && publicId.equals(callerPublicId(authentication));
    }

    public boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private UUID callerPublicId(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof org.springframework.security.oauth2.jwt.Jwt jwt)) {
            return null;
        }
        return UUID.fromString(jwt.getSubject());
    }
}
