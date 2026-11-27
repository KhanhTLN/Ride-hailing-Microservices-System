package com.threecats.userservice.security;

import com.threecats.userservice.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.access-token-expiration:900000}")
    private long accessTokenExpiration;

    @Value("${jwt.refresh-token-expiration:604800000}")
    private long refreshTokenExpiration;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(UUID id, Role role) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(accessTokenExpiration);

        List<String> roles = Collections.singletonList(role != null ? role.name() : "PASSENGER");

        return Jwts.builder()
                .subject(id.toString())
                .id(UUID.randomUUID().toString())
                .issuer("identity-service")
                .audience().add("ds03-api").and()
                .claim("token_use", "access")
                .claim("roles", roles)
                .claim("role", role != null ? role.name() : "PASSENGER")
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(getSigningKey())
                .compact();
    }

    public String generateRefreshToken(UUID id) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(refreshTokenExpiration);

        return Jwts.builder()
                .subject(id.toString())
                .id(UUID.randomUUID().toString())
                .issuer("identity-service")
                .claim("token_use", "refresh")
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(getSigningKey())
                .compact();
    }

    public ResponseCookie createRefreshTokenCookie(String refreshToken) {
        return ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true)
                .secure(false)
                .path("/api/v1/auth")
                .maxAge(refreshTokenExpiration / 1000)
                .sameSite("Lax")
                .build();
    }

    public long getAccessTokenExpirationInSeconds() {
        return accessTokenExpiration / 1000;
    }

    public String getUserIdFromJwt(String token) {
        return parseClaims(token).getSubject();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .requireIssuer("identity-service")
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Claims parseRefreshClaims(String token) {
        Claims claims=parseClaims(token);
        if (!"refresh".equals(claims.get("token_use"))) throw new IllegalArgumentException("Refresh token required");
        return claims;
    }

    public boolean validateAccessToken(String token) {
        try {
            Claims c=parseClaims(token);
            return "access".equals(c.get("token_use")) && c.getAudience()!=null && c.getAudience().contains("ds03-api");
        } catch (RuntimeException e) { return false; }
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
