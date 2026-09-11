package com.ecommerce.userservice.config.filter;

import com.ecommerce.userservice.config.ratelimit.RateLimitRule;
import com.ecommerce.userservice.exception.ErrorCode;
import com.ecommerce.userservice.exception.ErrorResponse;
import com.ecommerce.userservice.exception.RateLimitExceededException;
import com.ecommerce.userservice.service.RateLimitService;
import com.ecommerce.userservice.util.ClientIpResolver;
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
import java.util.Map;

/**
 * Counts requests per IP address and blocks the ones over the limit.
 * Runs before the controller, so a blocked request never reaches business code.
 */
@NullMarked
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String BASE = "/api/v1/user/auth";

    private static final Map<String, RateLimitRule> POST_RULES = Map.of(
            BASE + "/login", RateLimitRule.LOGIN_IP,
            BASE + "/register", RateLimitRule.REGISTER_IP,
            BASE + "/forgot-password", RateLimitRule.FORGOT_IP,
            BASE + "/reset-password", RateLimitRule.RESET_IP
    );

    private static final Map<String, RateLimitRule> GET_RULES = Map.of(
            BASE + "/reset-password/validate", RateLimitRule.VALIDATE_IP
    );

    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;

    @Autowired
    public RateLimitFilter(RateLimitService rateLimitService,
                           ClientIpResolver clientIpResolver,
                           ObjectMapper objectMapper) {
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * shouldNotFilter comes from OncePerRequestFilter. Spring calls it first.
     * Return true and doFilterInternal never runs - the request goes straight on.
     *
     * OPTIONS is the CORS preflight. Before a real cross-origin POST the browser
     * sends an OPTIONS request asking permission, so one login would be two
     * requests. Without this check every limit would silently be halved.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod()) || ruleFor(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            rateLimitService.check(ruleFor(request), clientIpResolver.resolve(request));
        } catch (RateLimitExceededException e) {
            sendRateLimitErrorResponse(request, response, e);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /** Which rule applies to this request, or null when none does. */
    private RateLimitRule ruleFor(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return switch (request.getMethod()) {
            case "POST" -> POST_RULES.get(uri);
            case "GET" -> GET_RULES.get(uri);
            default -> null;
        };
    }

    /**
     * A filter runs before the controller, so GlobalExceptionHandler cannot see
     * this error. The body is written by hand here, in the same shape.
     */
    private void sendRateLimitErrorResponse(HttpServletRequest request,
                                            HttpServletResponse response,
                                            RateLimitExceededException e) throws IOException {

        ErrorResponse body = ErrorResponse.of(
                HttpStatus.TOO_MANY_REQUESTS,
                ErrorCode.RATE_LIMITED,
                e.getMessage(),
                request.getRequestURI());

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()));

        objectMapper.writeValue(response.getWriter(), body);
    }
}
