package com.moataz.paymentwallet.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record TransferForm(

        @NotBlank(message = "choose an account to send from")
        String sourceAccountNumber,

        @NotBlank(message = "enter the recipient's account number")
        String destAccountNumber,

        @NotNull(message = "enter an amount")
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than 0")
        @Digits(integer = 15, fraction = 4)
        BigDecimal amount,

        @Size(max = 255)
        String description,

        @NotBlank
        String idempotencyKey
) {
    public TransferForm {
        sourceAccountNumber = sourceAccountNumber == null ? null : sourceAccountNumber.trim();
        destAccountNumber = destAccountNumber == null ? null : destAccountNumber.trim().toUpperCase();
    }
}
