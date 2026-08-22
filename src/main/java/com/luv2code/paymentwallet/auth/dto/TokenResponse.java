package com.luv2code.paymentwallet.auth.dto;

import java.util.Set;
import java.util.UUID;

/**
 * The access token is also set as an HttpOnly cookie. It is returned in the body as well
 * so Swagger and curl can use the Authorize button; a production deployment would drop
 * the body copy and rely on the cookie alone.
 */
public record TokenResponse(
        String accessToken,
        long expiresInSeconds,
        UUID publicId,
        String email,
        Set<String> roles
) {
}
