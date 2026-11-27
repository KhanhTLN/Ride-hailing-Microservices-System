package com.threecats.userservice.security;

import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {
    private JwtTokenProvider jwt;
    private final java.util.UUID user=java.util.UUID.randomUUID();
    @BeforeEach void setup() {
        jwt=new JwtTokenProvider();
        ReflectionTestUtils.setField(jwt,"jwtSecret","unit-test-access-refresh-secret-0123456789-abcdefghijklmnopqrstuvwxyz");
        ReflectionTestUtils.setField(jwt,"accessTokenExpiration",900000L);
        ReflectionTestUtils.setField(jwt,"refreshTokenExpiration",604800000L);
    }
    @Test void accessTokenHasIdentityAndAudience() {
        String token=jwt.generateAccessToken(user,com.threecats.userservice.enums.Role.PASSENGER);
        var claims=jwt.parseClaims(token);
        assertTrue(jwt.validateAccessToken(token));
        assertEquals(user.toString(),claims.getSubject());
        assertTrue(claims.getAudience().contains("ds03-api"));
        assertEquals("access",claims.get("token_use"));
    }
    @Test void accessTokenCannotRefresh() {
        String token=jwt.generateAccessToken(user,com.threecats.userservice.enums.Role.DRIVER);
        assertThrows(IllegalArgumentException.class,() -> jwt.parseRefreshClaims(token));
    }
    @Test void refreshTokenCannotAuthenticateAnApi() {
        String token=jwt.generateRefreshToken(user);
        assertFalse(jwt.validateAccessToken(token));
        assertEquals("refresh",jwt.parseRefreshClaims(token).get("token_use"));
    }
    @Test void refreshTokensAreUnique() { assertNotEquals(jwt.generateRefreshToken(user),jwt.generateRefreshToken(user)); }
    @Test void expiredAccessTokenIsRejected() {
        ReflectionTestUtils.setField(jwt,"accessTokenExpiration",-1000L);
        assertFalse(jwt.validateAccessToken(jwt.generateAccessToken(user,com.threecats.userservice.enums.Role.PASSENGER)));
    }
}
