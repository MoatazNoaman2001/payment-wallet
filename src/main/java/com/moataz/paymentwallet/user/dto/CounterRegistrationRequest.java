package com.moataz.paymentwallet.user.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record CounterRegistrationRequest(
        @Valid @NotNull RegisterUserRequest user,
        @Valid @NotNull KycSubmissionRequest kyc
) {
}
