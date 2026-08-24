package main.java.com.jobscheduler.repository;

import com.jobscheduler.entity.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<Job, Long> {

    /**
     * ATOMIC CLAIM.
     * This is the core reliability mechanism: multiple worker threads/instances can call
     * this concurrently and each row will only ever be claimed by exactly one of them.
     *
     * How it works:
     *  - The inner SELECT ... FOR UPDATE SKIP LOCKED finds up to :limit eligible rows
     *    (status=QUEUED and due) and locks them for the duration of this transaction.
     *  - SKIP LOCKED means if another transaction already has a row locked (i.e. another
     *    worker is claiming it right now), this query skips it instead of waiting/blocking.
     *  - The outer UPDATE then flips those (and only those) rows to CLAIMED and stamps
     *    them with this worker's id, all within a single atomic transaction.
     *
     * MySQL doesn't allow "UPDATE t WHERE id IN (SELECT ... FROM t)" directly (you can't
     * select from the same table you're updating in a subquery), so the inner SELECT is
     * wrapped in a derived table (the "AS t" alias) to work around that restriction.
     */
    @Modifying
    @Transactional
    @Query(value =
            "UPDATE jobs SET status = 'CLAIMED', claimed_by_worker_id = :workerId, updated_at = NOW() " +
            "WHERE id IN ( " +
            "  SELECT id FROM ( " +
            "    SELECT id FROM jobs " +
            "    WHERE status = 'QUEUED' AND scheduled_at <= NOW() " +
            "    ORDER BY priority DESC, scheduled_at ASC " +
            "    LIMIT :limit " +
            "    FOR UPDATE SKIP LOCKED " +
            "  ) AS t " +
            ")", nativeQuery = true)
    int claimJobs(@Param("workerId") Long workerId, @Param("limit") int limit);

    List<Job> findByQueueId(Long queueId);
    // After claimJobs() runs, fetch exactly the rows this worker just claimed.
    List<Job> findByStatusAndClaimedByWorkerId(Job.Status status, Long workerId);

    // Used by the heartbeat "reaper": jobs still RUNNING/CLAIMED under a specific worker.
    List<Job> findByClaimedByWorkerIdAndStatusIn(Long workerId, List<Job.Status> statuses);


    // With this (keep findByQueueIdAndStatus as-is, unused elsewhere isn't a problem):
    org.springframework.data.domain.Page<Job> findByQueueId(Long queueId, org.springframework.data.domain.Pageable pageable);

    List<Job> findByQueueIdAndStatus(Long queueId, Job.Status status);
    @Query("SELECT j FROM Job j " +
            "LEFT JOIN FETCH j.queue q " +
            "LEFT JOIN FETCH q.retryPolicy " +
            "WHERE j.id = :id")
    Optional<Job> findByIdWithQueueAndRetryPolicy(@Param("id") Long id);
}
