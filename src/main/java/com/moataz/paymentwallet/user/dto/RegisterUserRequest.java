package com.moataz.paymentwallet.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTOs are Java records: immutable, no setters, no accidental JPA behaviour.
 *
 * Validation lives here, at the edge, not in the entity. The controller marks the
 * parameter @Valid and Spring rejects the request before your service ever runs.
 */
public record RegisterUserRequest(

        @NotBlank(message = "email is required")
        @Email(message = "must be a well-formed email address")
        @Size(max = 255)
        String email,

        @NotBlank(message = "phone is required")
        @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "must be 7-15 digits, optional leading +")
        String phone,

        @NotBlank(message = "password is required")
        @Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
        String password,

        @NotBlank(message = "fullName is required")
        @Size(max = 150)
        String fullName
) {
}
