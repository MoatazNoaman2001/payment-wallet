package com.moataz.paymentwallet.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

public record KycSubmissionRequest(

        @NotBlank(message = "national id is required")
        @Pattern(regexp = "^[0-9]{8,20}$", message = "must be 8-20 digits")
        String nationalId,

        @NotNull(message = "date of birth is required")
        @Past(message = "must be in the past")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate dateOfBirth,

        @Size(max = 255)
        String address
) {
    public KycSubmissionRequest {
        nationalId = nationalId == null ? null : nationalId.trim();
        address = address == null || address.isBlank() ? null : address.trim();
    }
}
