package com.moataz.paymentwallet.auth;

import com.moataz.paymentwallet.config.SecurityConfig;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * One definition of how the auth cookies are written, shared by the JSON API and the
 * browser pages.
 *
 * HttpOnly so page scripts cannot read the tokens; the refresh cookie is additionally
 * scoped to the refresh endpoint so it is not attached to every request, and SameSite
 * Strict so it never rides a cross-site navigation.
 */
@Component
@RequiredArgsConstructor
public class AuthCookies {

    private final TokenService tokenService;

    public void set(HttpServletResponse response, String accessToken, String refreshToken) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie
                .from(SecurityConfig.ACCESS_COOKIE, accessToken)
                .httpOnly(true).secure(false)          // secure(true) behind TLS
                .sameSite("Lax").path("/")
                .maxAge(tokenService.accessTokenSeconds())
                .build().toString());

        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie
                .from(SecurityConfig.REFRESH_COOKIE, refreshToken)
                .httpOnly(true).secure(false)
                .sameSite("Strict").path(SecurityConfig.REFRESH_PATH)
                .maxAge(tokenService.refreshTokenSeconds())
                .build().toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie
                .from(SecurityConfig.ACCESS_COOKIE, "").httpOnly(true).path("/").maxAge(0)
                .build().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie
                .from(SecurityConfig.REFRESH_COOKIE, "").httpOnly(true)
                .path(SecurityConfig.REFRESH_PATH).maxAge(0)
                .build().toString());
    }
}
