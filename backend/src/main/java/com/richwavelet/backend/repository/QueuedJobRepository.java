package com.richwavelet.backend.repository;

import com.richwavelet.backend.model.QueuedJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.QueryHint;
import java.util.List;

@Repository
public interface QueuedJobRepository extends JpaRepository<QueuedJob, String> {

    boolean existsByUserIdAndStatusIn(String userId, List<QueuedJob.Status> statuses);

    long countByStatus(QueuedJob.Status status);

    /**
     * Atomically claim up to {@code limit} runnable jobs.
     *
     * <p>A job is runnable when it is PENDING and its retry backoff has elapsed, or when
     * it is RUNNING but its lease has expired (crash recovery). {@code FOR UPDATE SKIP
     * LOCKED} lets several dispatchers — including ones in other replicas — drain the
     * same table concurrently without ever handing the same job to two of them.
     *
     * <p>Must be called inside a transaction.
     */
    @Query(value = """
            SELECT * FROM queued_job
            WHERE (status = 'PENDING'
                   AND (next_attempt_at IS NULL OR next_attempt_at <= now())
                   AND (lease_expires_at IS NULL OR lease_expires_at <= now()))
               OR (status = 'RUNNING' AND lease_expires_at <= now())
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "10000"))
    List<QueuedJob> claimBatch(@Param("limit") int limit);
}
