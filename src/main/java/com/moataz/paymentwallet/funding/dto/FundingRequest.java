package com.moataz.paymentwallet.funding.dto;

import com.moataz.paymentwallet.funding.PaymentProvider;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record FundingRequest(

        @NotBlank(message = "accountNumber is required")
        String accountNumber,

        @NotNull(message = "provider is required")
        PaymentProvider provider,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0001", message = "must be greater than zero")
        @Digits(integer = 15, fraction = 4, message = "at most 4 decimal places")
        BigDecimal amount,

        @Size(max = 30)
        String method
) {
}
