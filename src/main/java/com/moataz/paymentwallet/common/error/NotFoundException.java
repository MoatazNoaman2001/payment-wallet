package com.moataz.paymentwallet.common.error;

/** Thrown when a resource referenced by the caller does not exist. Maps to 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
