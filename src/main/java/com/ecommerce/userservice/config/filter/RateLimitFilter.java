package com.ecommerce.userservice.config.filter;

import com.ecommerce.userservice.config.ratelimit.RateLimitProperties;
import com.ecommerce.userservice.config.ratelimit.RateLimitRule;
import com.ecommerce.userservice.exception.ErrorResponse;
import com.ecommerce.userservice.exception.RateLimitExceededException;
import com.ecommerce.userservice.service.RateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@NullMarked
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Map<String, RateLimitRule> POST_RULES = Map.of(
            "/api/auth/login", RateLimitRule.LOGIN_IP,
            "/api/auth/register", RateLimitRule.REGISTER_IP,
            "/api/auth/forgot-password", RateLimitRule.FORGOT_IP,
            "/api/auth/reset-password", RateLimitRule.RESET_IP
    );

    private static final Map<String, RateLimitRule> GET_RULES = Map.of(
            "/api/auth/reset-password/validate", RateLimitRule.VALIDATE_IP
    );

    private final RateLimitService rateLimitService;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;


    @Autowired
    public RateLimitFilter(RateLimitService rateLimitService,
                           RateLimitProperties properties,
                           ObjectMapper objectMapper) {
        this.rateLimitService = rateLimitService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * shouldNotFilter comes from OncePerRequestFilter. Spring calls it first.
     * Return true and doFilterInternal never runs — the request goes straight to the next filter.
     * <p>
     * Your line returns true in two cases:
     * <p>
     * "OPTIONS".equalsIgnoreCase(request.getMethod())
     * <p>
     * OPTIONS is the CORS preflight. Before a real cross-origin POST, the browser sends an OPTIONS request asking
     * permission. So one login from your React app is actually two HTTP requests:
     * <p>
     * OPTIONS /api/auth/login    ← browser asking permission
     * POST    /api/auth/login    ← the real one
     * <p>
     * Without this check, each login would consume two tokens instead of one, silently halving every limit you configured.
     *
     **/
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod()) || ruleFor(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            rateLimitService.check(ruleFor(request), clientIp(request));
        } catch (RateLimitExceededException e) {
            sendRateLimitErrorResponse(request, response, e);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Helper method to get the rate limit RULE for a given request.
     *
     **/
    private RateLimitRule ruleFor(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return switch (request.getMethod()) {
            case "POST" -> POST_RULES.get(uri);
            case "GET" -> GET_RULES.get(uri);
            default -> null;
        };
    }

    /**
     * Helper method to get the CLIENT IP address.
     *
     **/
    private String clientIp(HttpServletRequest request) {
        if (properties.isTrustProxyHeaders()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                String first = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
                if (!first.isEmpty()) {
                    return first;
                }
            }
        }
        String remote = request.getRemoteAddr();
        return remote != null ? remote : "unknown";
    }


    /**
     * use to send rate limit error response.
     *
     **/
    private void sendRateLimitErrorResponse(HttpServletRequest request,
                                            HttpServletResponse response,
                                            RateLimitExceededException e) throws IOException {

        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                HttpStatus.TOO_MANY_REQUESTS.value(),
                HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                e.getMessage(),
                request.getRequestURI(),
                List.of()
        );

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()));

        objectMapper.writeValue(response.getWriter(), body);
    }
}