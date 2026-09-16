package com.richwavelet.backend.service;

import com.richwavelet.backend.dto.ProcessVideoRequest;
import com.richwavelet.backend.model.QueuedJob;
import com.richwavelet.backend.repository.QueuedJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Durable job queue backed by the application's own PostgreSQL database.
 *
 * <p>Replaces the previous Google Cloud Tasks integration. Behaviour is the same from
 * the caller's point of view — enqueue a job, then a worker is invoked over HTTP — but
 * the queue now lives in a table that ships with the app, so the whole thing runs on a
 * single VPS with no managed queue behind it.
 */
@Service
public class JobQueueService {

    private static final Logger logger = LoggerFactory.getLogger(JobQueueService.class);

    /** How long a claimed job stays leased before another dispatcher may re-claim it. */
    private static final int DEFAULT_LEASE_SECONDS = 3600;

    /** Upper bound on the retry backoff. */
    private static final int MAX_BACKOFF_SECONDS = 600;

    private final QueuedJobRepository queueRepository;

    @Value("${queue.lease-seconds:3600}")
    private int leaseSeconds = DEFAULT_LEASE_SECONDS;

    public JobQueueService(QueuedJobRepository queueRepository) {
        this.queueRepository = queueRepository;
    }

    /**
     * Persist a new job for the dispatcher to pick up.
     */
    public QueuedJob enqueue(ProcessVideoRequest request, String userId, String jobId) {
        String adIds = request.adIds() == null ? "" : String.join(",", request.adIds());
        QueuedJob job = new QueuedJob(
                jobId,
                userId,
                request.videoId(),
                adIds,
                request.shaderStyle() == null ? "CRT" : request.shaderStyle().name()
        );

        QueuedJob saved = queueRepository.save(job);
        logger.info("Enqueued job {} for user {} (videoId={}, ads={})",
                jobId, userId, request.videoId(), request.adIds() == null ? 0 : request.adIds().size());
        return saved;
    }

    /**
     * True when the user already has a job waiting or running.
     *
     * <p>Mirrors the old Cloud Tasks "list tasks and match the X-User-Id header" check,
     * except the answer now comes from one indexed query instead of a full queue scan.
     */
    public boolean hasExistingTask(String userId) {
        return queueRepository.existsByUserIdAndStatusIn(
                userId, List.of(QueuedJob.Status.PENDING, QueuedJob.Status.RUNNING));
    }

    /**
     * Claim up to {@code limit} runnable jobs, marking them RUNNING under a lease.
     *
     * <p>Must run in a transaction: the {@code FOR UPDATE SKIP LOCKED} in the claim query
     * only holds the rows until the transaction commits.
     */
    @Transactional
    public List<QueuedJob> claimBatch(int limit, String dispatcherId) {
        List<QueuedJob> claimed = queueRepository.claimBatch(limit);
        OffsetDateTime now = OffsetDateTime.now();

        for (QueuedJob job : claimed) {
            job.setStatus(QueuedJob.Status.RUNNING);
            job.setAttempts(job.getAttempts() + 1);
            job.setClaimedBy(dispatcherId);
            job.setLeaseExpiresAt(now.plusSeconds(effectiveLeaseSeconds()));
            job.setUpdatedAt(now);
        }

        if (!claimed.isEmpty()) {
            queueRepository.saveAll(claimed);
            logger.info("Claimed {} job(s) as {}", claimed.size(), dispatcherId);
        }
        return claimed;
    }

    /**
     * Mark a job as finished. Called by the dispatcher after the worker returns 2xx.
     */
    @Transactional
    public void markCompleted(String jobId) {
        queueRepository.findById(jobId).ifPresentOrElse(job -> {
            job.setStatus(QueuedJob.Status.COMPLETED);
            job.setLeaseExpiresAt(null);
            job.setLastError(null);
            job.setUpdatedAt(OffsetDateTime.now());
            queueRepository.save(job);
            logger.info("Job {} completed", jobId);
        }, () -> logger.warn("markCompleted for unknown job {}", jobId));
    }

    /**
     * Record a failed attempt. Retries with exponential backoff until maxAttempts is
     * exhausted, after which the job is parked as FAILED so it stops being re-dispatched.
     */
    @Transactional
    public void markFailed(String jobId, String error) {
        Optional<QueuedJob> existing = queueRepository.findById(jobId);
        if (existing.isEmpty()) {
            logger.warn("markFailed for unknown job {}", jobId);
            return;
        }

        QueuedJob job = existing.get();
        OffsetDateTime now = OffsetDateTime.now();
        job.setLastError(error);
        job.setLeaseExpiresAt(null);
        job.setUpdatedAt(now);

        if (job.getAttempts() >= job.getMaxAttempts()) {
            job.setStatus(QueuedJob.Status.FAILED);
            job.setNextAttemptAt(null);
            logger.error("Job {} failed permanently after {} attempt(s): {}",
                    jobId, job.getAttempts(), error);
        } else {
            job.setStatus(QueuedJob.Status.PENDING);
            job.setNextAttemptAt(now.plusSeconds(backoffSeconds(job.getAttempts())));
            logger.warn("Job {} failed (attempt {}/{}), retrying: {}",
                    jobId, job.getAttempts(), job.getMaxAttempts(), error);
        }

        queueRepository.save(job);
    }

    /**
     * Release a claimed job back to PENDING without counting a failure — used when the
     * dispatcher itself could not reach the worker (shutdown, connection refused).
     */
    @Transactional
    public void release(String jobId, String reason) {
        queueRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(QueuedJob.Status.PENDING);
            job.setAttempts(Math.max(0, job.getAttempts() - 1));
            job.setLeaseExpiresAt(null);
            job.setNextAttemptAt(OffsetDateTime.now().plusSeconds(backoffSeconds(job.getAttempts())));
            job.setLastError(reason);
            job.setUpdatedAt(OffsetDateTime.now());
            queueRepository.save(job);
        });
    }

    private int effectiveLeaseSeconds() {
        return leaseSeconds > 0 ? leaseSeconds : DEFAULT_LEASE_SECONDS;
    }

    private long backoffSeconds(int attempts) {
        return Math.min((long) Math.pow(2, Math.max(0, attempts)) * 5, MAX_BACKOFF_SECONDS);
    }
}
