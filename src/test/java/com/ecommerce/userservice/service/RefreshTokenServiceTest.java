package com.ecommerce.userservice.service;

import com.ecommerce.userservice.exception.ErrorCode;
import com.ecommerce.userservice.config.security.AuthTokenException;
import com.ecommerce.userservice.entity.RefreshToken;
import com.ecommerce.userservice.entity.Role;
import com.ecommerce.userservice.entity.User;
import com.ecommerce.userservice.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for RefreshTokenService - the rotation rules in particular.
 * <p>
 * This is the most security-sensitive class in the service, so it gets the
 * most attention. The three failure modes (unknown token, replayed token,
 * expired token) all look similar from the outside but must behave very
 * differently, and only one of them is allowed to nuke the user's sessions.
 * <p>
 * Note the @BeforeEach: tokenTtlMinutes normally arrives from @Value, and
 *
 * @Value does nothing outside a Spring context, so we set it by hand.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private RefreshTokenService refreshTokenService;

    @Captor
    private ArgumentCaptor<RefreshToken> tokenCaptor;

    @BeforeEach
    void setTtlThatSpringWouldNormallyInject() {
        ReflectionTestUtils.setField(refreshTokenService, "tokenTtlMinutes", 60L);
    }

    // ---------------------------------------------------------------------
    // issue
    // ---------------------------------------------------------------------

    /*
     * Check that the raw refresh token is returned to the caller, but only its
     * SHA-256 hash is stored in the database. This prevents database
     * exposing usable refresh tokens for attackers.
     */
    @Test
    @DisplayName("issue give a raw token but only stores its hash")
    void issueStoresTheHashAndReturnsTheRawValue() {
        User user = user(42L);

        String rawToken = refreshTokenService.issue(user);

        verify(refreshTokenRepository).save(tokenCaptor.capture());
        RefreshToken stored = tokenCaptor.getValue();

        assertThat(rawToken).isNotBlank();
        assertThat(stored.getTokenHash()).isEqualTo(sha256(rawToken));
        assertThat(stored.getTokenHash()).isNotEqualTo(rawToken);
        assertThat(stored.getUser()).isSameAs(user);
        assertThat(stored.getRevokedAt()).isNull();
    }


    /*
     * Check that each call generates a different refresh token.
     */
    @Test
    @DisplayName("generates a different token each time")
    void issueProducesADifferentTokenEveryTime() {
        User user = user(42L);

        String first = refreshTokenService.issue(user);
        String second = refreshTokenService.issue(user);

        assertThat(first).isNotEqualTo(second);
    }

    // ---------------------------------------------------------------------
    // rotate - the happy path
    // ---------------------------------------------------------------------

    /*
     * Check that a valid refresh token is revoked and a new token is returned.
     */
    @Test
    @DisplayName("rotating a valid token revokes the old one and returns a new one")
    void rotateBurnsTheOldTokenAndIssuesAReplacement() {
        User user = user(42L);
        String rawToken = "a-perfectly-good-token";
        RefreshToken stored = storedToken(user, rawToken, Instant.now().plus(Duration.ofMinutes(30)), null);

        when(refreshTokenRepository.findByTokenHash(sha256(rawToken))).thenReturn(Optional.of(stored));

        RefreshTokenService.RotationResult result = refreshTokenService.rotate(rawToken);

        assertThat(stored.getRevokedAt()).isNotNull();
        assertThat(result.user()).isSameAs(user);
        assertThat(result.refreshToken()).isNotBlank();
        assertThat(result.refreshToken()).isNotEqualTo(rawToken);
    }

    /*
     * Check that the repository lookup uses the SHA-256 hash instead of the raw
     * refresh token. This ensures the raw token is never used in the database query.
     */
    @Test
    @DisplayName("rotate looks the row up by hash, never by the raw token")
    void rotateQueriesUsingTheDigest() {
        User user = user(42L);
        String rawToken = "another-good-token";
        RefreshToken stored = storedToken(user, rawToken, Instant.now().plus(Duration.ofMinutes(30)), null);

        ArgumentCaptor<String> lookupCaptor = ArgumentCaptor.forClass(String.class);
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(stored));

        refreshTokenService.rotate(rawToken);

        verify(refreshTokenRepository).findByTokenHash(lookupCaptor.capture());
        assertThat(lookupCaptor.getValue()).isNotEqualTo(rawToken);
        assertThat(lookupCaptor.getValue()).isEqualTo(sha256(rawToken));
    }

    // ---------------------------------------------------------------------
    // rotate - the failure modes
    // ---------------------------------------------------------------------

    /*
     * Check that an unknown refresh token is rejected. Since the token does not
     * belong to any stored session, no existing sessions should be revoked and
     * no new token should be saved.
     */
    @Test
    @DisplayName("an unknown token is rejected without punishing the user's other sessions")
    void rotateRejectsATokenItHasNeverSeen() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refreshTokenService.rotate("no-such-token"))
                .isInstanceOf(AuthTokenException.class)
                .satisfies(thrown -> assertThat(((AuthTokenException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID));

        verify(refreshTokenRepository, never()).revokeAllForUser(anyLong(), any(Instant.class));
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    /*
     * Check that reusing an already-revoked token is rejected and all sessions
     * for that user are revoked. This helps protect the account if a refresh
     * token has been stolen and reused.
     */
    @Test
    @DisplayName("replaying a spent token revokes every session that user has")
    void rotateTreatsAReusedTokenAsATheftAndRevokesEverything() {
        User user = user(42L);
        String rawToken = "already-used-token";
        RefreshToken stored = storedToken(
                user,
                rawToken,
                Instant.now().plus(Duration.ofMinutes(30)),
                Instant.now().minus(Duration.ofMinutes(5))   // revokedAt is set - it was spent already
        );

        when(refreshTokenRepository.findByTokenHash(sha256(rawToken))).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> refreshTokenService.rotate(rawToken))
                .isInstanceOf(AuthTokenException.class)
                .satisfies(thrown -> assertThat(((AuthTokenException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.REFRESH_TOKEN_REUSED));

        verify(refreshTokenRepository).revokeAllForUser(eq(42L), any(Instant.class));
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    /*
     * Check that an expired refresh token is rejected without revoking the
     * user's other sessions. An expired token is only invalid, not a reused token.
     * user can login again and carry on
     */
    @Test
    @DisplayName("an expired token is rejected but nothing is revoked in bulk")
    void rotateRejectsAnExpiredTokenWithoutOverreacting() {
        User user = user(42L);
        String rawToken = "stale-token";
        RefreshToken stored = storedToken(user, rawToken, Instant.now().minus(Duration.ofMinutes(1)), null);

        when(refreshTokenRepository.findByTokenHash(sha256(rawToken))).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> refreshTokenService.rotate(rawToken))
                .isInstanceOf(AuthTokenException.class)
                .satisfies(thrown -> assertThat(((AuthTokenException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.REFRESH_TOKEN_EXPIRED));

        verify(refreshTokenRepository, never()).revokeAllForUser(anyLong(), any(Instant.class));
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    // ---------------------------------------------------------------------
    // revoke
    // ---------------------------------------------------------------------

    // check if revoked token is marked as revoked
    @Test
    @DisplayName("revoking a live token stamps it as revoked")
    void revokeStampsTheToken() {
        User user = user(42L);
        Instant notRevokedYet = null;
        RefreshToken stored = storedToken(user, "live-token",
                Instant.now().plus(Duration.ofMinutes(30)), notRevokedYet);

        when(refreshTokenRepository.findByTokenHash(sha256("live-token")))
                .thenReturn(Optional.of(stored));

        refreshTokenService.revoke("live-token");

        assertThat(stored.getRevokedAt()).isNotNull();
    }

    // check if invalid token does not throw any error
    @Test
    @DisplayName("revoking an unknown token does nothing and does not throw")
    void revokeIgnoresUnknownTokens() {
        when(refreshTokenRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.empty());

        assertThatCode(() -> refreshTokenService.revoke("garbage"))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private User user(Long id) {
        return User.builder()
                .id(id)
                .name("Nimal Perera")
                .email("nimal@example.com")
                .roles(List.of(Role.ROLE_USER))
                .build();
    }

    private RefreshToken storedToken(User user, String rawToken, Instant expiresAt, Instant revokedAt) {
        return RefreshToken.builder()
                .id(1L)
                .user(user)
                .tokenHash(sha256(rawToken))
                .expiresAt(expiresAt)
                .revokedAt(revokedAt)
                .build();
    }

    /**
     * Mirrors the private hash() inside the service. Duplicating it here is
     * deliberate - if someone changes the algorithm in the service, these tests
     * should fail loudly rather than quietly follow along.
     */
    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
