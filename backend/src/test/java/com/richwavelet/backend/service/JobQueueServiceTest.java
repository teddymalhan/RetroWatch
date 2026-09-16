package com.richwavelet.backend.service;

import com.richwavelet.backend.dto.ProcessVideoRequest;
import com.richwavelet.backend.model.QueuedJob;
import com.richwavelet.backend.model.ShaderStyle;
import com.richwavelet.backend.repository.QueuedJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobQueueServiceTest {

    @Mock
    private QueuedJobRepository queueRepository;

    private JobQueueService jobQueueService;

    @BeforeEach
    void setUp() {
        jobQueueService = new JobQueueService(queueRepository);
        ReflectionTestUtils.setField(jobQueueService, "leaseSeconds", 3600);
    }

    @Test
    void testEnqueue_PersistsJobWithCommaSeparatedAds() {
        when(queueRepository.save(any(QueuedJob.class))).thenAnswer(i -> i.getArgument(0));

        ProcessVideoRequest request = new ProcessVideoRequest(7L, List.of("ad-1", "ad-2"), ShaderStyle.VHS);

        QueuedJob job = jobQueueService.enqueue(request, "user-1", "job-1");

        assertEquals("job-1", job.getJobId());
        assertEquals("user-1", job.getUserId());
        assertEquals(7L, job.getVideoId());
        assertEquals("ad-1,ad-2", job.getAdIds());
        assertEquals("VHS", job.getShaderStyle());
        assertEquals(QueuedJob.Status.PENDING, job.getStatus());
    }

    @Test
    void testEnqueue_HandlesMissingAds() {
        when(queueRepository.save(any(QueuedJob.class))).thenAnswer(i -> i.getArgument(0));

        QueuedJob job = jobQueueService.enqueue(
                new ProcessVideoRequest(1L, null, ShaderStyle.CRT), "user-1", "job-1");

        assertEquals("", job.getAdIds());
    }

    @Test
    void testHasExistingTask_OnlyCountsPendingAndRunning() {
        when(queueRepository.existsByUserIdAndStatusIn(eq("user-1"), any())).thenReturn(true);

        assertTrue(jobQueueService.hasExistingTask("user-1"));

        ArgumentCaptor<List<QueuedJob.Status>> statuses = ArgumentCaptor.forClass(List.class);
        verify(queueRepository).existsByUserIdAndStatusIn(eq("user-1"), statuses.capture());

        assertEquals(List.of(QueuedJob.Status.PENDING, QueuedJob.Status.RUNNING), statuses.getValue());
    }

    @Test
    void testClaimBatch_MarksJobsRunningUnderLease() {
        QueuedJob job = new QueuedJob("job-1", "user-1", 1L, "", "CRT");
        when(queueRepository.claimBatch(2)).thenReturn(List.of(job));

        List<QueuedJob> claimed = jobQueueService.claimBatch(2, "dispatcher-a");

        assertEquals(1, claimed.size());
        assertEquals(QueuedJob.Status.RUNNING, job.getStatus());
        assertEquals(1, job.getAttempts());
        assertEquals("dispatcher-a", job.getClaimedBy());
        assertNotNull(job.getLeaseExpiresAt());
        assertTrue(job.getLeaseExpiresAt().isAfter(OffsetDateTime.now()));
    }

    @Test
    void testMarkCompleted_ClearsLeaseAndError() {
        QueuedJob job = new QueuedJob("job-1", "user-1", 1L, "", "CRT");
        job.setStatus(QueuedJob.Status.RUNNING);
        job.setLastError("boom");
        when(queueRepository.findById("job-1")).thenReturn(Optional.of(job));

        jobQueueService.markCompleted("job-1");

        assertEquals(QueuedJob.Status.COMPLETED, job.getStatus());
        assertNull(job.getLeaseExpiresAt());
        assertNull(job.getLastError());
        verify(queueRepository).save(job);
    }

    @Test
    void testMarkFailed_RequeuesWithBackoffWhileAttemptsRemain() {
        QueuedJob job = new QueuedJob("job-1", "user-1", 1L, "", "CRT");
        job.setStatus(QueuedJob.Status.RUNNING);
        job.setAttempts(1);
        job.setMaxAttempts(3);
        when(queueRepository.findById("job-1")).thenReturn(Optional.of(job));

        jobQueueService.markFailed("job-1", "worker said no");

        assertEquals(QueuedJob.Status.PENDING, job.getStatus());
        assertEquals("worker said no", job.getLastError());
        assertNotNull(job.getNextAttemptAt());
        assertTrue(job.getNextAttemptAt().isAfter(OffsetDateTime.now()));
        assertNull(job.getLeaseExpiresAt());
    }

    @Test
    void testMarkFailed_ParksJobAfterMaxAttempts() {
        QueuedJob job = new QueuedJob("job-1", "user-1", 1L, "", "CRT");
        job.setAttempts(3);
        job.setMaxAttempts(3);
        when(queueRepository.findById("job-1")).thenReturn(Optional.of(job));

        jobQueueService.markFailed("job-1", "still broken");

        assertEquals(QueuedJob.Status.FAILED, job.getStatus());
        assertNull(job.getNextAttemptAt());
    }

    @Test
    void testRelease_DoesNotBurnAnAttempt() {
        QueuedJob job = new QueuedJob("job-1", "user-1", 1L, "", "CRT");
        job.setStatus(QueuedJob.Status.RUNNING);
        job.setAttempts(1);
        when(queueRepository.findById("job-1")).thenReturn(Optional.of(job));

        jobQueueService.release("job-1", "worker unreachable");

        assertEquals(QueuedJob.Status.PENDING, job.getStatus());
        assertEquals(0, job.getAttempts());
        assertEquals("worker unreachable", job.getLastError());
    }

    @Test
    void testMarkFailed_UnknownJobIsIgnored() {
        when(queueRepository.findById(anyString())).thenReturn(Optional.empty());

        jobQueueService.markFailed("missing", "oops");

        verify(queueRepository, never()).save(any());
    }
}
