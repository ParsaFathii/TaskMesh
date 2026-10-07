package com.taskmesh.repository;

import com.taskmesh.domain.JobLog;
import com.taskmesh.domain.LogLevel;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface JobLogRepository extends JpaRepository<JobLog, Long> {

    // Two queries instead of one ":level is null or ..." predicate: Hibernate binds a
    // null enum parameter as an untyped value, which PostgreSQL cannot type-check.
    @Query("""
            select l from JobLog l
            where l.jobId = :jobId
            order by l.id desc
            """)
    List<JobLog> findNewestFirst(@Param("jobId") UUID jobId, Pageable pageable);

    @Query("""
            select l from JobLog l
            where l.jobId = :jobId and l.level = :level
            order by l.id desc
            """)
    List<JobLog> findNewestFirst(@Param("jobId") UUID jobId, @Param("level") LogLevel level,
                                 Pageable pageable);
}
