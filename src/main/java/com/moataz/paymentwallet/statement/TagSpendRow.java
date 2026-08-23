package com.moataz.paymentwallet.statement;

import java.math.BigDecimal;

public record TagSpendRow(String tag, BigDecimal total, Long transfers) {
}
