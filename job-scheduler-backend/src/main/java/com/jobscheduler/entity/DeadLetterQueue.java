package com.jobscheduler.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "dead_letter_queue")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DeadLetterQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String reason;

    @Builder.Default
    private LocalDateTime movedAt = LocalDateTime.now();
}
