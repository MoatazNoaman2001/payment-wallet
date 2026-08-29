package com.moataz.paymentwallet.funding.provider;

import java.math.BigDecimal;

/**
 * This ledger stores NUMERIC(19,4). Stripe wants an integer number of the smallest unit,
 * PayPal wants a decimal string with exactly the currency's scale. Both conversions are
 * exact on purpose: a wallet that quietly rounds a customer's money is a wallet that does
 * not balance.
 */
public final class Money {

    private Money() {
    }

    public static long toMinorUnits(BigDecimal amount, int minorUnits) {
        try {
            return amount.movePointRight(minorUnits).toBigIntegerExact().longValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(
                    amount + " cannot be expressed exactly in " + minorUnits
                    + " minor units: refusing to round money", ex);
        }
    }

    public static String toDecimalString(BigDecimal amount, int minorUnits) {
        return amount.setScale(minorUnits, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }

    public static BigDecimal fromMinorUnits(long minor, int minorUnits) {
        return BigDecimal.valueOf(minor).movePointLeft(minorUnits);
    }
}
