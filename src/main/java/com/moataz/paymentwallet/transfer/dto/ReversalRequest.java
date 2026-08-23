package com.moataz.paymentwallet.transfer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReversalRequest(
        @NotBlank(message = "reason is required")
        @Size(max = 255)
        String reason
) {
}
