package com.moataz.paymentwallet.common.error;

/** Thrown when a uniqueness rule is violated (email, phone...). Maps to 409. */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}
