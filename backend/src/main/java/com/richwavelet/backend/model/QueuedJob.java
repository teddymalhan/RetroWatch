package com.richwavelet.backend.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * A durable video-processing job.
 *
 * <p>This replaces the previous Google Cloud Tasks queue: jobs are persisted in the
 * application's own PostgreSQL database and drained by an in-process dispatcher
 * ({@link com.richwavelet.backend.worker.JobDispatcher}), so the queue ships with the
 * application and needs no external managed service.
 */
@Entity
@Table(name = "queued_job", indexes = {
        @Index(name = "idx_queued_job_status_created", columnList = "status, created_at"),
        @Index(name = "idx_queued_job_user_id", columnList = "user_id")
})
public class QueuedJob {

    public enum Status {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED
    }

    @Id
    @Column(name = "job_id", length = 64)
    private String jobId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "video_id", nullable = false)
    private Long videoId;

    /** Comma-separated list of ad ids; empty string when no ads were selected. */
    @Column(name = "ad_ids", columnDefinition = "TEXT")
    private String adIds;

    @Column(name = "shader_style", nullable = false)
    private String shaderStyle;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    /** Identity of the dispatcher instance that claimed the job. */
    @Column(name = "claimed_by")
    private String claimedBy;

    /**
     * While a job is RUNNING the row is leased until this instant. A dispatcher that
     * crashes mid-job leaves the lease behind, and another dispatcher re-claims it
     * once the lease expires, so a restart never strands a job.
     */
    @Column(name = "lease_expires_at")
    private OffsetDateTime leaseExpiresAt;

    /** Earliest time a PENDING job may be attempted (retry backoff). */
    @Column(name = "next_attempt_at")
    private OffsetDateTime nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public QueuedJob() {
    }

    public QueuedJob(String jobId, String userId, Long videoId, String adIds, String shaderStyle) {
        this.jobId = jobId;
        this.userId = userId;
        this.videoId = videoId;
        this.adIds = adIds == null ? "" : adIds;
        this.shaderStyle = shaderStyle;
        this.status = Status.PENDING;
        this.attempts = 0;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Long getVideoId() {
        return videoId;
    }

    public void setVideoId(Long videoId) {
        this.videoId = videoId;
    }

    public String getAdIds() {
        return adIds;
    }

    public void setAdIds(String adIds) {
        this.adIds = adIds;
    }

    public String getShaderStyle() {
        return shaderStyle;
    }

    public void setShaderStyle(String shaderStyle) {
        this.shaderStyle = shaderStyle;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public void setClaimedBy(String claimedBy) {
        this.claimedBy = claimedBy;
    }

    public OffsetDateTime getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(OffsetDateTime leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public OffsetDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(OffsetDateTime nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
