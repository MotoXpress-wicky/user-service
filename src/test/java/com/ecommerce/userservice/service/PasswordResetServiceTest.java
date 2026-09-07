package com.ecommerce.userservice.service;

import com.ecommerce.userservice.entity.AuthProvider;
import com.ecommerce.userservice.entity.PasswordResetToken;
import com.ecommerce.userservice.entity.Role;
import com.ecommerce.userservice.entity.User;
import com.ecommerce.userservice.exception.InvalidPasswordResetTokenException;
import com.ecommerce.userservice.repository.PasswordResetTokenRepository;
import com.ecommerce.userservice.repository.RefreshTokenRepository;
import com.ecommerce.userservice.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for PasswordResetService.
 * <p>
 * Most of this class is about what does NOT happen. Three separate situations
 * - unknown address, Google account, still inside the cooldown - all end with
 * the method returning quietly, because saying anything different would tell a
 * stranger which addresses have accounts. Tests that assert on silence are
 * unusual, but that silence is the actual feature here.
 * <p>
 * The @Value fields are set by hand in @BeforeEach; without a Spring context
 * they would be null and the URL builder would blow up.
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordResetTokenRepository tokenRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private PasswordResetService passwordResetService;

    @Captor
    private ArgumentCaptor<PasswordResetToken> tokenCaptor;

    @Captor
    private ArgumentCaptor<User> userCaptor;

    @Captor
    private ArgumentCaptor<String> linkCaptor;

    @BeforeEach
    void setConfigValuesThatSpringWouldNormallyInject() {
        ReflectionTestUtils.setField(passwordResetService, "resetPasswordUrl", "https://shop.example.com/reset-password");
        ReflectionTestUtils.setField(passwordResetService, "ttlMinutes", 30L);
        ReflectionTestUtils.setField(passwordResetService, "cooldownSeconds", 60L);
    }

    // ---------------------------------------------------------------------
    // sendPasswordResetEmail
    // ---------------------------------------------------------------------

    /*
     * Check that the password reset token sent in the email is stored in the
     * database as a hash, not as the raw token. We take the token from the
     * email link, hash it, and make sure it matches the token hash saved in
     * the database.
     */
    @Test
    @DisplayName("check hash(email_sended_raw_token) == token_hash saved in db")
    void sendResetEmailStoresOnlyTheHashOfTheEmailedToken() {
        User user = localUser(9L, "Nimal Perera", "nimal@example.com");
        when(userRepository.findByEmail("nimal@example.com")).thenReturn(Optional.of(user));

        passwordResetService.sendPasswordResetEmail("nimal@example.com");

        verify(tokenRepository).save(tokenCaptor.capture());
        verify(emailService).sendPasswordResetEmail(
                eq("nimal@example.com"), eq("Nimal Perera"), linkCaptor.capture(), eq(30L));

        String emailedLink = linkCaptor.getValue();
        String rawToken = emailedLink.substring(emailedLink.indexOf("token=") + "token=".length());

        assertThat(emailedLink).startsWith("https://shop.example.com/reset-password?token=");
        assertThat(rawToken).isNotBlank();
        assertThat(tokenCaptor.getValue().getTokenHash()).isEqualTo(sha256(rawToken));
        assertThat(tokenCaptor.getValue().getUser()).isSameAs(user);
    }

    /*
     * Asking for a new link should kill any earlier ones, so an old email
     * sitting in the inbox stops working the moment a newer one is requested.
     * This has to happen before the new row is written.
     */
    @Test
    @DisplayName("requesting a new link invalidates previous reset tokens")
    void sendResetEmailInvalidatesPreviousTokensFirst() {
        User user = localUser(9L, "Nimal Perera", "nimal@example.com");
        when(userRepository.findByEmail("nimal@example.com")).thenReturn(Optional.of(user));
        when(tokenRepository.findTopByUserIdOrderByCreatedAtDesc(9L)).thenReturn(Optional.empty());


        passwordResetService.sendPasswordResetEmail("nimal@example.com");


        verify(tokenRepository).invalidateActiveTokens(eq(9L), any(Instant.class));

        // we did not stub the tokenRepository.save() because it's return value is never used. we stub methods only
        // whose return values are used
    }


    /*
     * If the email is not registered, the method should not throw an exception
     * or send an email. This makes sure we do not reveal whether an account
     * exists for a given email address.
     */
    @Test
    @DisplayName("an unknown email is ignored silently - no token, no email, no error")
    void sendResetEmailSaysNothingAboutUnknownAddresses() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatCode(() -> passwordResetService.sendPasswordResetEmail("nobody@example.com"))
                .doesNotThrowAnyException();

        verify(tokenRepository, never()).save(any(PasswordResetToken.class));
        verifyNoInteractions(emailService);
    }

    /*
     * Someone who signed in through Google has no local password to reset.
     * Same treatment as an unknown address - stop, say nothing. Replying with
     * "that's a Google account" would be handing out account details for free.
     */
    @Test
    @DisplayName("A Google Account is ignored silently - no token, no email, no error")
    void sendResetEmailIgnoresNonLocalAccounts() {
        User googleUser = localUser(11L, "Amara", "amara@example.com");
        googleUser.setAuthProvider(AuthProvider.GOOGLE);
        when(userRepository.findByEmail("amara@example.com")).thenReturn(Optional.of(googleUser));

        passwordResetService.sendPasswordResetEmail("amara@example.com");

        verify(tokenRepository, never()).save(any(PasswordResetToken.class));
        verifyNoInteractions(emailService);
    }

    /*
     * Check that another reset request (reset email) is ignored until cooldown has passed.
     */
    @Test
    @DisplayName("a second request inside the cooldown window is dropped")
    void sendResetEmailRespectsTheCooldown() {
        User user = localUser(9L, "Nimal Perera", "nimal@example.com");
        PasswordResetToken justIssued = tokenFor(user, "recent-token", Instant.now().plus(Duration.ofMinutes(30)), null, Instant.now());
//        justIssued.setCreatedAt(Instant.now().minusSeconds(5));   // as if the link went out moments ago

        when(userRepository.findByEmail("nimal@example.com")).thenReturn(Optional.of(user));
        when(tokenRepository.findTopByUserIdOrderByCreatedAtDesc(9L)).thenReturn(Optional.of(justIssued));

        passwordResetService.sendPasswordResetEmail("nimal@example.com");

        verify(tokenRepository, never()).save(any(PasswordResetToken.class));
        verifyNoInteractions(emailService);
    }

    /*
     * Check that the user can request another reset email after the cooldown
     * period has passed. The new token should be saved and a new email should
     * be sent normally.
     */
    @Test
    @DisplayName("sends a new reset email after the cooldown has passed")
    void sendResetEmailWorksAgainAfterTheCooldown() {
        User user = localUser(9L, "Nimal Perera", "nimal@example.com");
        PasswordResetToken older = tokenFor(
                user,
                "older-token",
                Instant.now().plus(Duration.ofMinutes(30)),
                null,
                Instant.now().minusSeconds(600)
        );

        when(userRepository.findByEmail("nimal@example.com"))
                .thenReturn(Optional.of(user));
        when(tokenRepository.findTopByUserIdOrderByCreatedAtDesc(9L))
                .thenReturn(Optional.of(older));

        passwordResetService.sendPasswordResetEmail("nimal@example.com");

        verify(tokenRepository).save(any(PasswordResetToken.class));
        verify(emailService).sendPasswordResetEmail(
                anyString(),
                anyString(),
                anyString(),
                anyLong()
        );
    }

