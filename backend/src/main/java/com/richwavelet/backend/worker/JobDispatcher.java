package com.richwavelet.backend.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richwavelet.backend.model.QueuedJob;
import com.richwavelet.backend.service.JobQueueService;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drains the database-backed job queue.
 *
 * <p>Runs on a fixed delay inside the application process: claims runnable jobs (with
 * {@code FOR UPDATE SKIP LOCKED}, so replicas never double-process), then POSTs each one
 * to the worker endpoint with the shared worker token — the same HTTP boundary Cloud
 * Tasks used to cross, minus the managed service.
 */
@Component
@ConditionalOnProperty(name = "queue.enabled", havingValue = "true", matchIfMissing = true)
public class JobDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(JobDispatcher.class);

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int WORKER_TIMEOUT_SECONDS = 3600;

    private final JobQueueService queueService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient;
    private final String dispatcherId;
    /** Loopback base URL used when {@code worker.base-url} is not configured. */
    private final String selfBaseUrl;

    @Value("${worker.base-url:}")
    private String workerBaseUrl;

    @Value("${worker.auth-token:}")
    private String workerAuthToken;

    @Value("${queue.batch-size:1}")
    private int batchSize;

    public JobDispatcher(JobQueueService queueService,
                         @Value("${server.port:8080}") int serverPort) {
        this.queueService = queueService;
        this.dispatcherId = buildDispatcherId();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(WORKER_TIMEOUT_SECONDS))
                .writeTimeout(Duration.ofSeconds(60))
                .build();
        this.selfBaseUrl = "http://127.0.0.1:" + serverPort;
    }

    /**
     * Poll loop. {@code fixedDelay} means the next pass starts only after the previous one
     * finishes, so a long-running video job cannot pile up overlapping dispatches.
     */
    @Scheduled(fixedDelayString = "${queue.dispatch-interval-ms:5000}", initialDelayString = "${queue.initial-delay-ms:15000}")
    public void dispatch() {
        List<QueuedJob> claimed;
        try {
            claimed = queueService.claimBatch(Math.max(1, batchSize), dispatcherId);
        } catch (Exception e) {
            logger.error("Queue claim failed: {}", e.getMessage());
            return;
        }

        for (QueuedJob job : claimed) {
            dispatchOne(job);
        }
    }

    private void dispatchOne(QueuedJob job) {
        String url = resolveBaseUrl() + "/api/tasks/process-video-worker";
        String body;
        try {
            body = objectMapper.writeValueAsString(payloadFor(job));
        } catch (Exception e) {
            queueService.markFailed(job.getJobId(), "Could not serialize job payload: " + e.getMessage());
            return;
        }

        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body, JSON))
                .header("Content-Type", "application/json")
                .header("X-User-Id", job.getUserId())
                .header("X-Job-Id", job.getJobId());

        if (workerAuthToken != null && !workerAuthToken.isBlank()) {
            builder.header("X-Worker-Token", workerAuthToken);
        }

        try (Response response = httpClient.newCall(builder.build()).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (response.isSuccessful()) {
                queueService.markCompleted(job.getJobId());
            } else {
                String error = "Worker returned HTTP " + response.code() + ": " + truncate(responseBody);
                if (response.code() == 401 || response.code() == 403) {
                    // Misconfigured token would fail every job identically; retrying is pointless.
                    logger.error("Worker rejected dispatcher credentials for job {}: {}", job.getJobId(), error);
                }
                queueService.markFailed(job.getJobId(), error);
            }
        } catch (IOException e) {
            // Connection level failure — the worker never ran. Release rather than burn an attempt.
            queueService.release(job.getJobId(), "Dispatcher could not reach worker at " + url + ": " + e.getMessage());
        }
    }

    private Map<String, Object> payloadFor(QueuedJob job) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId", job.getJobId());
        payload.put("userId", job.getUserId());
        payload.put("videoId", job.getVideoId());
        payload.put("adIds", splitAdIds(job.getAdIds()));
        payload.put("shaderStyle", job.getShaderStyle());
        return payload;
    }

    private List<String> splitAdIds(String adIds) {
        List<String> ids = new ArrayList<>();
        if (adIds != null && !adIds.isBlank()) {
            for (String id : adIds.split(",")) {
                if (!id.isBlank()) {
                    ids.add(id.trim());
                }
            }
        }
        return ids;
    }

    private String resolveBaseUrl() {
        if (workerBaseUrl != null && !workerBaseUrl.isBlank()) {
            return workerBaseUrl.endsWith("/")
                    ? workerBaseUrl.substring(0, workerBaseUrl.length() - 1)
                    : workerBaseUrl;
        }
        return selfBaseUrl;
    }

    private static String buildDispatcherId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        return host + ":" + ProcessHandle.current().pid() + ":" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }
}
