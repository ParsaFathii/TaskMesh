package com.taskmesh.security;

import com.taskmesh.config.TaskMeshProperties;
import com.taskmesh.domain.User;
import com.taskmesh.domain.UserRole;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JWT issuance and verification: round-trip, expiry, tampering and the short-secret
 * derivation rule.
 */
class JwtServiceTest {

    private final JwtService jwtService = new JwtService(properties("a-test-secret-that-is-long-enough-for-hs256!"));

    private static TaskMeshProperties properties(String secret) {
        return new TaskMeshProperties(secret, "./data", "http://localhost:5173", 10,
                true, true, "taskmesh_events");
    }

    private static User user() {
        User user = new User("ada", "ada@taskmesh.local", "hash", UserRole.OPERATOR);
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    @Test
    void issuedTokenRoundTrips() {
        User user = user();
        String token = jwtService.issueToken(user);

        AuthenticatedUser parsed = jwtService.parse(token);

        assertThat(parsed).isNotNull();
        assertThat(parsed.id()).isEqualTo(user.getId());
        assertThat(parsed.username()).isEqualTo("ada");
        assertThat(parsed.role()).isEqualTo(UserRole.OPERATOR);
        assertThat(jwtService.tokenValiditySeconds()).isEqualTo(43_200);
    }

    @Test
    void expiredTokenIsRejected() {
        User user = user();
        SecretKey key = Keys.hmacShaKeyFor("a-test-secret-that-is-long-enough-for-hs256!"
                .getBytes(StandardCharsets.UTF_8));
        String expired = Jwts.builder()
                .subject(user.getId().toString())
                .claim("username", user.getUsername())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .expiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThat(jwtService.parse(expired)).isNull();
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwtService.issueToken(user());
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        assertThat(jwtService.parse(tampered)).isNull();
    }

    @Test
    void garbageIsRejected() {
        assertThat(jwtService.parse("not-a-jwt")).isNull();
        assertThat(jwtService.parse("")).isNull();
        assertThat(jwtService.parse(null)).isNull();
    }

    @Test
    void foreignSecretTokenIsRejected() {
        JwtService otherService = new JwtService(properties("another-secret-also-long-enough-for-hs256-ok"));
        String foreignToken = otherService.issueToken(user());

        assertThat(jwtService.parse(foreignToken)).isNull();
    }

    @Test
    void shortSecretIsDerivedToFullKeyLength() {
        // The documented dev default is only 28 bytes; derivation must make it usable.
        JwtService defaultSecretService = new JwtService(properties("taskmesh-dev-secret-change-me"));
        String token = defaultSecretService.issueToken(user());

        assertThat(defaultSecretService.parse(token)).isNotNull();
    }
}
