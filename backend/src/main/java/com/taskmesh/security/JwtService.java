package com.taskmesh.security;

import com.taskmesh.config.TaskMeshProperties;
import com.taskmesh.domain.User;
import com.taskmesh.domain.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issue and verify HS256 JSON Web Tokens (jjwt 0.12).
 *
 * <p>Bearer tokens carry the user id as subject plus username and role claims and are
 * valid for 12 hours. If the configured secret is shorter than 256 bits it is run through
 * SHA-256 once to obtain a full-size HMAC key; this keeps the documented development
 * default secret usable while remaining deterministic.</p>
 */
@Component
public class JwtService {

    /** Token lifetime per SPEC §8: 12 hours = 43200 seconds. */
    public static final Duration TOKEN_TTL = Duration.ofHours(12);
    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_ROLE = "role";

    private final SecretKey key;

    public JwtService(TaskMeshProperties properties) {
        byte[] secret = properties.jwtSecret().getBytes(StandardCharsets.UTF_8);
        this.key = Keys.hmacShaKeyFor(secret.length >= 32 ? secret : sha256(secret));
    }

    public String issueToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim(CLAIM_USERNAME, user.getUsername())
                .claim(CLAIM_ROLE, user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TOKEN_TTL)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Verifies signature and expiry and decodes the principal.
     *
     * @return the authenticated user, or {@code null} if the token is invalid or expired
     */
    public AuthenticatedUser parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return new AuthenticatedUser(
                    UUID.fromString(claims.getSubject()),
                    claims.get(CLAIM_USERNAME, String.class),
                    UserRole.valueOf(claims.get(CLAIM_ROLE, String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public long tokenValiditySeconds() {
        return TOKEN_TTL.toSeconds();
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
