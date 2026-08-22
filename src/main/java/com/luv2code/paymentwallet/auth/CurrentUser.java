package com.luv2code.paymentwallet.auth;

import com.luv2code.paymentwallet.common.error.UnauthorizedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The single place identity is read from. Nothing in the application takes the caller's
 * identity from a request body or query parameter any more.
 */
@Component
public class CurrentUser {

    public UUID publicId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new UnauthorizedException("Not authenticated");
        }
        return UUID.fromString(jwt.getSubject());
    }

    public boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
