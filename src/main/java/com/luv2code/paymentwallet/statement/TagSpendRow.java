package com.luv2code.paymentwallet.statement;

import java.math.BigDecimal;

/**
 * Built by a JPQL constructor expression, so the query selects three scalars and
 * never materialises a Transfer or a Tag. This is a projection in the strict sense.
 */
public record TagSpendRow(String tag, BigDecimal total, Long transfers) {
}
