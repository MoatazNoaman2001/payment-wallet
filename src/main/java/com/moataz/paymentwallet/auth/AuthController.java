package com.moataz.paymentwallet.auth;

import com.moataz.paymentwallet.auth.dto.LoginRequest;
import com.moataz.paymentwallet.auth.dto.TokenResponse;
import com.moataz.paymentwallet.common.error.NotFoundException;
import com.moataz.paymentwallet.config.SecurityConfig;
import com.moataz.paymentwallet.user.AppUser;
import com.moataz.paymentwallet.user.AppUserRepository;
import com.moataz.paymentwallet.user.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import com.moataz.paymentwallet.common.error.UnauthorizedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Login, refresh with rotation, logout")
@RequiredArgsConstructor
public class AuthController {

    private final AppUserRepository userRepository;
    private final AuthService authService;
    private final AuthCookies authCookies;
    private final TokenService tokenService;

    @Operation(summary = "Log in",
               description = "Sets HttpOnly access and refresh cookies. The access token is "
                           + "also returned in the body so Swagger's Authorize button can use it.")
    @PostMapping("/login")
    @Transactional
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletResponse response) {
        AuthService.Session session = authService.login(request.email(), request.password());
        authCookies.set(response, session.accessToken(), session.refreshToken());
        return ResponseEntity.ok(body(session.user(), session.accessToken()));
    }

    @Operation(summary = "Rotate the refresh token",
               description = "Single use. Replaying a spent token revokes every token from that login.")
    @PostMapping("/refresh")
    @Transactional
    public ResponseEntity<TokenResponse> refresh(
            @CookieValue(name = SecurityConfig.REFRESH_COOKIE, required = false) String cookieToken,
            @RequestParam(name = "refreshToken", required = false) String paramToken,
            HttpServletResponse response) {

        String presented = cookieToken != null ? cookieToken : paramToken;
        if (presented == null || presented.isBlank()) {
            throw new UnauthorizedException("No refresh token supplied");
        }

        TokenService.RotationResult result = tokenService.rotate(presented);
        if (!result.accepted()) {
            authCookies.clear(response);
            throw new UnauthorizedException(result.reason());
        }

        AppUser user = userRepository.findByEmailWithRoles(result.user().getEmail()).orElseThrow();
        String access = tokenService.issueAccessToken(user);
        authCookies.set(response, access, result.refreshToken());
        return ResponseEntity.ok(body(user, access));
    }

    @Operation(summary = "Log out", description = "Revokes every refresh token for the caller.")
    @PostMapping("/logout")
    @Transactional
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt, HttpServletResponse response) {
        AppUser user = userRepository.findByPublicId(UUID.fromString(jwt.getSubject()))
                .orElseThrow(() -> new NotFoundException("Unknown user"));
        tokenService.revokeAllForUser(user.getId());
        authCookies.clear(response);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Who am I", description = "Echoes the authenticated identity from the token.")
    @GetMapping("/me")
    @Transactional(readOnly = true)
    public TokenResponse me(@AuthenticationPrincipal Jwt jwt) {
        AppUser user = userRepository.findByPublicId(UUID.fromString(jwt.getSubject()))
                .orElseThrow(() -> new NotFoundException("Unknown user"));
        return body(user, null);
    }

    private TokenResponse body(AppUser user, String access) {
        Set<String> roles = user.getRoles().stream().map(Role::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        return new TokenResponse(access, tokenService.accessTokenSeconds(),
                                 user.getPublicId(), user.getEmail(), roles);
    }


}
