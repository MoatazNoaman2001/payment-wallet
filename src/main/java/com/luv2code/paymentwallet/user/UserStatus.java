package com.luv2code.paymentwallet.user;

/**
 * Mirrors the CHECK constraint on app_user.status.
 * Always mapped with @Enumerated(EnumType.STRING) — see AppUser.status.
 */
public enum UserStatus {
    PENDING, ACTIVE, SUSPENDED, CLOSED
}
