package com.taskmesh.repository;

import com.taskmesh.domain.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID>, JpaSpecificationExecutor<Job> {

    /** Idempotency lookup: one key per owner (partial unique index jobs_idempotency). */
    Optional<Job> findByOwnerIdAndIdempotencyKey(UUID ownerId, String idempotencyKey);
}
