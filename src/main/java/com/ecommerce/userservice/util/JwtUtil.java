package com.ecommerce.userservice.util;

import com.ecommerce.userservice.entity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class JwtUtil {

    @Value("${auth.token.secret}")
    private String secret;

    @Value("${auth.token.ttl-minutes}")
    private long tokenTtlMinutes;

    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    public String generateToken(Long userId, String userName, String email, List<Role> roles) {

        String roleList = roles.stream().map(Role::name).collect(Collectors.joining(","));

        long issuedAtMs = System.currentTimeMillis();
        long expiresAtMs = issuedAtMs + Duration.ofMinutes(tokenTtlMinutes).toMillis();

        return Jwts.builder()
                .claim("roles", roleList)
                .claim("name", userName)
                .claim("email", email)
                .subject(userId.toString())
                .issuedAt(new Date(issuedAtMs))
                .expiration(new Date(expiresAtMs))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Parses AND verifies the signature. Throws ExpiredJwtException when past the exp
     * claim, JwtException when malformed or wrongly signed. Callers must distinguish
     * the two - that difference is what tells the client whether refreshing is worth trying.
     */
    public Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }


}