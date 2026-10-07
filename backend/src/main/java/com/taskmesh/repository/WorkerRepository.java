package com.taskmesh.repository;

import com.taskmesh.domain.Worker;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WorkerRepository extends JpaRepository<Worker, UUID> {
}
