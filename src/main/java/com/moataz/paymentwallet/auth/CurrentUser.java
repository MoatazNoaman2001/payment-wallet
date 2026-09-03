package com.moataz.paymentwallet.auth;

import com.moataz.paymentwallet.common.error.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Identity and powers of whoever is making this request.
 *
 * Every question here is forwarded to {@link AccountOwnership} rather than answered with its
 * own role list. It used to keep a second copy, and the copies drifted: a supervisor was
 * allowed through @PreAuthorize onto the cash desk while the page that would have linked them
 * there decided they were not staff. A permission you hold but cannot reach is a bug that no
 * authorization test catches, because authorization is working perfectly.
 */
@Component
@RequiredArgsConstructor
public class CurrentUser {

    private final AccountOwnership ownership;

    public UUID publicId() {
        Authentication auth = authentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new UnauthorizedException("Not authenticated");
        }
        return UUID.fromString(jwt.getSubject());
    }

    public Optional<UUID> publicIdIfPresent() {
        Authentication auth = authentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(jwt.getSubject()));
    }

    /** Operational staff: may work the counter. */
    public boolean isStaff() {
        return ownership.isStaff(authentication());
    }

    /** Operational, plus the read-only functions that need to see every customer file. */
    public boolean canBrowseCustomers() {
        return ownership.canBrowseCustomers(authentication());
    }

    public boolean canApproveIdentity() {
        return ownership.canApproveIdentity(authentication());
    }

    public boolean canFreeze() {
        return ownership.canFreeze(authentication());
    }

    public boolean isAdmin() {
        return ownership.isAdmin(authentication());
    }

    private static Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }
}
