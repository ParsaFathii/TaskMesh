package com.taskmesh.repository;

import com.taskmesh.domain.JobResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JobResultRepository extends JpaRepository<JobResult, UUID> {

    Optional<JobResult> findByJobId(UUID jobId);
}
