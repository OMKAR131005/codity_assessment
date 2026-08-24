package com.jobscheduler;

import com.jobscheduler.entity.*;
import com.jobscheduler.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the atomic claim mechanism (native UPDATE ... FOR UPDATE SKIP LOCKED,
 * wrapped in a derived table) is safe under real concurrency: when two threads
 * race to claim the SAME queued job at the SAME instant, exactly one of them
 * must win, and the job must end up CLAIMED (not double-processed).
 *
 * This is a plain @SpringBootTest (NOT @Transactional) on purpose — each
 * thread's claimJobs() call needs to run in its own real transaction against
 * the DB for row-locking (SKIP LOCKED) to actually be exercised. Wrapping the
 * whole test in one Spring-managed transaction would serialize everything and
 * defeat the point of the test.
 */
@SpringBootTest
class JobClaimConcurrencyTest {

    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private QueueRepository queueRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WorkerRepository workerRepository;

    private Long jobId;

    @BeforeEach
    void setup() {
        // Adjust field names below if they differ from your actual entities —
        // these are inferred from WorkerService/DTOs, not read directly from
        // the entity classes.
        User user = userRepository.save(User.builder()
                .name("ConcurrencyTestUser")
                .email("concurrency-" + System.nanoTime() + "@test.com")
                .password("x")
                .build());

        Project project = projectRepository.save(Project.builder()
                .name("ConcurrencyTestProject")
                .owner(user)
                .build());

        Queue queue = queueRepository.save(Queue.builder()
                .name("concurrency-queue-" + System.nanoTime())
                .project(project)
                .priority(5)
                .concurrencyLimit(5)
                .status(Queue.Status.ACTIVE)
                .build());

        Job job = jobRepository.save(Job.builder()
                .queue(queue)
                .jobType(Job.JobType.IMMEDIATE)
                .payload("{\"type\":\"email\",\"to\":\"test@x.com\"}")
                .status(Job.Status.QUEUED)
                .priority(5)
                .attemptCount(0)
                .scheduledAt(LocalDateTime.now().minusSeconds(1))
                .build());

        jobId = job.getId();
    }

    @Test
    void onlyOneWorkerShouldClaimTheSameJobUnderConcurrency() throws InterruptedException {
        // Register two real workers first (claimed_by_worker_id likely has an FK
        // to workers.id) so the claim itself doesn't fail on a constraint.
        Worker workerA = workerRepository.save(Worker.builder()
                .workerName("test-worker-A-" + System.nanoTime())
                .status(Worker.Status.ACTIVE)
                .startedAt(LocalDateTime.now())
                .lastPingAt(LocalDateTime.now())
                .build());

        Worker workerB = workerRepository.save(Worker.builder()
                .workerName("test-worker-B-" + System.nanoTime())
                .status(Worker.Status.ACTIVE)
                .startedAt(LocalDateTime.now())
                .lastPingAt(LocalDateTime.now())
                .build());

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger totalClaimed = new AtomicInteger(0);

        Runnable claimTask1 = () -> runClaim(workerA.getId(), readyLatch, startLatch, doneLatch, totalClaimed);
        Runnable claimTask2 = () -> runClaim(workerB.getId(), readyLatch, startLatch, doneLatch, totalClaimed);

        executor.submit(claimTask1);
        executor.submit(claimTask2);


        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(finished, "Both claim attempts should complete within the timeout");
        assertEquals(1, totalClaimed.get(),
                "Exactly one worker should have claimed the job, even when both attempt at the same instant");

        Job job = jobRepository.findById(jobId).orElseThrow();
        assertEquals(Job.Status.CLAIMED, job.getStatus(),
                "Job should end up CLAIMED exactly once, not left QUEUED or claimed twice");
    }

    private void runClaim(Long workerId, CountDownLatch readyLatch, CountDownLatch startLatch,
                          CountDownLatch doneLatch, AtomicInteger totalClaimed) {
        try {
            readyLatch.countDown();
            startLatch.await();
            int claimed = jobRepository.claimJobs(workerId, 10);
            totalClaimed.addAndGet(claimed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            doneLatch.countDown();
        }
    }
}