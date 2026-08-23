package com.moataz.paymentwallet.auth.dto;

import java.util.Set;
import java.util.UUID;

public record TokenResponse(
        String accessToken,
        long expiresInSeconds,
        UUID publicId,
        String email,
        Set<String> roles
) {
}
