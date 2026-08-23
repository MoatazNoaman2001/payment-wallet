package com.moataz.paymentwallet.auth;

import com.moataz.paymentwallet.common.error.UnauthorizedException;
import com.moataz.paymentwallet.user.AppUser;
import com.moataz.paymentwallet.user.AppUserRepository;
import com.moataz.paymentwallet.user.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shared by the JSON API and the browser pages, so there is one definition of "log in". */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    @Transactional
    public Session login(String email, String password) {
        AppUser user = userRepository.findByEmailWithRoles(email)
                .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));

        // same error either way: a distinct "no such user" reply enumerates accounts
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid credentials");
        }
        if (user.getStatus() == UserStatus.SUSPENDED || user.getStatus() == UserStatus.CLOSED) {
            throw new UnauthorizedException("Account is " + user.getStatus());
        }

        return new Session(user, tokenService.issueAccessToken(user), tokenService.issueRefreshToken(user));
    }

    public record Session(AppUser user, String accessToken, String refreshToken) {
    }
}
