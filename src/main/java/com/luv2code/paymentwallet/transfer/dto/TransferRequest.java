package com.luv2code.paymentwallet.transfer.dto;

import com.luv2code.paymentwallet.transfer.TransferType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record TransferRequest(

        @NotBlank(message = "sourceAccountNumber is required")
        String sourceAccountNumber,

        @NotBlank(message = "destAccountNumber is required")
        String destAccountNumber,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than 0")
        @Digits(integer = 15, fraction = 4)
        BigDecimal amount,

        @NotNull(message = "currencyCode is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO code")
        String currencyCode,

        @NotNull(message = "type is required")
        TransferType type,

        @Size(max = 255)
        String description
) {
}
