package com.ecommerce.userservice.controller;

import com.ecommerce.userservice.config.security.AuthErrorCode;
import com.ecommerce.userservice.config.security.AuthTokenException;
import com.ecommerce.userservice.dto.*;
import com.ecommerce.userservice.entity.User;
import com.ecommerce.userservice.service.*;
import com.ecommerce.userservice.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthCookieService authCookieService;
    private final RefreshCookieService refreshCookieService;
    private final RefreshTokenService refreshTokenService;
    private final PasswordResetService passwordResetService;
    private final JwtUtil jwtUtil;
    private static final String GENERIC_FORGOT_RESPONSE = "Reset link has been sent to your email.";

    private static AuthResponse toAuthResponse(User user) {
        return AuthResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .roles(user.getRoles())
                .build();
    }

    private static AuthResponse toAuthResponse(CurrentUser user) {
        return AuthResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .roles(user.getRoles())
                .build();
    }


    @Autowired
    public AuthController(AuthService authService, AuthCookieService authCookieService, RefreshCookieService refreshCookieService, RefreshTokenService refreshTokenService, JwtUtil jwtUtil, PasswordResetService passwordResetService) {
        this.authService = authService;
        this.authCookieService = authCookieService;
        this.refreshCookieService = refreshCookieService;
        this.refreshTokenService = refreshTokenService;
        this.jwtUtil = jwtUtil;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/register")
    public ResponseEntity<String> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }


    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        Map<String, Object> loginResult = authService.login(request);

        User user = (User) loginResult.get("user");
        String authToken = (String) loginResult.get("authToken");
        String refreshToken = (String) loginResult.get("refreshToken");


        ResponseCookie authCookie = authCookieService.create(authToken);
        ResponseCookie refreshCookie = refreshCookieService.create(refreshToken);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(toAuthResponse(user));


    }

    @GetMapping("/me")
    public ResponseEntity<AuthResponse> me(@AuthenticationPrincipal CurrentUser currentUser) {
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return ResponseEntity.ok(toAuthResponse(currentUser));

    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest request) {
        String rawRefreshToken = refreshCookieService.read(request).orElseThrow(() -> new AuthTokenException(AuthErrorCode.REFRESH_TOKEN_MISSING));

        RefreshTokenService.RotationResult result = refreshTokenService.rotate(rawRefreshToken);
        User user = result.user();
        String refreshToken = result.refreshToken();

        String authToken = jwtUtil.generateToken(user.getId(), user.getName(), user.getEmail(), user.getRoles());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieService.create(authToken).toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookieService.create(refreshToken).toString())
                .body(toAuthResponse(user));

    }


    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, authCookieService.clear().toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookieService.clear().toString())
                .build();
    }


    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {

        passwordResetService.sendPasswordResetEmail(request.getEmail());

        // Same 200, same body, same timing — whether or not the account exists.
        return ResponseEntity.ok(Map.of("message", GENERIC_FORGOT_RESPONSE));
    }

    @GetMapping("/reset-password/validate")
    public ResponseEntity<Void> validateResetToken(@RequestParam String token) {
        passwordResetService.validateToken(token);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {

        passwordResetService.resetPassword(request.getToken(), request.getNewPassword());

        // Clear whatever auth cookie this browser holds and force a fresh login.
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieService.clear().toString())
                .body(Map.of("message", "Password updated. Please sign in."));
    }


}
