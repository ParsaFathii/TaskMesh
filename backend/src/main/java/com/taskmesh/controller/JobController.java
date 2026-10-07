package com.taskmesh.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.taskmesh.controller.dto.JobAttemptResponse;
import com.taskmesh.controller.dto.JobDetailResponse;
import com.taskmesh.controller.dto.JobLogResponse;
import com.taskmesh.controller.dto.JobResponse;
import com.taskmesh.controller.dto.PageResponse;
import com.taskmesh.controller.dto.SubmitJobRequest;
import com.taskmesh.domain.JobPriority;
import com.taskmesh.domain.JobResult;
import com.taskmesh.domain.JobStatus;
import com.taskmesh.domain.LogLevel;
import com.taskmesh.domain.ResultKind;
import com.taskmesh.security.AuthenticatedUser;
import com.taskmesh.service.JobService;
import com.taskmesh.storage.ResultStorage;
import jakarta.validation.Valid;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Job submission, listing and lifecycle endpoints. POST returns 201 for a new job and
 * 200 with the existing job when an idempotency key is replayed.
 */
@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    /** Hard cap for the log limit parameter. */
    private static final int MAX_LOG_LIMIT = 1000;
    private static final int DEFAULT_LOG_LIMIT = 100;

    private final JobService jobService;
    private final ResultStorage resultStorage;

    public JobController(JobService jobService, ResultStorage resultStorage) {
        this.jobService = jobService;
        this.resultStorage = resultStorage;
    }

    @GetMapping
    public PageResponse<JobResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                          @RequestParam(required = false) UUID projectId,
                                          @RequestParam(required = false) JobStatus status,
                                          @RequestParam(required = false) String type,
                                          @RequestParam(required = false) JobPriority priority,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size) {
        PageRequest pageable = (PageRequest) Pagination.toPage(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(jobService.listFor(user, projectId, status, type, priority, pageable),
                JobResponse::from);
    }

    @PostMapping
    public ResponseEntity<JobResponse> submit(@AuthenticationPrincipal AuthenticatedUser user,
                                              @RequestHeader(value = "Idempotency-Key", required = false)
                                              String idempotencyHeader,
                                              @Valid @RequestBody SubmitJobRequest request) {
        JobService.Submission submission = jobService.submit(user, request, idempotencyHeader);
        JobResponse body = JobResponse.from(submission.job());
        if (submission.duplicate()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity
                .created(java.net.URI.create("/api/v1/jobs/" + body.id()))
                .body(body);
    }

    @GetMapping("/{id}")
    public JobDetailResponse get(@AuthenticationPrincipal AuthenticatedUser user,
                                 @PathVariable UUID id) {
        var job = jobService.requireVisible(id, user);
        return JobDetailResponse.from(job, jobService.workerOf(job));
    }

    @PostMapping("/{id}/cancel")
    public JobResponse cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return JobResponse.from(jobService.cancel(user, id));
    }

    @PostMapping("/{id}/retry")
    public JobResponse retry(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return JobResponse.from(jobService.retry(user, id));
    }

    @GetMapping("/{id}/result")
    public ResponseEntity<?> result(@AuthenticationPrincipal AuthenticatedUser user,
                                    @PathVariable UUID id,
                                    @RequestParam(required = false, defaultValue = "false") boolean download) {
        JobResult result = jobService.result(user, id);
        if (result.getKind() == ResultKind.inline) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (download) {
                headers.setContentDisposition(inlineDisposition("job-" + id + ".json"));
            }
            return ResponseEntity.ok().headers(headers).body(result.getInline());
        }
        return fileResult(id, result, download);
    }

    @GetMapping("/{id}/logs")
    public List<JobLogResponse> logs(@AuthenticationPrincipal AuthenticatedUser user,
                                     @PathVariable UUID id,
                                     @RequestParam(required = false) LogLevel level,
                                     @RequestParam(required = false) Integer limit) {
        int normalizedLimit = Pagination.normalizeLimit(limit, DEFAULT_LOG_LIMIT, MAX_LOG_LIMIT);
        return jobService.logs(user, id, level, normalizedLimit).stream()
                .map(JobLogResponse::from)
                .toList();
    }

    @GetMapping("/{id}/attempts")
    public List<JobAttemptResponse> attempts(@AuthenticationPrincipal AuthenticatedUser user,
                                             @PathVariable UUID id) {
        return jobService.attempts(user, id).stream()
                .map(JobAttemptResponse::from)
                .toList();
    }

    private ResponseEntity<UrlResource> fileResult(UUID jobId, JobResult result, boolean download) {
        Path file = resultStorage.resolveExisting(result.getFilePath());
        UrlResource resource;
        long length;
        try {
            resource = new UrlResource(file.toUri());
            length = Files.size(file);
        } catch (IOException e) {
            throw new ApiException.NotFound("Result file not found in storage");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentLength(length);
        if (result.getChecksumSha256() != null) {
            headers.set("X-Job-Result-SHA256", result.getChecksumSha256());
        }
        String filename = file.getFileName().toString();
        headers.setContentDisposition(download
                ? ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build()
                : ContentDisposition.inline().filename(filename, StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(resource);
    }

    private static ContentDisposition inlineDisposition(String filename) {
        return ContentDisposition.inline().filename(filename, StandardCharsets.UTF_8).build();
    }
}
