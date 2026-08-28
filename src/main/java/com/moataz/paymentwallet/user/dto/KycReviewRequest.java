package com.moataz.paymentwallet.user.dto;

import com.moataz.paymentwallet.user.KycTier;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record KycReviewRequest(
        @NotNull(message = "tier is required") KycTier tier,
        @Size(max = 255) String note
) {
}
