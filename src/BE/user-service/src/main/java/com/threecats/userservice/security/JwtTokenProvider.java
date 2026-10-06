package com.threecats.userservice.security;

import io.jsonwebtoken.Jwts;
import com.threecats.userservice.enums.Role;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.access-token-expiration}")
    private long accessTokenExpiration;

    @Value("${jwt.refresh-token-expiration}")
    private long refreshTokenExpiration;

    private Key getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(UUID id, Role role) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiry = now.plusSeconds(accessTokenExpiration / 1000);

        Date issuedAt = Date.from(now.toInstant());
        Date expiration = Date.from(expiry.toInstant());

        return Jwts.builder()
                .setSubject(String.valueOf(id))
                .claim("role", role)
                .setIssuedAt(issuedAt)
                .setExpiration(expiration)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }
    public String generateRefreshToken(UUID id) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiry = now.plusSeconds(accessTokenExpiration / 1000);

        Date issuedAt = Date.from(now.toInstant());
        Date expiration = Date.from(expiry.toInstant());

        return Jwts.builder()
                .setSubject(String.valueOf(id))
                .setIssuedAt(issuedAt)
                .setExpiration(expiration)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }


    public ResponseCookie createRefreshTokenCookie(String refreshToken) {
        return ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true)       // Chống truy cập từ JavaScript (XSS Protection)
                .secure(false)        // Đổi thành true khi chạy HTTPS
                .path("/api/auth") // Chỉ gửi cookie này khi gọi các endpoint auth (refresh, logout)
                .maxAge(refreshTokenExpiration / 1000)
                .sameSite("Lax")
                .build();
    }

    public long getAccessTokenExpirationInSeconds() {
        return accessTokenExpiration / 1000;
    }
}
