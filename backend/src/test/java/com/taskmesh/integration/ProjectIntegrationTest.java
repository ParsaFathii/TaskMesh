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
 * Project CRUD and ownership isolation.
 */
class ProjectIntegrationTest extends IntegrationTestBase {

    private String createProject(String username, String name) {
        ResponseEntity<String> created = post(username, "/api/v1/projects",
                Map.of("name", name, "description", "integration test project"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return JsonPath.<String>read(created.getBody(), "$.id");
    }

    @Test
    void ownerCanCreateReadUpdateAndDelete() {
        String id = createProject("user", "My Project");

        ResponseEntity<String> fetched = get("user", "/api/v1/projects/" + id);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(fetched.getBody(), "$.name")).isEqualTo("My Project");
        assertThat(JsonPath.<String>read(fetched.getBody(), "$.description"))
                .isEqualTo("integration test project");

        ResponseEntity<String> updated = rest.exchange("/api/v1/projects/" + id, HttpMethod.PATCH,
                authJsonEntity("user", Map.of("name", "Renamed", "description", "updated")),
                String.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(updated.getBody(), "$.name")).isEqualTo("Renamed");

        ResponseEntity<String> deleted = rest.exchange("/api/v1/projects/" + id, HttpMethod.DELETE,
                authEntity("user"), String.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get("user", "/api/v1/projects/" + id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void foreignUserCannotSeeAnotherUsersProject() {
        // A second, non-seeded user to prove isolation.
        post("admin", "/api/v1/users", Map.of("username", "mallory", "email",
                "mallory@taskmesh.local", "password", "MalloryPass!9", "role", "USER"));

        String id = createProject("user", "Private Project");

        assertThat(get("mallory", "/api/v1/projects/" + id).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("operator", "/api/v1/projects/" + id).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("admin", "/api/v1/projects/" + id).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void onlyTheOwnerMayModifyAProject() {
        String id = createProject("user", "Locked Project");

        ResponseEntity<String> patch = rest.exchange("/api/v1/projects/" + id, HttpMethod.PATCH,
                authJsonEntity("operator", Map.of("name", "Hijacked")), String.class);
        assertThat(patch.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> delete = rest.exchange("/api/v1/projects/" + id, HttpMethod.DELETE,
                authEntity("operator"), String.class);
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void listingIsScopedByRole() {
        createProject("user", "Scoped Project");

        ResponseEntity<String> own = get("user", "/api/v1/projects");
        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(own.getBody()).contains("Scoped Project");
        assertThat((Integer) JsonPath.read(own.getBody(), "$.total")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(own.getBody(), "$.page")).isZero();
        assertThat((Integer) JsonPath.read(own.getBody(), "$.size")).isEqualTo(25);

        ResponseEntity<String> all = get("operator", "/api/v1/projects");
        assertThat((Integer) JsonPath.read(all.getBody(), "$.total")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void missingNameIsRejected() {
        ResponseEntity<String> response = post("user", "/api/v1/projects", Map.of("description", "no name"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(response.getBody(), "$.error.code")).isEqualTo("VALIDATION");
    }

    @Test
    void unknownProjectReturnsNotFoundEnvelope() {
        ResponseEntity<String> response = get("user", "/api/v1/projects/" + UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(JsonPath.<String>read(response.getBody(), "$.error.code")).isEqualTo("NOT_FOUND");
    }
}
