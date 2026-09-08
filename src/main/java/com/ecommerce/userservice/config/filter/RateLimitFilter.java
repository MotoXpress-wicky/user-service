package com.ecommerce.userservice.config.filter;

import com.ecommerce.userservice.config.ratelimit.RateLimitRule;
import com.ecommerce.userservice.exception.ErrorResponse;
import com.ecommerce.userservice.exception.RateLimitExceededException;
import com.ecommerce.userservice.service.RateLimitService;
import com.ecommerce.userservice.util.ClientIpResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Applies every IP-based limit, as early as it is safe to do so.
 * <p>
 * WHY A FILTER, AND WHY ONLY IP HERE
 * <p>
 * A request body can only be read once. If this filter parsed the JSON to find
 * the email address, Spring would later find an empty body and every
 * {@code @RequestBody} binding would fail. So email-based limits live in
 * AuthController instead, where the DTO is already parsed. IP is available from
 * the connection itself, so it can be checked here - before Spring Security,
 * before JSON parsing, and most importantly before BCrypt burns ~80ms of CPU.
 * <p>
 * WHY IT WRITES ITS OWN RESPONSE
 * <p>
 * GlobalExceptionHandler is invoked by the DispatcherServlet, which sits
 * downstream of the filter chain. An exception thrown here would never reach
 * it - the browser would get a bare container error page instead of JSON. So
 * the filter renders the same ErrorResponse shape by hand. This is the same
 * constraint JwtAuthenticationFilter works around with its request attribute
 * and entry point.
 * <p>
 * WHERE IT IS REGISTERED
 * <p>
 * SecurityConfig places this AFTER CorsFilter, on purpose. Registered before
 * it, a 429 would go out with no CORS headers, the browser would block the
 * response, and the React app would show a confusing network error instead of
 * "too many attempts". Every endpoint being limited here is permitAll anyway,
 * so running a few filters later costs nothing.
 */
@NullMarked
@Slf4j
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Map<String, RateLimitRule> POST_RULES = Map.of(
            "/api/auth/login", RateLimitRule.LOGIN_IP,
            "/api/auth/register", RateLimitRule.REGISTER_IP,
            "/api/auth/forgot-password", RateLimitRule.FORGOT_IP,
            "/api/auth/reset-password", RateLimitRule.RESET_IP,
            "/api/auth/refresh", RateLimitRule.REFRESH_IP
    );

    private static final Map<String, RateLimitRule> GET_RULES = Map.of(
            "/api/auth/reset-password/validate", RateLimitRule.VALIDATE_IP
    );

    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimitService rateLimitService,
                           ClientIpResolver clientIpResolver,
                           ObjectMapper objectMapper) {
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        /*
         * OPTIONS is the CORS preflight the browser sends before the real
         * request. Counting it would mean every genuine cross-origin call
         * silently costs two tokens, halving every limit you configured.
         */
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        return uri.startsWith("/h2-console");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String ip = clientIpResolver.resolve(request);

        try {
            // The blanket net first. Cheap: it is held in local memory, so
            // there is no Redis round trip on the common path.
            rateLimitService.check(RateLimitRule.GLOBAL_IP, ip);

            RateLimitRule endpointRule = ruleFor(request);
            if (endpointRule != null) {
                rateLimitService.check(endpointRule, ip);
            }

        } catch (RateLimitExceededException e) {
            writeTooManyRequests(request, response, e);
            return;   // the request stops here - nothing downstream runs
        }

        filterChain.doFilter(request, response);
    }

    private RateLimitRule ruleFor(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return switch (request.getMethod()) {
            case "POST" -> POST_RULES.get(uri);
            case "GET" -> GET_RULES.get(uri);
            default -> null;
        };
    }

    private void writeTooManyRequests(HttpServletRequest request,
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
        // Standard header telling a well-behaved client how long to back off.
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()));

        objectMapper.writeValue(response.getWriter(), body);
    }
}
