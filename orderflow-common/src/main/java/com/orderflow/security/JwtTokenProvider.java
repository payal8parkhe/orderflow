package com.orderflow.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JwtTokenProvider.class);
    public static final String DEFAULT_SECRET = "OrderFlowSecretKeyForJWTTokenGenerationWhichMustBeAtLeast256BitsLong!";

    private final SecretKey secretKey;
    private final long jwtExpirationInMs;

    @Autowired
    public JwtTokenProvider(
            @Value("${app.jwt.secret:" + DEFAULT_SECRET + "}") String secret,
            @Value("${app.jwt.expiration-ms:86400000}") long jwtExpirationInMs,
            @Autowired(required = false) Environment env) {
        if (env != null) {
            boolean isProduction = Arrays.stream(env.getActiveProfiles())
                    .anyMatch(p -> p.equalsIgnoreCase("prod") || p.equalsIgnoreCase("production"));
            if (isProduction && DEFAULT_SECRET.equals(secret)) {
                throw new IllegalStateException("CRITICAL SECURITY ERROR: Production deployment requires a custom APP_JWT_SECRET environment variable. Default development secret is strictly prohibited in production.");
            }
        }
        if (DEFAULT_SECRET.equals(secret)) {
            log.warn("SECURITY NOTICE: Using default development JWT secret. Set APP_JWT_SECRET for production environments.");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.jwtExpirationInMs = jwtExpirationInMs;
    }

    public JwtTokenProvider(String secret, long jwtExpirationInMs) {
        this(secret, jwtExpirationInMs, null);
    }

    public String generateToken(Authentication authentication) {
        String username = authentication.getName();
        String role = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst()
                .orElse("ROLE_CUSTOMER");

        return generateToken(username, role);
    }

    public String generateToken(String username, String role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationInMs);

        return Jwts.builder()
                .subject(username)
                .claim("role", role != null ? role : "ROLE_CUSTOMER")
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(secretKey)
                .compact();
    }

    public String getUsernameFromJwt(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }

    public String getRoleFromJwt(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.get("role", String.class);
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
