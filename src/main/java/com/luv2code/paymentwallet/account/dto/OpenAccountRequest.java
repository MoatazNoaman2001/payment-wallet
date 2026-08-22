package com.luv2code.paymentwallet.account.dto;

import com.luv2code.paymentwallet.account.AccountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record OpenAccountRequest(

        @NotNull(message = "currencyCode is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO code, e.g. EGP")
        String currencyCode,

        @NotNull(message = "type is required")
        AccountType type,

        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than 0")
        @Digits(integer = 15, fraction = 4)
        BigDecimal dailyLimit
) {
}
