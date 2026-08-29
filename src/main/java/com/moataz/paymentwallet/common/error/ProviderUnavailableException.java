package com.moataz.paymentwallet.common.error;

/**
 * The wallet is fine; somebody else is not. Distinct from BusinessRuleException because the
 * caller did nothing wrong and retrying may well work.
 */
public class ProviderUnavailableException extends RuntimeException {
    public ProviderUnavailableException(String message) {
        super(message);
    }

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
