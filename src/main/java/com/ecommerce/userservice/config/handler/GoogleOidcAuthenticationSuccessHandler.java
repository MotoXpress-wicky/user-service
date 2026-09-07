package com.ecommerce.userservice.config.handler;


import com.ecommerce.userservice.entity.AuthProvider;
import com.ecommerce.userservice.entity.User;
import com.ecommerce.userservice.repository.UserRepository;
import com.ecommerce.userservice.service.AuthCookieService;
import com.ecommerce.userservice.service.RefreshCookieService;
import com.ecommerce.userservice.service.RefreshTokenService;
import com.ecommerce.userservice.util.JwtUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@NullMarked
@Slf4j
@Component
public class GoogleOidcAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final AuthCookieService authCookieService;
    private final RefreshCookieService refreshCookieService;
    private final RefreshTokenService refreshTokenService;

    @Value("${frontend.success-redirect-url}")
    private String frontendRedirectUrl;

    @Autowired
    public GoogleOidcAuthenticationSuccessHandler(UserRepository userRepository
            , JwtUtil jwtUtil
            , AuthCookieService authCookieService
            , RefreshCookieService refreshCookieService
            , RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
        this.authCookieService = authCookieService;
        this.refreshCookieService = refreshCookieService;
        this.refreshTokenService = refreshTokenService;
    }


    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,

                                        Authentication authentication) throws IOException, ServletException {

        // 1. Because we used the "openid" scope, Spring returns an OidcUser, not a generic OAuth2User.
        // By the time this code runs, Spring has ALREADY verified the signature using Google's public keys.
        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();

        OidcIdToken idToken = oidcUser.getIdToken();
        String email = idToken.getEmail();
        String name = idToken.getClaim("name");

        User user = userRepository.findByEmail(email).orElseGet(() -> {
            User newUser = User.builder()
                    .name(name)
                    .email(email)
                    .authProvider(AuthProvider.GOOGLE)
                    .build();
            return userRepository.save(newUser);
        });

        if (user.isLocked()) {
            log.warn("Google sign-in blocked for locked account: userId={}", user.getId());
            throw new LockedException("Account locked");
        }

        String appAuthToken = jwtUtil.generateToken(user.getId(), user.getName(), user.getEmail(), user.getRoles());
        ResponseCookie authCookie = authCookieService.create(appAuthToken);

        String appRefreshToken = refreshTokenService.issue(user);
        ResponseCookie refreshCookie = refreshCookieService.create(appRefreshToken);

        response.addHeader(HttpHeaders.SET_COOKIE, authCookie.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
        log.info("Google sign-in successful: userId={}", user.getId());                       // (4)

        response.sendRedirect(frontendRedirectUrl);


    }

}
