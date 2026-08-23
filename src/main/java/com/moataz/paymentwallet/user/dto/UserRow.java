package com.moataz.paymentwallet.user.dto;

import com.moataz.paymentwallet.user.UserStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserRow(
        UUID publicId,
        String fullName,
        String email,
        String phone,
        UserStatus status,
        OffsetDateTime createdAt,
        Long accountCount
) {
    public String initial() {
        return fullName == null || fullName.isBlank() ? "?" : fullName.substring(0, 1).toUpperCase();
    }
}
