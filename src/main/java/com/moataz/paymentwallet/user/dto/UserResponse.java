package com.moataz.paymentwallet.user.dto;

import com.moataz.paymentwallet.user.AppUser;
import com.moataz.paymentwallet.user.Role;
import com.moataz.paymentwallet.user.UserStatus;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

public record UserResponse(
        UUID publicId,
        String email,
        String phone,
        String fullName,
        UserStatus status,
        Set<String> roles,
        OffsetDateTime createdAt
) {
    public static UserResponse from(AppUser user) {
        return new UserResponse(
                user.getPublicId(),
                user.getEmail(),
                user.getPhone(),
                user.getFullName(),
                user.getStatus(),
                user.getRoles().stream().map(Role::getName)
                    .collect(Collectors.toCollection(TreeSet::new)),
                user.getCreatedAt());
    }
}
