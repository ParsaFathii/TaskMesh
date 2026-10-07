package com.taskmesh.integration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Result serving: inline JSON, file bytes with checksum header, download disposition
 * and path-traversal rejection.
 */
class ResultStorageIntegrationTest extends IntegrationTestBase {

    @Autowired
    private com.taskmesh.storage.ResultStorage storage;

    private String createJob() {
        String projectId = JsonPath.<String>read(post("user", "/api/v1/projects",
                Map.of("name", "Result Project")).getBody(), "$.id");
        return JsonPath.<String>read(post("user", "/api/v1/jobs", Map.of(
                "projectId", projectId, "type", "text_statistics",
                "payload", Map.of("text", "result content"))).getBody(), "$.id");
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    @Test
    void inlineResultIsServedAsJson() {
        String jobId = createJob();
        jdbc.update("INSERT INTO job_results (job_id, kind, inline) VALUES (?, 'inline', "
                + "'{\"characters\":15}'::jsonb)", uuid(jobId));

        ResponseEntity<String> response = get("user", "/api/v1/jobs/" + jobId + "/result");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat((Integer) JsonPath.read(response.getBody(), "$.characters")).isEqualTo(15);
    }

    @Test
    void fileResultIsServedWithChecksumHeader() throws Exception {
        String jobId = createJob();
        byte[] content = "resized image bytes".getBytes();
        String relative = "results/" + jobId + ".bin";
        Path file = storage.resolveForWrite(relative);
        Files.write(file, content);
        String checksum = sha256(content);
        jdbc.update("INSERT INTO job_results (job_id, kind, file_path, size_bytes, checksum_sha256) "
                + "VALUES (?, 'file', ?, ?, ?)", uuid(jobId), relative, content.length, checksum);

        ResponseEntity<byte[]> response = rest.exchange("/api/v1/jobs/" + jobId + "/result",
                HttpMethod.GET, new HttpEntity<Void>(authHeaders("user")), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(response.getHeaders().getFirst("X-Job-Result-SHA256")).isEqualTo(checksum);
        assertThat(response.getBody()).isEqualTo(content);
    }

    @Test
    void downloadFlagForcesFileDisposition() throws Exception {
        String jobId = createJob();
        byte[] content = "0123456789".getBytes();
        String relative = "results/" + jobId + ".bin";
        Files.write(storage.resolveForWrite(relative), content);
        jdbc.update("INSERT INTO job_results (job_id, kind, file_path, size_bytes, checksum_sha256) "
                + "VALUES (?, 'file', ?, ?, ?)", uuid(jobId), relative, content.length, sha256(content));

        ResponseEntity<byte[]> response = rest.exchange(
                "/api/v1/jobs/" + jobId + "/result?download=true", HttpMethod.GET,
                new HttpEntity<Void>(authHeaders("user")), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).contains("attachment");
        assertThat(disposition).contains(jobId + ".bin");
    }

    @Test
    void traversalAttemptsAreRejected() throws Exception {
        String jobId = createJob();
        // A file that really exists OUTSIDE the storage root.
        Path outside = storage.root().getParent().resolve("secrets.txt");
        Files.writeString(outside, "top secret");
        try {
            jdbc.update("INSERT INTO job_results (job_id, kind, file_path) VALUES (?, 'file', ?)",
                    uuid(jobId), "../secrets.txt");

            ResponseEntity<String> response = get("user", "/api/v1/jobs/" + jobId + "/result");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).doesNotContain("top secret");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void absolutePathsAreRejected() {
        String jobId = createJob();
        jdbc.update("INSERT INTO job_results (job_id, kind, file_path) VALUES (?, 'file', ?)",
                uuid(jobId), "/etc/passwd");

        ResponseEntity<String> response = get("user", "/api/v1/jobs/" + jobId + "/result");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).doesNotContain("root");
    }

    @Test
    void missingResultFileYieldsNotFound() {
        String jobId = createJob();
        jdbc.update("INSERT INTO job_results (job_id, kind, file_path) VALUES (?, 'file', ?)",
                uuid(jobId), "results/does-not-exist.bin");

        ResponseEntity<String> response = get("user", "/api/v1/jobs/" + jobId + "/result");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void jobsWithoutResultReturnNotFound() {
        String jobId = createJob();

        ResponseEntity<String> response = get("user", "/api/v1/jobs/" + jobId + "/result");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(JsonPath.<String>read(response.getBody(), "$.error.code")).isEqualTo("NOT_FOUND");
    }

    @Test
    void resultsAreInvisibleToForeignUsers() {
        post("admin", "/api/v1/users", Map.of("username", "mallory", "email",
                "mallory@taskmesh.local", "password", "MalloryPass!9", "role", "USER"));
        String jobId = createJob();
        jdbc.update("INSERT INTO job_results (job_id, kind, inline) VALUES (?, 'inline', "
                + "'{\"characters\":1}'::jsonb)", uuid(jobId));

        assertThat(get("mallory", "/api/v1/jobs/" + jobId + "/result").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("operator", "/api/v1/jobs/" + jobId + "/result").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private HttpHeaders authHeaders(String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(username));
        return headers;
    }
}
