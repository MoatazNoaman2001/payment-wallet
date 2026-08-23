package com.moataz.paymentwallet.transfer.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Currency is not supplied: it is whatever the target account holds. */
public record CashRequest(

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than 0")
        @Digits(integer = 15, fraction = 4)
        BigDecimal amount,

        @Size(max = 255)
        String description
) {
}
