package com.luv2code.paymentwallet.auth;

import com.luv2code.paymentwallet.user.AppUser;
import com.luv2code.paymentwallet.user.Role;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Access tokens are short-lived JWTs: signed, self-describing, never looked up.
 * Refresh tokens are long-lived opaque random strings: stored hashed, single use,
 * and rotated on every use so a replay can be detected.
 */
@Service
@RequiredArgsConstructor
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JwtEncoder jwtEncoder;
    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${security.jwt.access-token-minutes:15}")
    private long accessTokenMinutes;

    @Value("${security.jwt.refresh-token-days:14}")
    private long refreshTokenDays;

    public String issueAccessToken(AppUser user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("payment-wallet")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(accessTokenMinutes * 60))
                .subject(user.getPublicId().toString())
                .claim("email", user.getEmail())
                .claim("roles", user.getRoles().stream().map(Role::getName).collect(Collectors.toList()))
                .build();
        // the header must name the algorithm: the encoder defaults to RS256 and then
        // fails to find a signing key, since ours is a symmetric secret
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long accessTokenSeconds() {
        return accessTokenMinutes * 60;
    }

    public long refreshTokenSeconds() {
        return refreshTokenDays * 24 * 60 * 60;
    }

    /** A brand new family: this is a fresh login, not a rotation. */
    @Transactional
    public String issueRefreshToken(AppUser user) {
        return persist(user, UUID.randomUUID());
    }

    /**
     * Single-use rotation. Presenting a token that was already revoked means someone
     * replayed an old copy, so every token descended from that login is killed.
     */
    @Transactional
    public RotationResult rotate(String presentedToken) {
        String hash = sha256(presentedToken);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash).orElse(null);
        if (stored == null) {
            return RotationResult.rejected("Unknown refresh token");
        }
        if (stored.getRevokedAt() != null) {
            int killed = refreshTokenRepository.revokeFamily(stored.getFamilyId(), now);
            log.warn("Refresh token reuse detected for user {} - revoked {} tokens in family {}",
                     stored.getUser().getPublicId(), killed, stored.getFamilyId());
            return RotationResult.rejected("Refresh token reuse detected, please log in again");
        }
        if (!stored.isUsable(now)) {
            return RotationResult.rejected("Refresh token expired");
        }

        stored.setRevokedAt(now);
        AppUser user = stored.getUser();
        return RotationResult.accepted(user, persist(user, stored.getFamilyId()));
    }

    @Transactional
    public void revokeAllForUser(Long userId) {
        refreshTokenRepository.revokeAllForUser(userId, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private String persist(AppUser user, UUID familyId) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        RefreshToken entity = new RefreshToken();
        entity.setUser(user);
        entity.setTokenHash(sha256(token));
        entity.setFamilyId(familyId);
        entity.setIssuedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entity.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(refreshTokenDays));
        refreshTokenRepository.save(entity);

        return token;   // the only time the raw value exists outside the client
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record RotationResult(boolean accepted, AppUser user, String refreshToken, String reason) {
        static RotationResult accepted(AppUser user, String refreshToken) {
            return new RotationResult(true, user, refreshToken, null);
        }

        static RotationResult rejected(String reason) {
            return new RotationResult(false, null, null, reason);
        }
    }
}
