package com.jobscheduler.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "retry_policies")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RetryPolicy {

    public enum Type { FIXED, LINEAR, EXPONENTIAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(nullable = false)
    private Integer baseDelaySeconds;

    // used only for EXPONENTIAL
    @Builder.Default
    private Double multiplier = 2.0;

    @Column(nullable = false)
    private Integer maxRetries;
}
