package com.ecommerce.userservice.service;

import com.ecommerce.userservice.config.security.AuthErrorCode;
import com.ecommerce.userservice.config.security.AuthTokenException;
import com.ecommerce.userservice.entity.RefreshToken;
import com.ecommerce.userservice.entity.User;
import com.ecommerce.userservice.repository.RefreshTokenRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${refresh.token.ttl-minutes}")
    private long tokenTtlMinutes;

    @Autowired
    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    public static record RotationResult(User user, String refreshToken) {
    }

    @Transactional
    public String issue(User user) {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(hash(rawToken))
                .expiresAt(Instant.now().plus(Duration.ofMinutes(tokenTtlMinutes)))
                .build());

        // Only the hash is stored. This is the one and only time the raw value exists.
        return rawToken;
    }

    /*
     * noRollbackFor is essential. AuthTokenException is a RuntimeException, and Spring
     * rolls those back by default - which would silently undo the revocation below and
     * leave the attacker's stolen token family fully usable.
     */
    @Transactional(noRollbackFor = AuthTokenException.class)
    public RotationResult rotate(String rawToken) {

        String hash = hash(rawToken);

        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new AuthTokenException(AuthErrorCode.REFRESH_TOKEN_INVALID));

        if (stored.getRevokedAt() != null) {
            // Already spent. Someone replayed a stolen copy - revoke every session.
            refreshTokenRepository.revokeAllForUser(stored.getUser().getId(), Instant.now());
            throw new AuthTokenException(AuthErrorCode.REFRESH_TOKEN_REUSED);
        }

        if (stored.getExpiresAt().isBefore(Instant.now())) {
            throw new AuthTokenException(AuthErrorCode.REFRESH_TOKEN_EXPIRED);
        }

        if (stored.getUser().isLocked()) {
            refreshTokenRepository.revokeAllForUser(stored.getUser().getId(), Instant.now());
            throw new AuthTokenException(AuthErrorCode.ACCOUNT_LOCKED);
        }

        // 'stored' is managed, so this UPDATE is flushed automatically at commit.
        // It and the INSERT below succeed together or not at all - a crash between
        // them would otherwise leave the user with a dead token and no replacement.
        stored.setRevokedAt(Instant.now());


        return new RotationResult(stored.getUser(), issue(stored.getUser()));
    }

    @Transactional
    public void revoke(String rawToken) {
        String tokenHash = hash(rawToken);

        Optional<RefreshToken> optionalToken = refreshTokenRepository.findByTokenHash(tokenHash);

        if (optionalToken.isEmpty()) {
            return;
        }

        RefreshToken token = optionalToken.get();
        token.setRevokedAt(Instant.now());
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}