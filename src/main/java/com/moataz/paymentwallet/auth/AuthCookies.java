package com.moataz.paymentwallet.auth;

import com.moataz.paymentwallet.config.SecurityConfig;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthCookies {
    private final TokenService tokenService;

    public void set(HttpServletResponse response, String accessToken, String refreshToken) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie
                .from(SecurityConfig.ACCESS_COOKIE, accessToken)
                .httpOnly(true).secure(false)
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
