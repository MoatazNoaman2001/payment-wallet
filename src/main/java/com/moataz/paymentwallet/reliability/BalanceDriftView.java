package com.moataz.paymentwallet.reliability;

import java.math.BigDecimal;

public interface BalanceDriftView {
    String getAccountNumber();

    BigDecimal getBalance();

    BigDecimal getLedgerBalance();
}
