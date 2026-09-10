package com.ecommerce.userservice.controller;

import com.ecommerce.userservice.config.security.AuthErrorCode;
import com.ecommerce.userservice.config.security.AuthTokenException;
import com.ecommerce.userservice.dto.*;
import com.ecommerce.userservice.entity.Role;
import com.ecommerce.userservice.entity.User;
import com.ecommerce.userservice.exception.InvalidPasswordResetTokenException;
import com.ecommerce.userservice.exception.UserAlreadyExistsException;
import com.ecommerce.userservice.service.*;
import com.ecommerce.userservice.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web layer tests for AuthController.
 * <p>
 * Different shape from the service tests on purpose. The controller methods
 * are two or three lines each - all the behaviour worth checking lives in the
 * annotations, and annotations do nothing unless Spring's dispatcher is in
 * front of them. So these go in through MockMvc: real request mapping, real
 * JSON binding, real @Valid, real GlobalExceptionHandler. Only the services
 * underneath are faked.
 * <p>
 * addFilters = false switches off the security filter chain. That is deliberate
 * - these tests are about the HTTP contract of each endpoint, not about which
 * URLs are protected. Which URLs are protected belongs in a @SpringBootTest,
 * where the real SecurityConfig is loaded.
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private AuthCookieService authCookieService;

    @MockitoBean
    private RefreshCookieService refreshCookieService;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    @MockitoBean
    private PasswordResetService passwordResetService;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private RateLimitService rateLimitService;

    @BeforeEach
    void clearAnyLeftoverSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearSecurityContextAfterwards() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------------
    // POST /api/auth/register
    // ---------------------------------------------------------------------

    /*
     * Nothing clever here - the controller hands the request to AuthService and
     * returns whatever string comes back. Worth one test to confirm the route
     * is mapped and the JSON body actually binds onto RegisterRequest.
     */
    @Test
    @DisplayName("POST /register returns 200 and the service's message")
    void registerReturnsTheServiceMessage() throws Exception {
        when(authService.register(any(RegisterRequestDto.class))).thenReturn("User registered successfully");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(registerRequest("Nimal Perera", "nimal@example.com", "sup3r-secret"))))
                .andExpect(status().isOk())
                .andExpect(content().string("User registered successfully"));
    }

    /*
     * The duplicate-email case, and the reason a controller test earns its
     * keep. UserAlreadyExistsException is thrown deep in the service; whether
     * the browser sees a 409 or a 500 depends entirely on GlobalExceptionHandler
     * being wired into the dispatcher. Calling the controller method directly
     * would never tell you that.
     */
    @Test
    @DisplayName("a duplicate email surfaces as 409 with the error body, not a 500")
    void duplicateEmailBecomesAConflictResponse() throws Exception {
        when(authService.register(any(RegisterRequestDto.class)))
                .thenThrow(new UserAlreadyExistsException("User with email: taken@example.com already exists"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(registerRequest("Copycat", "taken@example.com", "whatever"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("User with email: taken@example.com already exists"))
                .andExpect(jsonPath("$.path").value("/api/auth/register"));
    }

    // ---------------------------------------------------------------------
    // POST /api/auth/login
    // ---------------------------------------------------------------------

    /*
     * The login contract in full. Two Set-Cookie headers have to go out - one
     * access, one refresh - and both must be HttpOnly, because the whole point
     * of putting tokens in cookies rather than localStorage is that JavaScript
     * cannot read them. Setting one header instead of two is a classic bug
     * here: ResponseEntity.header() appends, but a careless refactor to
     * .headers() would silently drop one.
     */
    @Test
    @DisplayName("POST /login sets both auth cookies and returns the user")
    void loginSetsBothCookiesAndReturnsTheUser() throws Exception {
        User user = user(7L, "Nimal Perera", "nimal@example.com");

        when(authService.login(any(LoginRequest.class))).thenReturn(Map.of(
                "user", user,
                "authToken", "signed.jwt.value",
                "refreshToken", "raw-refresh-token"
        ));
        when(authCookieService.create("signed.jwt.value")).thenReturn(cookie("auth_token", "signed.jwt.value"));
        when(refreshCookieService.create("raw-refresh-token")).thenReturn(cookie("refresh_token", "raw-refresh-token"));

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(loginRequest("nimal@example.com", "sup3r-secret"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.name").value("Nimal Perera"))
                .andExpect(jsonPath("$.email").value("nimal@example.com"))
                .andReturn();

        List<String> setCookieHeaders = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);

        assertThat(setCookieHeaders).hasSize(2);
        assertThat(setCookieHeaders).anySatisfy(header -> assertThat(header).contains("auth_token=signed.jwt.value"));
        assertThat(setCookieHeaders).anySatisfy(header -> assertThat(header).contains("refresh_token=raw-refresh-token"));
        assertThat(setCookieHeaders).allSatisfy(header -> assertThat(header).contains("HttpOnly"));
    }

    /*
     * The response body is built from AuthResponse, which has no password
     * field - but DTOs get edited, and someone swapping it for the User entity
     * to "save a mapping step" would leak the bcrypt hash to every browser on
     * every login. The two doesNotExist checks are cheap insurance against a
     * change nobody would think to test.
     */
    @Test
    @DisplayName("the login response body carries no password or token fields")
    void loginResponseLeaksNothingSensitive() throws Exception {
        User user = user(7L, "Nimal Perera", "nimal@example.com");

        when(authService.login(any(LoginRequest.class))).thenReturn(Map.of(
                "user", user,
                "authToken", "signed.jwt.value",
                "refreshToken", "raw-refresh-token"
        ));
        when(authCookieService.create(anyString())).thenReturn(cookie("auth_token", "signed.jwt.value"));
        when(refreshCookieService.create(anyString())).thenReturn(cookie("refresh_token", "raw-refresh-token"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(loginRequest("nimal@example.com", "sup3r-secret"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.authToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    // ---------------------------------------------------------------------
    // GET /api/auth/me
    // ---------------------------------------------------------------------

    /*
     * The frontend calls this on every page load to find out who it is talking
     * to. When the filter has put a CurrentUser in the security context, that
     * user comes straight back - no database trip, which is the point of
     * stuffing the name and email into the JWT in the first place.
     *
     * We put the principal into SecurityContextHolder by hand because the JWT
     * filter is switched off in this slice. MockMvc runs on the calling thread,
     * so the ThreadLocal the argument resolver reads is the one we just set.
     */
    @Test
    @DisplayName("GET /me echoes back the authenticated principal")
    void meReturnsTheCurrentUser() throws Exception {
        CurrentUser principal = CurrentUser.builder()
                .id(7L)
                .name("Nimal Perera")
                .email("nimal@example.com")
                .roles(List.of(Role.ROLE_USER))
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.email").value("nimal@example.com"))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"));
    }

    /*
     * No principal in the context means nobody is logged in, and the controller
     * has an explicit null check for exactly that. It must be a 401 - the
     * frontend's auth store keys off this to decide between showing the app and
     * bouncing to the login page.
     */
    @Test
    @DisplayName("GET /me with nobody logged in returns 401")
    void meReturnsUnauthorizedWithoutAPrincipal() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------------
    // POST /api/auth/refresh
    // ---------------------------------------------------------------------

    /*
     * A successful rotation. The browser gets a fresh pair of cookies, and the
     * refresh cookie in particular must carry the NEW value - handing back the
     * old one would mean the next refresh replays a spent token, which the
     * rotation logic treats as theft and responds to by logging the user out of
     * everything. A one-word mistake here turns into users being mysteriously
     * signed out.
     */
    @Test
    @DisplayName("POST /refresh rotates the cookies and returns the user")
    void refreshIssuesANewCookiePair() throws Exception {
        User user = user(7L, "Nimal Perera", "nimal@example.com");

        when(refreshCookieService.read(any(HttpServletRequest.class))).thenReturn(Optional.of("old-refresh-token"));
        when(refreshTokenService.rotate("old-refresh-token"))
                .thenReturn(new RefreshTokenService.RotationResult(user, "new-refresh-token"));
        when(jwtUtil.generateToken(7L, "Nimal Perera", "nimal@example.com", List.of(Role.ROLE_USER)))
                .thenReturn("new.jwt.value");
        when(authCookieService.create("new.jwt.value")).thenReturn(cookie("auth_token", "new.jwt.value"));
        when(refreshCookieService.create("new-refresh-token")).thenReturn(cookie("refresh_token", "new-refresh-token"));

        MvcResult result = mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andReturn();

        List<String> setCookieHeaders = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);

        assertThat(setCookieHeaders).hasSize(2);
        assertThat(setCookieHeaders).anySatisfy(header -> assertThat(header).contains("refresh_token=new-refresh-token"));
        assertThat(setCookieHeaders).noneSatisfy(header -> assertThat(header).contains("old-refresh-token"));
    }

    /*
     * A refresh with no cookie at all. The controller throws AuthTokenException
     * with REFRESH_TOKEN_MISSING, and the handler answers 401 with a body that
     * includes a "refreshable" flag - the frontend interceptor reads that flag
     * to decide whether to retry or give up, so it matters that it is there and
     * that it is false. Nothing should be rotated on this path.
     */
    @Test
    @DisplayName("POST /refresh without a cookie returns 401 with the MISSING code")
    void refreshWithoutACookieIsUnauthorized() throws Exception {
        when(refreshCookieService.read(any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(AuthErrorCode.REFRESH_TOKEN_MISSING.name()))
                .andExpect(jsonPath("$.refreshable").value(false));

        verify(refreshTokenService, never()).rotate(anyString());
    }

    /*
     * A replayed refresh token. Two things have to be true in the response:
     * a 401 carrying the REUSED code, and clearing cookies on the way out so
     * the browser stops sending the dead pair on every subsequent request.
     */
    @Test
    @DisplayName("a reused refresh token returns 401 and clears both cookies")
    void reusedRefreshTokenClearsTheCookies() throws Exception {
        when(refreshCookieService.read(any(HttpServletRequest.class))).thenReturn(Optional.of("stolen-token"));
        when(refreshTokenService.rotate("stolen-token"))
                .thenThrow(new AuthTokenException(AuthErrorCode.REFRESH_TOKEN_REUSED));
        when(authCookieService.clear()).thenReturn(expiredCookie("auth_token"));
        when(refreshCookieService.clear()).thenReturn(expiredCookie("refresh_token"));

        MvcResult result = mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(AuthErrorCode.REFRESH_TOKEN_REUSED.name()))
                .andReturn();

        List<String> setCookieHeaders = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);

        assertThat(setCookieHeaders).hasSize(2);
        assertThat(setCookieHeaders).allSatisfy(header -> assertThat(header).contains("Max-Age=0"));
    }

    // ---------------------------------------------------------------------
    // POST /api/auth/logout
    // ---------------------------------------------------------------------

    /*
     * Logout returns 204 with no body and two expiring cookies. Max-Age=0 is
     * the part that matters - a cookie without it just sits in the browser
     * until it ages out on its own, and the user stays logged in on a machine
     * they thought they had left.
     */
    @Test
    @DisplayName("POST /logout returns 204 and expires both cookies")
    void logoutExpiresBothCookies() throws Exception {
        when(authCookieService.clear()).thenReturn(expiredCookie("auth_token"));
        when(refreshCookieService.clear()).thenReturn(expiredCookie("refresh_token"));

        MvcResult result = mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent())
                .andReturn();

        List<String> setCookieHeaders = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);

        assertThat(setCookieHeaders).hasSize(2);
        assertThat(setCookieHeaders).allSatisfy(header -> assertThat(header).contains("Max-Age=0"));
        assertThat(result.getResponse().getContentAsString()).isEmpty();
    }

    // ---------------------------------------------------------------------
    // POST /api/auth/forgot-password
    // ---------------------------------------------------------------------

    /*
     * The privacy guarantee, expressed as a test. A known address and a
     * completely unknown one must produce byte-for-byte the same response - so
     * rather than asserting on each separately, we capture both bodies and
     * compare them directly. If anyone ever adds a helpful "no account found"
     * message, this fails immediately.
     */
    @Test
    @DisplayName("forgot-password answers identically for a known and an unknown address")
    void forgotPasswordGivesTheSameAnswerEitherWay() throws Exception {
        MvcResult known = mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(forgotPasswordRequest("nimal@example.com"))))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult unknown = mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(forgotPasswordRequest("nobody@example.com"))))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(known.getResponse().getContentAsString())
                .isEqualTo(unknown.getResponse().getContentAsString());
        assertThat(known.getResponse().getStatus())
                .isEqualTo(unknown.getResponse().getStatus());
    }

    /*
     * @Valid on ForgotPasswordRequest is the only thing standing between the
     * service and a pile of junk input. This checks that a malformed address is
     * rejected at the edge with a 400, that the field-level message makes it
     * into the details array, and - the part people forget - that the service
     * was never called at all.
     */
    @Test
    @DisplayName("a malformed email is rejected with 400 before the service is touched")
    void forgotPasswordRejectsAMalformedEmail() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(forgotPasswordRequest("definitely-not-an-email"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Please check the submitted data."))
                .andExpect(jsonPath("$.details[0]").value("email: Enter a valid email"));

        verifyNoInteractions(passwordResetService);
    }

    /*
     * Same idea for a blank submission - the @NotBlank message should come
     * through rather than a bare 400 with an empty body, because the form
     * renders whatever is in details.
     */
    @Test
    @DisplayName("a blank email produces the required-field message")
    void forgotPasswordRejectsABlankEmail() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(forgotPasswordRequest(""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details").isNotEmpty());

        verifyNoInteractions(passwordResetService);
    }

    // ---------------------------------------------------------------------
    // GET /api/auth/reset-password/validate
    // ---------------------------------------------------------------------

    /*
     * Check whether a good token return 204.
     * Check passwordResetService is called
     */
    @Test
    @DisplayName("valid password reset token returns a bare 204")
    void validateResetTokenAcceptsAGoodToken() throws Exception {

        mockMvc.perform(get("/api/auth/reset-password/validate").param("token", "good-token"))
                .andExpect(status().isNoContent());

        verify(passwordResetService).validateToken("good-token");


    }

    /*
     * A dead token has to come back as a 400, not a 500, because the frontend
     * shows a friendly "this link expired" screen for 4xx and a scary error page
     * for 5xx. That mapping lives entirely in GlobalExceptionHandler.
     */
    @Test
    @DisplayName("an expired reset token returns 400 with the generic message")
    void validateResetTokenRejectsADeadToken() throws Exception {
        doThrow(new InvalidPasswordResetTokenException())
                .when(passwordResetService).validateToken("dead-token");

        mockMvc.perform(get("/api/auth/reset-password/validate").param("token", "dead-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("This password reset link is invalid or has expired."));
    }

    // ---------------------------------------------------------------------
    // POST /api/auth/reset-password
    // ---------------------------------------------------------------------

    /*
     * After a reset the old access cookie is worthless, and leaving it in the
     * browser means the user sits in a half-logged-in state until it expires.
     * So the response clears it and tells them to sign in again. One cookie
     * here, not two - the refresh cookie is dealt with server side by burning
     * the tokens.
     */
    @Test
    @DisplayName("a successful reset clears the auth cookie and asks for a fresh sign-in")
    void resetPasswordClearsTheAuthCookie() throws Exception {
        when(authCookieService.clear()).thenReturn(expiredCookie("auth_token"));

        MvcResult result = mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(resetPasswordRequest("good-token", "brand-new-password"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password updated. Please sign in."))
                .andReturn();

        List<String> setCookieHeaders = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);

        assertThat(setCookieHeaders).hasSize(1);
        assertThat(setCookieHeaders).allSatisfy(header -> assertThat(header).contains("Max-Age=0"));
        verify(passwordResetService).resetPassword("good-token", "brand-new-password");
    }

    /*
     * A password below the minimum length never reaches the service. Note what
     * this is really testing: not that eight characters is the right number,
     * but that the @Size rule on the DTO is actually being enforced. Drop the
     * @Valid off the controller parameter and this test goes red while the
     * service tests all stay green.
     */
    @Test
    @DisplayName("a too-short password is rejected at the edge")
    void resetPasswordRejectsAShortPassword() throws Exception {
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(resetPasswordRequest("good-token", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("newPassword: Password must be at least 8 characters"));

        verifyNoInteractions(passwordResetService);
    }

    /*
     * And a request with no token at all. Same treatment - blocked before the
     * service sees it.
     */
    @Test
    @DisplayName("a reset with a missing token is rejected before the service runs")
    void resetPasswordRejectsAMissingToken() throws Exception {
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(resetPasswordRequest("", "brand-new-password"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details").isNotEmpty());

        verifyNoInteractions(passwordResetService);
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private RegisterRequest registerRequest(String name, String email, String password) {
        RegisterRequest request = new RegisterRequest();
        request.setName(name);
        request.setEmail(email);
        request.setPassword(password);
        return request;
    }

    private LoginRequest loginRequest(String email, String password) {
        LoginRequest request = new LoginRequest();
        request.setEmail(email);
        request.setPassword(password);
        return request;
    }

    private ForgotPasswordRequest forgotPasswordRequest(String email) {
        ForgotPasswordRequest request = new ForgotPasswordRequest();
        request.setEmail(email);
        return request;
    }

    private ResetPasswordRequest resetPasswordRequest(String token, String newPassword) {
        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setToken(token);
        request.setNewPassword(newPassword);
        return request;
    }

    private User user(Long id, String name, String email) {
        return User.builder()
                .id(id)
                .name(name)
                .email(email)
                .password("$2a$10$encoded-version")
                .roles(List.of(Role.ROLE_USER))
                .build();
    }

    private ResponseCookie cookie(String name, String value) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofMinutes(15))
                .build();
    }

    private ResponseCookie expiredCookie(String name) {
        return ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
    }
}
