package com.jobscheduler.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class JobRequest {
    @NotNull
    private Long queueId;

    @NotBlank
    private String jobType; // IMMEDIATE / DELAYED / SCHEDULED

    @NotBlank
    private String payload; // e.g. {"type":"email","to":"user@x.com","message":"hi"}

    private Integer priority = 5;

    // required for DELAYED (seconds from now)
    private Integer delaySeconds;

    // required for SCHEDULED (ISO-8601, e.g. 2026-08-25T10:00:00)
    private String scheduledAt;
}
