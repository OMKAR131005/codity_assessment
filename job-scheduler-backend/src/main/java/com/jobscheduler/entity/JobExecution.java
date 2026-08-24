package com.jobscheduler.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "job_executions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class JobExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @Column(name = "worker_id")
    private Long workerId;

    private Integer attemptNumber;

    @Enumerated(EnumType.STRING)
    private Job.Status status;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String errorMessage;
}
