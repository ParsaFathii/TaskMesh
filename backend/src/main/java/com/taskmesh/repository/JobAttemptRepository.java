package com.taskmesh.repository;

import com.taskmesh.domain.JobAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JobAttemptRepository extends JpaRepository<JobAttempt, Long> {

    List<JobAttempt> findByJobIdOrderByAttemptNumberDesc(UUID jobId);

    List<JobAttempt> findByJobIdAndFinishedAtIsNull(UUID jobId);
}
