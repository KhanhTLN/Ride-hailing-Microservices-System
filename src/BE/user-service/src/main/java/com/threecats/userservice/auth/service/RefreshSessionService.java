package com.threecats.userservice.auth.service;

import com.threecats.userservice.auth.entity.RefreshSession;
import com.threecats.userservice.auth.repository.RefreshSessionRepository;
import com.threecats.userservice.security.JwtTokenProvider;
import com.threecats.common.exception.authenAndAuthor.UnauthorizedException;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class RefreshSessionService {
    private final RefreshSessionRepository repository;
    private final JwtTokenProvider jwt;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public record Rotation(UUID userId, String token) {}

    @Transactional
    public String issue(UUID userId) { return issue(userId, UUID.randomUUID()); }

    private String issue(UUID userId, UUID family) {
        String token=jwt.generateRefreshToken(userId);
        RefreshSession session=new RefreshSession();
        session.setTokenHash(hash(token)); session.setUserId(userId); session.setFamilyId(family);
        session.setExpiresAt(jwt.parseRefreshClaims(token).getExpiration().toInstant());
        repository.save(session);
        return token;
    }

    @Transactional(noRollbackFor=UnauthorizedException.class)
    public Rotation rotate(String token) {
        Claims claims=claims(token);
        lockFamily(token);
        RefreshSession session=repository.lock(hash(token)).orElseThrow(this::invalid);
        if (session.getRevokedAt()!=null) {
            repository.revokeFamily(session.getFamilyId(),Instant.now());
            throw invalid();
        }
        if (session.getExpiresAt().isBefore(Instant.now()) || !session.getUserId().toString().equals(claims.getSubject())) throw invalid();
        session.setRevokedAt(Instant.now());
        repository.saveAndFlush(session);
        return new Rotation(session.getUserId(),issue(session.getUserId(),session.getFamilyId()));
    }

    @Transactional
    public void logout(String token) {
        // Unknown/already revoked tokens are idempotent; access tokens are rejected.
        if(repository.family(hash(token)).isEmpty()) claims(token);
        lockFamily(token);
        repository.lock(hash(token)).ifPresent(s -> repository.revokeFamily(s.getFamilyId(),Instant.now()));
    }

    private Claims claims(String token) {
        try { return jwt.parseRefreshClaims(token); }
        catch (RuntimeException e) { throw invalid(); }
    }
    private void lockFamily(String token) {
        repository.family(hash(token)).ifPresent(family -> jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,17))",Object.class,family.toString()));
    }
    private UnauthorizedException invalid() { return new UnauthorizedException("Invalid, expired or revoked refresh token"); }
    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
