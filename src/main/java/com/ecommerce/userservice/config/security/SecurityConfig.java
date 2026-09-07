package com.ecommerce.userservice.config.security;


import com.ecommerce.userservice.config.handler.ApiAuthenticationEntryPoint;
import com.ecommerce.userservice.config.handler.GoogleOidcAuthenticationFailureHandler;
import com.ecommerce.userservice.config.handler.GoogleOidcAuthenticationSuccessHandler;
import com.ecommerce.userservice.config.filter.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final GoogleOidcAuthenticationSuccessHandler googleOidcAuthenticationSuccessHandler;
    private final GoogleOidcAuthenticationFailureHandler googleOidcAuthenticationFailureHandler;
    private final ApiAuthenticationEntryPoint apiAuthenticationEntryPoint;

    @Autowired
    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            GoogleOidcAuthenticationSuccessHandler googleOidcAuthenticationSuccessHandler,
            GoogleOidcAuthenticationFailureHandler googleOidcAuthenticationFailureHandler,
            ApiAuthenticationEntryPoint apiAuthenticationEntryPoint) {

        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.googleOidcAuthenticationSuccessHandler = googleOidcAuthenticationSuccessHandler;
        this.googleOidcAuthenticationFailureHandler = googleOidcAuthenticationFailureHandler;
        this.apiAuthenticationEntryPoint = apiAuthenticationEntryPoint;
    }


    /*
       1. Request with no JWT token / Invalid jwt token / expired jwt token
       ↓
    2. JwtAuthenticationFilter:
       - Finds no token
       - Does NOT set authentication
       - Passes to next filter (filterChain.doFilter)
       ↓
    3. Request reaches authorizeHttpRequests
       - Checks: Is this route protected? → YES (/api/users)
       - Checks: Is user authenticated? → NO
       ↓
    4. Spring Security calls AuthenticationEntryPoint
       - Checks: Does URI start with /api/* ? → YES
       ↓
    5. apiAuthenticationEntryPoint() returns 401 JSON
    * */

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .headers(headers -> headers.frameOptions(frame -> frame.disable()))
                // 1. Disable CSRF (not needed for stateless REST APIs using JWT)
                .csrf(csrf -> csrf.disable())
                // 2. Disable form-based login
                .formLogin(form -> form.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                // 3. Make session management stateless (no HttpSession cookies)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .exceptionHandling(exception -> exception
                        .defaultAuthenticationEntryPointFor(
                               /* When authenticationEntryPoint is triggered:
                                  1. Request to PROTECTED route (requires authentication)
                                  2. User is NOT authenticated
                                  3. THEN → AuthenticationEntryPoint is called
                                */
                                apiAuthenticationEntryPoint,
                                request -> request.getRequestURI().startsWith("/api/")
                        )
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .oauth2Login(oauth2 -> oauth2
                        .successHandler(googleOidcAuthenticationSuccessHandler)
                        .failureHandler(googleOidcAuthenticationFailureHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/h2-console/**").permitAll()
                        .requestMatchers(
                                "/api/auth/login"
                                , "/api/auth/register"
                                , "/api/auth/refresh"
                                , "/api/auth/logout"
                                , "/api/auth/forgot-password"
                                , "/api/auth/reset-password"
                                , "/api/auth/reset-password/validate"
                        ).permitAll()
                        .anyRequest().authenticated()
                );

        return http.build();

    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
