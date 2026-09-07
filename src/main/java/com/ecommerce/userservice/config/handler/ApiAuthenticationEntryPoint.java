package com.ecommerce.userservice.config.handler;

import com.ecommerce.userservice.config.security.AuthErrorCode;
import com.ecommerce.userservice.config.security.AuthErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

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

        AuthErrorCode errorCode = AuthErrorCode.UNAUTHENTICATED;

        Object attribute = request.getAttribute(AuthErrorCode.REQUEST_ATTRIBUTE);

        if (attribute instanceof AuthErrorCode) {
            errorCode = (AuthErrorCode) attribute;
        }


        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); //SC_UNAUTHORIZED = 401
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), AuthErrorResponse.create(errorCode));
    }


}
