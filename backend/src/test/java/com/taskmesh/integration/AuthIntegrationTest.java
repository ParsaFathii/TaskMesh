package com.taskmesh.integration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Login, JWT handling and role enforcement (SPEC §11) over the live HTTP API.
 */
class AuthIntegrationTest extends IntegrationTestBase {

    @Test
    void healthIsPublicAndReportsVersion() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.status")).isEqualTo("UP");
        assertThat(JsonPath.<String>read(response.getBody(), "$.db")).isEqualTo("UP");
        assertThat(JsonPath.<String>read(response.getBody(), "$.version")).isEqualTo("0.1.0");
    }

    @Test
    void loginReturnsBearerTokenForTwelveHours() {
        ResponseEntity<String> response = rest.postForEntity("/api/v1/auth/login",
                jsonEntity(Map.of("username", "user", "password", USER_PASSWORD)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.token")).isNotBlank();
        assertThat(JsonPath.<String>read(response.getBody(), "$.tokenType")).isEqualTo("Bearer");
        assertThat((Integer) JsonPath.read(response.getBody(), "$.expiresIn")).isEqualTo(43200);
        assertThat(JsonPath.<String>read(response.getBody(), "$.user.username")).isEqualTo("user");
        assertThat(JsonPath.<String>read(response.getBody(), "$.user.role")).isEqualTo("USER");
    }

    @Test
    void wrongPasswordIsRejectedWithUnauthorizedEnvelope() {
        ResponseEntity<String> response = rest.postForEntity("/api/v1/auth/login",
                jsonEntity(Map.of("username", "user", "password", "wrong-password")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(JsonPath.<String>read(response.getBody(), "$.error.code")).isEqualTo("UNAUTHORIZED");
        assertThat(response.getBody()).doesNotContain("Exception");
    }

    @Test
    void unknownUserIsRejectedAsUnauthorized() {
        ResponseEntity<String> response = rest.postForEntity("/api/v1/auth/login",
                jsonEntity(Map.of("username", "ghost", "password", "whatever")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginAttemptsAreRateLimitedPerUsername() {
        String username = "ratelimit-probe-" + UUID.randomUUID();
        Map<String, String> body = Map.of("username", username, "password", "nope");

        for (int i = 0; i < 10; i++) {
            assertThat(rest.postForEntity("/api/v1/auth/login", jsonEntity(body), String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        ResponseEntity<String> blocked = rest.postForEntity("/api/v1/auth/login",
                jsonEntity(body), String.class);

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(JsonPath.<String>read(blocked.getBody(), "$.error.code")).isEqualTo("RATE_LIMITED");
    }

    @Test
    void malformedLoginBodyIsRejected() {
        ResponseEntity<String> response = rest.postForEntity("/api/v1/auth/login",
                jsonEntity(Map.of("username", "")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(response.getBody(), "$.error.code")).isEqualTo("VALIDATION");
    }

    @Test
    void protectedEndpointsRequireAuthentication() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/me", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(JsonPath.<String>read(response.getBody(), "$.error.code")).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void invalidTokenIsRejected() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth("this-is-not-a-jwt");
        ResponseEntity<String> response = rest.exchange("/api/v1/me", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void meReturnsTheAuthenticatedUser() {
        ResponseEntity<String> response = get("user", "/api/v1/me");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.username")).isEqualTo("user");
        assertThat(JsonPath.<String>read(response.getBody(), "$.role")).isEqualTo("USER");
        assertThat(response.getBody()).doesNotContain("password");
    }

    @Test
    void plainUsersCannotListWorkersButOperatorsCan() {
        assertThat(get("user", "/api/v1/workers").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("operator", "/api/v1/workers").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("admin", "/api/v1/workers").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void plainUsersCannotReadMetricsOrAuditLogs() {
        assertThat(get("user", "/api/v1/metrics").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("user", "/api/v1/logs").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("operator", "/api/v1/metrics").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("operator", "/api/v1/logs").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void onlyAdminsManageUsers() {
        assertThat(get("user", "/api/v1/users").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("operator", "/api/v1/users").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> listing = get("admin", "/api/v1/users");
        assertThat(listing.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) JsonPath.read(listing.getBody(), "$.total")).isEqualTo(3);
    }

    @Test
    void adminCreatesAUserWhoCanThenLogIn() {
        Map<String, Object> body = Map.of("username", "alice", "email", "alice@taskmesh.local",
                "password", "AlicePass!42", "role", "USER");
        ResponseEntity<String> created = post("admin", "/api/v1/users", body);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(JsonPath.<String>read(created.getBody(), "$.username")).isEqualTo("alice");
        assertThat(created.getBody()).doesNotContain("password");

        ResponseEntity<String> duplicate = post("admin", "/api/v1/users", body);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        String aliceToken = login("alice", "AlicePass!42");
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(aliceToken);
        ResponseEntity<String> me = rest.exchange("/api/v1/me", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(me.getBody(), "$.username")).isEqualTo("alice");
    }
}
