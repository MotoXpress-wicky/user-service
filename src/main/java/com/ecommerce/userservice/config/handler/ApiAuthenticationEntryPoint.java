package com.ecommerce.userservice.config.handler;

import com.ecommerce.userservice.exception.ErrorCode;
import com.ecommerce.userservice.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Runs when Spring Security blocks an unauthenticated request.
 * JwtAuthenticationFilter leaves the exact reason on the request, so the body
 * can say ACCESS_TOKEN_EXPIRED instead of a plain UNAUTHENTICATED.
 */
@NullMarked
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Autowired
    public ApiAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        ErrorCode errorCode = ErrorCode.UNAUTHENTICATED;

        Object attribute = request.getAttribute(ErrorCode.REQUEST_ATTRIBUTE);
        if (attribute instanceof ErrorCode found) {
            errorCode = found;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // 401
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        objectMapper.writeValue(
                response.getOutputStream(),
                ErrorResponse.of(HttpStatus.UNAUTHORIZED, errorCode, request.getRequestURI()));
    }
}
