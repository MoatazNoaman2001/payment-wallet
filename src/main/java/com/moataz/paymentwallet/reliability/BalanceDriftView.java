package com.moataz.paymentwallet.reliability;

import java.math.BigDecimal;

/**
 * A closed interface projection: Spring Data builds a proxy over the three selected
 * columns, so no Account or LedgerEntry entity is materialised.
 */
public interface BalanceDriftView {

    String getAccountNumber();

    BigDecimal getBalance();

    BigDecimal getLedgerBalance();
}
