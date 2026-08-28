package com.moataz.paymentwallet.auth;

import com.moataz.paymentwallet.account.AccountRepository;
import com.moataz.paymentwallet.user.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component("ownership")
@RequiredArgsConstructor
public class AccountOwnership {

    private static final Set<String> OPERATIONAL_STAFF =
            Set.of(Role.TELLER, Role.SUPERVISOR, Role.ADMIN);

    private static final Set<String> READ_ONLY_STAFF =
            Set.of(Role.AUDITOR, Role.COMPLIANCE, Role.OPS);

    private final AccountRepository accountRepository;

    public boolean ownsAccount(String accountNumber, Authentication authentication) {
        return isAdmin(authentication) || ownsPersonally(accountNumber, authentication);
    }

    public boolean canServiceAccount(String accountNumber, Authentication authentication) {
        return isStaff(authentication)
                || hasAnyRole(authentication, READ_ONLY_STAFF)
                || ownsPersonally(accountNumber, authentication);
    }

    /**
     * Staff powers may not be used on the actor's own account. A teller taking a deposit
     * into their own wallet is the textbook internal fraud, so the check is on identity,
     * not on role.
     */
    public boolean canOperateCash(String accountNumber, Authentication authentication) {
        return isStaff(authentication) && !ownsPersonally(accountNumber, authentication);
    }

    public boolean canSeeTransfer(String reference, Authentication authentication) {
        if (isStaff(authentication) || hasAnyRole(authentication, READ_ONLY_STAFF)) {
            return true;
        }
        UUID caller = callerPublicId(authentication);
        return caller != null && reference != null
                && accountRepository.existsByTransferReferenceAndOwner(reference, caller);
    }

    public boolean isSelf(UUID publicId, Authentication authentication) {
        return isStaff(authentication) || hasAnyRole(authentication, READ_ONLY_STAFF)
                || publicId != null && publicId.equals(callerPublicId(authentication));
    }

    public boolean canFreeze(Authentication authentication) {
        return hasAnyRole(authentication, Set.of(Role.COMPLIANCE, Role.ADMIN));
    }

    public boolean isStaff(Authentication authentication) {
        return hasAnyRole(authentication, OPERATIONAL_STAFF);
    }

    public boolean isAdmin(Authentication authentication) {
        return hasAnyRole(authentication, Set.of(Role.ADMIN));
    }

    public boolean ownsPersonally(String accountNumber, Authentication authentication) {
        UUID caller = callerPublicId(authentication);
        return caller != null && accountNumber != null
                && accountRepository.findByAccountNumberAndUserPublicId(accountNumber, caller).isPresent();
    }

    private boolean hasAnyRole(Authentication authentication, Set<String> roles) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> roles.contains(a.getAuthority()));
    }

    private UUID callerPublicId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        return UUID.fromString(jwt.getSubject());
    }
}
