package com.jobscheduler.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class QueueRequest {
    @NotBlank
    private String name;

    private Integer priority = 5;

    private Integer concurrencyLimit = 5;

    // retry policy config (a new RetryPolicy row is created for this queue)
    private String retryType = "EXPONENTIAL"; // FIXED / LINEAR / EXPONENTIAL
    private Integer baseDelaySeconds = 10;
    private Double multiplier = 2.0;
    private Integer maxRetries = 3;
}
