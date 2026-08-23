package com.moataz.paymentwallet.common.error;

/** A request that is well-formed but violates a domain rule. Maps to 422. */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
