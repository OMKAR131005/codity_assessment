package com.jobscheduler.service;

import com.jobscheduler.entity.*;
import com.jobscheduler.repository.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerService {

    private final JobRepository jobRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final WorkerRepository workerRepository;
    private final DeadLetterQueueRepository dlqRepository;

    @Value("${worker.poll.batch-size:10}")
    private int pollBatchSize;

    @Value("${worker.thread-pool.size:10}")
    private int threadPoolSize;

    @Value("${worker.heartbeat.stale-threshold-seconds:30}")
    private int staleThresholdSeconds;

    private ExecutorService executorService;
    private Worker self;
    private volatile boolean shuttingDown = false;

    // ---------- STARTUP ----------
    @PostConstruct
    public void init() {
        executorService = Executors.newFixedThreadPool(threadPoolSize);
        self = workerRepository.save(Worker.builder()
                .workerName("worker-" + UUID.randomUUID().toString().substring(0, 8))
                .status(Worker.Status.ACTIVE)
                .startedAt(LocalDateTime.now())
                .lastPingAt(LocalDateTime.now())
                .build());
        log.info("Worker started: {}", self.getWorkerName());
    }

    // ---------- POLLING + ATOMIC CLAIM + CONCURRENT EXECUTION ----------
    @Scheduled(fixedDelayString = "2000")
    public void pollAndClaim() {
        if (shuttingDown) return;

        try {
            int claimedCount = jobRepository.claimJobs(self.getId(), pollBatchSize);
            if (claimedCount == 0) return;

            List<Job> claimedJobs = jobRepository.findByStatusAndClaimedByWorkerId(Job.Status.CLAIMED, self.getId());
            log.info("Worker {} claimed {} job(s)", self.getWorkerName(), claimedJobs.size());

            for (Job job : claimedJobs) {
                executorService.submit(() -> executeJob(job.getId()));
            }
        } catch (Exception e) {
            log.error("Polling/claim cycle failed", e);
        }
    }

    // ---------- EXECUTION ----------
    @Transactional
    public void executeJob(Long jobId) {
        Job job = jobRepository.findByIdWithQueueAndRetryPolicy(jobId).orElse(null);
        if (job == null) return;

        job.setStatus(Job.Status.RUNNING);
        job.setUpdatedAt(LocalDateTime.now());
        jobRepository.save(job);

        int attemptNumber = job.getAttemptCount() + 1;
        JobExecution execution = JobExecution.builder()
                .job(job)
                .workerId(self.getId())
                .attemptNumber(attemptNumber)
                .status(Job.Status.RUNNING)
                .startedAt(LocalDateTime.now())
                .build();
        execution = jobExecutionRepository.save(execution);

        try {
            runJobLogic(job);

            job.setStatus(Job.Status.COMPLETED);
            job.setAttemptCount(attemptNumber);
            job.setUpdatedAt(LocalDateTime.now());

            execution.setStatus(Job.Status.COMPLETED);
            execution.setCompletedAt(LocalDateTime.now());

            log.info("Job {} COMPLETED (attempt {})", job.getId(), attemptNumber);

        } catch (Exception e) {
            handleFailure(job, execution, attemptNumber, e);
        }

        jobRepository.save(job);
        jobExecutionRepository.save(execution);
    }

    /**
     * Placeholder for actual job logic. In this notification-system domain, this is where
     * you'd parse job.getPayload() (e.g. {"type":"email","to":...,"message":...}) and call
     * the relevant notification channel. Simulated here with a short delay + random failure
     * so retry/DLQ paths are demonstrably exercised.
     */
    private void runJobLogic(Job job) throws Exception {
        Thread.sleep(500); // simulate work
        if (Math.random() < 0.3) {
            throw new RuntimeException("Simulated downstream failure for job " + job.getId());
        }
    }

    // ---------- RETRY / DLQ ----------
    private void handleFailure(Job job, JobExecution execution, int attemptNumber, Exception e) {
        log.warn("Job {} FAILED on attempt {}: {}", job.getId(), attemptNumber, e.getMessage());

        execution.setStatus(Job.Status.FAILED);
        execution.setCompletedAt(LocalDateTime.now());
        execution.setErrorMessage(e.getMessage());

        job.setAttemptCount(attemptNumber);

        RetryPolicy policy = job.getQueue().getRetryPolicy();
        int maxRetries = (policy != null) ? policy.getMaxRetries() : 3;

        if (attemptNumber >= maxRetries) {
            job.setStatus(Job.Status.DEAD_LETTER);
            dlqRepository.save(DeadLetterQueue.builder()
                    .job(job)
                    .reason("Exceeded max retries (" + maxRetries + "): " + e.getMessage())
                    .build());
            log.warn("Job {} moved to DEAD LETTER QUEUE", job.getId());
        } else {
            job.setStatus(Job.Status.QUEUED);
            job.setScheduledAt(LocalDateTime.now().plusSeconds(calculateDelay(policy, attemptNumber)));
        }
        job.setUpdatedAt(LocalDateTime.now());
    }

    private long calculateDelay(RetryPolicy policy, int attemptNumber) {
        if (policy == null) return 10; // default fallback
        int base = policy.getBaseDelaySeconds();
        switch (policy.getType()) {
            case FIXED:
                return base;
            case LINEAR:
                return (long) base * attemptNumber;
            case EXPONENTIAL:
                return (long) (base * Math.pow(policy.getMultiplier(), attemptNumber));
            default:
                return base;
        }
    }

    // ---------- HEARTBEAT ----------
    @Scheduled(fixedDelayString = "10000")
    public void heartbeat() {
        if (self == null) return;
        self.setLastPingAt(LocalDateTime.now());
        workerRepository.save(self);
    }

    // "Reaper": recover jobs stuck under workers whose heartbeat has gone stale (crash recovery)
    @Scheduled(fixedDelayString = "15000")
    public void reapStaleWorkers() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(staleThresholdSeconds);
        List<Worker> staleWorkers = workerRepository.findByStatusAndLastPingAtBefore(Worker.Status.ACTIVE, cutoff);

        for (Worker w : staleWorkers) {
            if (w.getId().equals(self.getId())) continue; // don't reap yourself
            w.setStatus(Worker.Status.DEAD);
            workerRepository.save(w);

            List<Job> orphanJobs = jobRepository.findByClaimedByWorkerIdAndStatusIn(
                    w.getId(), List.of(Job.Status.CLAIMED, Job.Status.RUNNING));
            for (Job job : orphanJobs) {
                job.setStatus(Job.Status.QUEUED);
                job.setClaimedByWorkerId(null);
                job.setUpdatedAt(LocalDateTime.now());
                jobRepository.save(job);
                log.warn("Reclaimed job {} from dead worker {}", job.getId(), w.getWorkerName());
            }
        }
    }

    // ---------- GRACEFUL SHUTDOWN ----------
    @PreDestroy
    public void shutdown() {
        log.info("Worker {} shutting down gracefully...", self != null ? self.getWorkerName() : "?");
        shuttingDown = true;
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Timed out waiting for running jobs to finish; forcing shutdown");
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (self != null) {
            self.setStatus(Worker.Status.DEAD);
            workerRepository.save(self);
        }
    }
}
