package com.moataz.paymentwallet.funding;

import java.util.EnumSet;
import java.util.Set;

/**
 * Money that has been asked for but not yet received, or sent but not yet settled.
 * Only SUCCEEDED has ever touched the ledger for a deposit.
 */
public enum PaymentIntentStatus {

    /** The customer must go somewhere else: a card form, a PayPal login, a wallet app. */
    REQUIRES_ACTION,

    /** The provider has it and is working. For a withdrawal the wallet is already debited. */
    PENDING,

    SUCCEEDED,
    FAILED,
    EXPIRED,
    CANCELLED;

    private static final Set<PaymentIntentStatus> TERMINAL =
            EnumSet.of(SUCCEEDED, FAILED, EXPIRED, CANCELLED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isOpen() {
        return !isTerminal();
    }
}
