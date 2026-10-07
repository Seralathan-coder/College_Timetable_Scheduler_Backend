package com.erp.timetable.module.auth.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Generates, validates, and parses JWT access and refresh tokens.
 * Uses HS512 signing with a configurable secret key.
 */
@Component
@Slf4j
public class JwtTokenProvider {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiration-ms}")
    private long jwtExpirationMs;

    @Value("${app.jwt.refresh-expiration-ms}")
    private long refreshExpirationMs;

    // ── Token Generation ───────────────────────────────────────────────

    public String generateAccessToken(Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        List<String> roles = principal.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .toList();
        return buildToken(principal.getUsername(), principal.getId(), principal.getEmail(),
            roles, principal.getCollegeId());
    }

    public String generateRefreshToken() {
        return UUID.randomUUID().toString();
    }

    public String generateAccessTokenFromUsername(String username, Long userId,
                                                   String email, List<String> roles, Long collegeId) {
        return buildToken(username, userId, email, roles, collegeId);
    }

    /**
     * Builds the access JWT. The collegeId is informational context only —
     * authorization always reads the DB-loaded {@link User} per request.
     */
    private String buildToken(String username, Long userId, String email,
                              List<String> roles, Long collegeId) {
        var token = Jwts.builder()
            .subject(username)
            .claim("userId", userId)
            .claim("email", email)
            .claim("roles", roles);
        if (collegeId != null) {
            token.claim("collegeId", collegeId);
        }
        return token.issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + jwtExpirationMs))
            .signWith(getSigningKey())
            .compact();
    }

    // ── Token Parsing ──────────────────────────────────────────────────

    public String getUsernameFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    public Long getUserIdFromToken(String token) {
        return parseClaims(token).get("userId", Long.class);
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException ex) {
            log.warn("JWT token expired: {}", ex.getMessage());
        } catch (UnsupportedJwtException ex) {
            log.warn("Unsupported JWT token: {}", ex.getMessage());
        } catch (MalformedJwtException ex) {
            log.warn("Malformed JWT token: {}", ex.getMessage());
        } catch (io.jsonwebtoken.security.SecurityException ex) {
            log.warn("Invalid JWT signature: {}", ex.getMessage());
        } catch (IllegalArgumentException ex) {
            log.warn("JWT claims string is empty: {}", ex.getMessage());
        }
        return false;
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private Claims parseClaims(String token) {
        return Jwts.parser()
            .verifyWith(getSigningKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(
            java.util.Base64.getEncoder().encodeToString(jwtSecret.getBytes()));
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public long getRefreshExpirationMs() {
        return refreshExpirationMs;
    }
}
