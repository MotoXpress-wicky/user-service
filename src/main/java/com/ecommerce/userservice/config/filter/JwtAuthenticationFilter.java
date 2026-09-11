package com.ecommerce.userservice.config.filter;

import com.ecommerce.userservice.exception.ErrorCode;
import com.ecommerce.userservice.dto.CurrentUser;
import com.ecommerce.userservice.entity.Role;
import com.ecommerce.userservice.service.AuthCookieService;
import com.ecommerce.userservice.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@NullMarked
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final AuthCookieService authCookieService;

    @Autowired
    public JwtAuthenticationFilter(JwtUtil jwtUtil, AuthCookieService authCookieService) {
        this.jwtUtil = jwtUtil;
        this.authCookieService = authCookieService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<String> token = authCookieService.read(request);

        if (token.isEmpty()) {
            fail(request, ErrorCode.ACCESS_TOKEN_MISSING);
            filterChain.doFilter(request, response);
            return;
        }

        try {
            /* Parsing jwt automatically verifies signature and expiration.
             *  When jwt is expired, expiration exception is thrown.
             *  When it is invalid, illegal argument exception is thrown.
             *  we catch these exception separately and set ErrorCode as attribute to the request.
             *  Then, inside entry point, entry point query the attribute and render the error response.
             * */
            Claims claims = jwtUtil.extractAllClaims(token.get());

            List<String> roleNames = List.of(claims.get("roles", String.class).split(","));

            CurrentUser currentUser = CurrentUser.builder()
                    .id(Long.parseLong(claims.getSubject()))
                    .email(claims.get("email", String.class))
                    .name(claims.get("name", String.class))
                    .roles(roleNames.stream().map(Role::valueOf).toList())
                    .build();

            List<SimpleGrantedAuthority> authorities =
                    roleNames.stream().map(SimpleGrantedAuthority::new).toList();

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(currentUser, null, authorities);

            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (ExpiredJwtException e) {
            fail(request, ErrorCode.ACCESS_TOKEN_EXPIRED);
            log.debug("Access token expired for {}", request.getRequestURI());
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid access token for {}: {}", request.getRequestURI(), e.getMessage());
            fail(request, ErrorCode.ACCESS_TOKEN_INVALID);
        }

        filterChain.doFilter(request, response);
    }

    //Store ErrorCode as attribute to the request
    private void fail(HttpServletRequest request, ErrorCode code) {
        SecurityContextHolder.clearContext();
        request.setAttribute(ErrorCode.REQUEST_ATTRIBUTE, code);
    }
}