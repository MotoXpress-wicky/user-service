// service/PasswordResetService.java
package com.ecommerce.userservice.service;

import com.ecommerce.userservice.entity.AuthProvider;
import com.ecommerce.userservice.entity.PasswordResetToken;
import com.ecommerce.userservice.entity.User;
import com.ecommerce.userservice.exception.InvalidPasswordResetTokenException;
import com.ecommerce.userservice.repository.PasswordResetTokenRepository;
import com.ecommerce.userservice.repository.RefreshTokenRepository;
import com.ecommerce.userservice.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

@Slf4j
@Service
public class PasswordResetService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;   // 256 bits — not brute-forceable

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${app.frontend.reset-password-url}")
    private String resetPasswordUrl;

    @Value("${app.password-reset.token-ttl-minutes}")
    private long ttlMinutes;

    @Value("${app.password-reset.resend-cooldown-seconds}")
    private long cooldownSeconds;

    @Autowired
    public PasswordResetService(UserRepository userRepository,
                                PasswordResetTokenRepository tokenRepository,
                                PasswordEncoder passwordEncoder,
                                EmailService emailService,
                                RefreshTokenRepository refreshTokenRepository
    ) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /**
     * Deliberately returns void and never throws for a missing account.
     * The controller replies with the same 200 either way.
     * <p>
     * Not @Transactional on purpose: the @Modifying repository methods carry
     * their own transaction, and this keeps the @Async mail call from firing
     * before an enclosing transaction commits (which would race the user
     * clicking the link against the token row actually existing).
     */
    public void sendPasswordResetEmail(String email) {
        Optional<User> optionalUser = userRepository.findByEmail(email);

        if (optionalUser.isEmpty()) {
            log.info("Password reset requested for unknown email");   // never log the address
            return;
        }

        User user = optionalUser.get();

        if (isWithinCooldown(user.getId())) {
            log.info("Password reset request ignored, still within cooldown");
            return;
        }

        // Google accounts have no local password to reset. Silently stop —
        // telling the caller "that's a Google account" leaks account details.
        if (user.getAuthProvider() != AuthProvider.LOCAL) {
            log.info("Password reset requested for a {} account, ignoring", user.getAuthProvider());
            return;
        }


        // Requesting a new link kills every previous one.
        tokenRepository.invalidateActiveTokens(user.getId(), Instant.now());


        String rawToken = generateRawToken();


        tokenRepository.save(PasswordResetToken.builder()
                .tokenHash(hash(rawToken))
                .user(user)
                .expiresAt(Instant.now().plus(Duration.ofMinutes(ttlMinutes)))
                .build());


        String link = UriComponentsBuilder.fromUriString(resetPasswordUrl)
                .queryParam("token", rawToken)
                .build()
                .toUriString();

        emailService.sendPasswordResetEmail(user.getEmail(), user.getName(), link, ttlMinutes);
    }

    /**
     * Cheap pre-check so the frontend can show "link expired" before rendering the form.
     */
    @Transactional(readOnly = true)
    public void validateToken(String rawToken) {
        tokenRepository.findByTokenHash(hash(rawToken))
                .filter((t) -> t.isUsable())
                .orElseThrow(() -> new InvalidPasswordResetTokenException());
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        PasswordResetToken token = tokenRepository.findByTokenHash(hash(rawToken))
                .filter((t) -> t.isUsable())
                .orElseThrow(() -> new InvalidPasswordResetTokenException());

        User user = token.getUser();

        if (user.getAuthProvider() != AuthProvider.LOCAL) {
            throw new InvalidPasswordResetTokenException();
        }

        Instant now = Instant.now();

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Marks the token just used AND every other outstanding one, in a single
        // statement. flushAutomatically pushes the user save first.
        tokenRepository.invalidateActiveTokens(user.getId(), now);

        // Invalidate all previous refresh tokens
        refreshTokenRepository.revokeAllForUser(user.getId(), Instant.now());


        emailService.sendPasswordChangedEmail(user.getEmail(), user.getName());

        log.info("Password reset completed for user id {}", user.getId());
    }

    private boolean isWithinCooldown(Long userId) {
        return tokenRepository.findTopByUserIdOrderByCreatedAtDesc(userId)
                .map(lastToken -> Duration.between(lastToken.getCreatedAt(), Instant.now()).getSeconds() < cooldownSeconds)
                .orElse(false);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256, not BCrypt. The token already carries 256 bits of entropy so
     * there is nothing to brute-force, and we need a deterministic digest to
     * look the row up by. BCrypt's salt would make lookup impossible.
     */
    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}