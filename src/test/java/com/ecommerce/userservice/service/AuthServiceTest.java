package com.ecommerce.userservice.service;

import com.ecommerce.userservice.dto.LoginRequest;
import com.ecommerce.userservice.dto.RegisterRequestDto;
import com.ecommerce.userservice.entity.Role;
import com.ecommerce.userservice.entity.User;
import com.ecommerce.userservice.exception.UserAlreadyExistsException;
import com.ecommerce.userservice.exception.UserEmailNotFoundException;
import com.ecommerce.userservice.repository.UserRepository;
import com.ecommerce.userservice.util.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private RefreshTokenService refreshTokenService;

    @InjectMocks
    private AuthService authService;

    @Captor
    private ArgumentCaptor<User> savedUserCaptor;

    @Captor
    private ArgumentCaptor<UsernamePasswordAuthenticationToken> authTokenCaptor;

    // ---------------------------------------------------------------------
    // register
    // ---------------------------------------------------------------------


    // check if register saves the encoded password, not the raw one.
    @Test
    @DisplayName("register stores the encoded password, never the raw one")
    void registerHashesThePasswordBeforeSaving() {
        RegisterRequestDto requestData = registerRequest("Nimal Perera", "nimal@example.com", "sup3r-secret");

        when(userRepository.existsByEmail("nimal@example.com")).thenReturn(false);
        when(passwordEncoder.encode("sup3r-secret")).thenReturn("$2a$10$encoded-version");

        String result = authService.register(requestData);

        verify(userRepository).save(savedUserCaptor.capture());
        User saved = savedUserCaptor.getValue();

        assertThat(saved.getPassword()).isEqualTo("$2a$10$encoded-version");
        assertThat(saved.getPassword()).isNotEqualTo("sup3r-secret");
        assertThat(saved.getEmail()).isEqualTo("nimal@example.com");
        assertThat(saved.getName()).isEqualTo("Nimal Perera");
        assertThat(result).isEqualTo("User registered successfully");
    }

    /*
     * A new User should automatically get ROLE_DEFAULT and the LOCAL auth provider
     * because of the defaults defined in the entity. This test makes sure those
     * defaults are still applied. If they are accidentally removed, signup could
     * create users without the expected role or provider and cause authorization
     * problems later.
     */
    @Test
    @DisplayName("When user register using email and password, he/she must have ROLE_DEFAULT and LOCAL as Auth Provider")
    void registerAppliesTheEntityDefaults() {
        RegisterRequestDto requestData = registerRequest("Amara", "amara@example.com", "another-secret");

        when(userRepository.existsByEmail("amara@example.com")).thenReturn(false);
        when(passwordEncoder.encode("another-secret")).thenReturn("encoded");

        authService.register(requestData);

        verify(userRepository).save(savedUserCaptor.capture());
        User saved = savedUserCaptor.getValue();

        assertThat(saved.getRoles()).containsExactly(Role.ROLE_DEFAULT);
        assertThat(saved.getAuthProvider().name()).isEqualTo("LOCAL");
    }

    /*
     * A user should not be able to register with an email that is already in use.
     * This test makes sure the correct exception is thrown and that we do not
     * continue with password encoding or saving the user to the database.
     */
    @Test
    @DisplayName("register method must throw UserAlreadyExists Exception if email already in the db")
    void registerRejectsAnEmailThatIsAlreadyTaken() {
        RegisterRequestDto requestData = registerRequest("Copycat", "taken@example.com", "whatever");

        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(requestData))
                .isInstanceOf(UserAlreadyExistsException.class)
                // The message is shown to the user, so it stays short and does not
                // repeat the email back. See AuthService.register.
                .hasMessage("Please use login instead.");

        verify(userRepository, never()).save(any(User.class)); // Tell mokito to,verify that userRepository.save() was never called with a User.
        verifyNoInteractions(passwordEncoder); // Tell mokito to,verify that o method called on passwordEncoder.
    }

    // ---------------------------------------------------------------------
    // login
    // ---------------------------------------------------------------------

    /*
     * This is the successful login case. AuthService does not validate the
     * password itself; it relies on AuthenticationManager for that. This test
     * mainly checks that AuthService puts everything together correctly and
     * returns the user, access token, and refresh token using the keys expected
     * by the controller.
     */
    @Test
    @DisplayName("a successful login returns the user plus both tokens")
    void loginReturnsUserAndTokens() {
        LoginRequest request = loginRequest("nimal@example.com", "sup3r-secret");
        User user = localUser(7L, "Nimal Perera", "nimal@example.com");

        when(userRepository.findByEmail("nimal@example.com")).thenReturn(Optional.of(user));
        when(jwtUtil.generateToken(7L, "Nimal Perera", "nimal@example.com", List.of(Role.ROLE_USER)))
                .thenReturn("signed.jwt.value");
        when(refreshTokenService.issue(user)).thenReturn("raw-refresh-token");

        Map<String, Object> result = authService.login(request);

        assertThat(result.get("user")).isSameAs(user);
        assertThat(result.get("authToken")).isEqualTo("signed.jwt.value");
        assertThat(result.get("refreshToken")).isEqualTo("raw-refresh-token");
    }

    /*
     * Check that the email and password are passed to the AuthenticationManager
     * in the correct order. If we accidentally swap them, the authentication
     * will fail even when the user enters the correct credentials.
     */
    @Test
    @DisplayName("login forwards the submitted credentials to the AuthenticationManager untouched")
    void loginPassesTheCredentialsThroughAsGiven() {
        LoginRequest request = loginRequest("nimal@example.com", "sup3r-secret");
        User user = localUser(7L, "Nimal Perera", "nimal@example.com");

        when(userRepository.findByEmail("nimal@example.com")).thenReturn(Optional.of(user));
        when(jwtUtil.generateToken(7L, "Nimal Perera", "nimal@example.com", List.of(Role.ROLE_USER)))
                .thenReturn("signed.jwt.value");
        when(refreshTokenService.issue(user)).thenReturn("raw-refresh-token");

        authService.login(request);

        verify(authenticationManager).authenticate(authTokenCaptor.capture());
        UsernamePasswordAuthenticationToken submitted = authTokenCaptor.getValue();

        assertThat(submitted.getPrincipal()).isEqualTo("nimal@example.com");
        assertThat(submitted.getCredentials()).isEqualTo("sup3r-secret");
    }

    /*
     * Check the wrong password case. AuthenticationManager should throw an
     * BadCredentialsException when the password is incorrect.When this exception happen
     * access token and refresh token shouldn't be created
     */
    @Test
    @DisplayName("when login credentials wrong no tokens issued")
    void loginWithWrongPasswordIssuesNothing() {
        LoginRequest request = loginRequest("nimal@example.com", "wrong-password");

        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BadCredentialsException.class);

        verifyNoInteractions(jwtUtil);
        verifyNoInteractions(refreshTokenService);
    }

    /*
     * If the user enters an email that does not exist, login should fail with
     * UserEmailNotFoundException. We also make sure that no refresh token is
     * issued when the user cannot be found.
     */
    @Test
    @DisplayName("login fails when the email does not exist")
    void loginFailsWhenEmailDoesNotExist() {
        LoginRequest request = loginRequest("ghost@example.com", "sup3r-secret");

        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(UserEmailNotFoundException.class);

        verifyNoInteractions(refreshTokenService);
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private RegisterRequestDto registerRequest(String name, String email, String password) {
        RegisterRequestDto requestData = new RegisterRequestDto();
        requestData.setName(name);
        requestData.setEmail(email);
        requestData.setPassword(password);
        return requestData;
    }

    private LoginRequest loginRequest(String email, String password) {
        LoginRequest request = new LoginRequest();
        request.setEmail(email);
        request.setPassword(password);
        return request;
    }

    private User localUser(Long id, String name, String email) {
        return User.builder()
                .id(id)
                .name(name)
                .email(email)
                .password("$2a$10$encoded-version")
                .roles(List.of(Role.ROLE_USER))
                .build();
    }
}