// ---------------------------------------------------------------------
// validateToken
// ---------------------------------------------------------------------

    /*
     * Check that a valid, unused and non-expired token is accepted.
     */
    @Test
    @DisplayName("accepts a valid reset token")
    void validateTokenAcceptsAUsableToken() {
        User user = localUser(9L, "Nimal Perera", "nimal@example.com");
        PasswordResetToken token = tokenFor(
                user,
                "good-token",
                Instant.now().plus(Duration.ofMinutes(10)),
                null,
                Instant.now()
        );

        when(tokenRepository.findByTokenHash(sha256("good-token")))
                .thenReturn(Optional.of(token));

        assertThatCode(() -> passwordResetService.validateToken("good-token"))
                .doesNotThrowAnyException();
    }

    /*
     * Check that an already-used token is rejected.
     * The same generic error is used for all invalid tokens so we do not reveal
     * whether the token exists, has expired, or was already used. This prevents
     * attackers from getting useful information about reset tokens.
     */
    @Test
    @DisplayName("rejects an already-used token")
    void validateTokenRejectsASpentToken() {
        User user = localUser(9L, "Nimal Perera", "nimal@example.com");

        PasswordResetToken token = tokenFor(
                user,
                "spent-token",
                Instant.now().plus(Duration.ofMinutes(10)),
                Instant.now().minusSeconds(30),
                Instant.now().minusSeconds(60)
        );

        when(tokenRepository.findByTokenHash(sha256("spent-token")))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() ->
                passwordResetService.validateToken("spent-token")
        )
                .isInstanceOf(InvalidPasswordResetTokenException.class)
                .hasMessage("This password reset link is invalid or has expired.");
    }

    /*
     * Check that an expired token is rejected.
     * The same generic message is used so we do not reveal that the token
     * existed but has expired.
     */
    @Test
    @DisplayName("rejects an expired token")
    void validateTokenRejectsAnExpiredToken() {
        User user = localUser(9L, "Nimal Perera", "nimal@example.com");

        PasswordResetToken token = tokenFor(
                user,
                "old-token",
                Instant.now().minusSeconds(10),
                null,
                Instant.now().minusSeconds(60)
        );

        when(tokenRepository.findByTokenHash(sha256("old-token")))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() ->
                passwordResetService.validateToken("old-token")
        )
                .isInstanceOf(InvalidPasswordResetTokenException.class)
                .hasMessage("This password reset link is invalid or has expired.");
    }

    /*
     * Check that a token that does not exist is also rejected with the same
     * generic message.
     */
    @Test
    @DisplayName("rejects an unknown token")
    void validateTokenRejectsAnUnknownToken() {
        when(tokenRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                passwordResetService.validateToken("old-token")
        )
                .isInstanceOf(InvalidPasswordResetTokenException.class)
                .hasMessage("This password reset link is invalid or has expired.");
    }

    // ---------------------------------------------------------------------
    // resetPassword
    // ---------------------------------------------------------------------

    /*
     * Check that a successful password reset updates the password, invalidates
     * the active reset tokens, and sends the password-changed email.
     */
    @Test
    @DisplayName("successfully resets the password")
    void resetPasswordUpdatesEverythingItShould() {
        User user = localUser(9L, "Nimal Perera", "nimal@example.com");
        PasswordResetToken token = tokenFor(
                user,
                "valid-token",
                Instant.now().plus(Duration.ofMinutes(10)),
                null,
                Instant.now()
        );

        when(tokenRepository.findByTokenHash(sha256("valid-token")))
                .thenReturn(Optional.of(token));

        when(passwordEncoder.encode("brand-new-password"))
                .thenReturn("$2a$10$freshly-encoded");

        passwordResetService.resetPassword("valid-token", "brand-new-password");

        // Check that the new password is encoded before saving.
        verify(userRepository).save(userCaptor.capture());
        User saved = userCaptor.getValue();

        assertThat(saved.getPassword()).isEqualTo("$2a$10$freshly-encoded");
        assertThat(saved.getPassword()).isNotEqualTo("brand-new-password");

        /*
         * Check that invalidateActiveTokens() is called once with the correct
         * user ID and an Instant.
         * eq(9L) checks that the user ID is exactly 9.
         * any(Instant.class) accepts any Instant value.
         */
        verify(tokenRepository)
                .invalidateActiveTokens(eq(9L), any(Instant.class));

        verify(refreshTokenRepository).revokeAllForUser(eq(9L), any(Instant.class));

        // Check that the password-changed email is sent to the correct user.
        verify(emailService)
                .sendPasswordChangedEmail("nimal@example.com", "Nimal Perera");
    }

    /*
     * Check that a Google account cannot use the password reset flow.
     * Nothing should be changed and no email should be sent.
     */
    @Test
    @DisplayName("a reset aimed at a Google account is refused and changes nothing")
    void resetPasswordRefusesNonLocalAccounts() {
        User googleUser = localUser(11L, "Amara", "amara@example.com");
        googleUser.setAuthProvider(AuthProvider.GOOGLE);
        PasswordResetToken token = tokenFor(googleUser, "valid-token", Instant.now().plus(Duration.ofMinutes(10)), null, Instant.now().plusSeconds(60));

        when(tokenRepository.findByTokenHash(sha256("valid-token"))).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> passwordResetService.resetPassword("valid-token", "brand-new-password"))
                .isInstanceOf(InvalidPasswordResetTokenException.class);

        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(passwordEncoder);
        verifyNoInteractions(emailService);
    }

    /*
     * Check that an already-used reset token is rejected and nothing is changed.
     */
    @Test
    @DisplayName("a reset with an already-used token changes nothing")
    void resetPasswordRefusesASpentToken() {
        User user = localUser(9L, "Nimal Perera", "nimal@example.com");
        PasswordResetToken token = tokenFor(
                user, "spent-token", Instant.now().plus(Duration.ofMinutes(10)), Instant.now().minusSeconds(30), Instant.now());

        when(tokenRepository.findByTokenHash(sha256("spent-token"))).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> passwordResetService.resetPassword("spent-token", "brand-new-password"))
                .isInstanceOf(InvalidPasswordResetTokenException.class);

        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(emailService);
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private User localUser(Long id, String name, String email) {
        return User.builder()
                .id(id)
                .name(name)
                .email(email)
                .password("$2a$10$old-password")
                .roles(List.of(Role.ROLE_USER))
                .authProvider(AuthProvider.LOCAL)
                .build();
    }

    private PasswordResetToken tokenFor(User user, String rawToken, Instant expiresAt,
                                        Instant usedAt, Instant createdAt) {
        return PasswordResetToken.builder()
                .id(1L)
                .user(user)
                .tokenHash(sha256(rawToken))
                .expiresAt(expiresAt)
                .usedAt(usedAt)
                .createdAt(createdAt)
                .build();
    }

    /**
     * A copy of the service's private hash(). Kept separate on purpose so a
     * change to the algorithm shows up as a failing test.
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
