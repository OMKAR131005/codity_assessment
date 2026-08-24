package com.jobscheduler.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "jobs", indexes = {
        // Critical index: this is exactly what the worker's polling query filters/sorts on.
        @Index(name = "idx_job_poll", columnList = "status, scheduled_at, priority")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Job {

    public enum JobType { IMMEDIATE, DELAYED, SCHEDULED }

    public enum Status { QUEUED, CLAIMED, RUNNING, COMPLETED, FAILED, DEAD_LETTER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "queue_id", nullable = false)
    private Queue queue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobType jobType;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Status status = Status.QUEUED;

    @Builder.Default
    private Integer priority = 5;

    @Column(nullable = false)
    private LocalDateTime scheduledAt;

    @Column(name = "claimed_by_worker_id")
    private Long claimedByWorkerId;

    @Builder.Default
    private Integer attemptCount = 0;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;
}
