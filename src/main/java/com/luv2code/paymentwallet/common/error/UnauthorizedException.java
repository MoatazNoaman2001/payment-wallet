package com.luv2code.paymentwallet.common.error;

/** Bad or missing credentials. Maps to 401 — as opposed to 403, which means "not yours". */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
