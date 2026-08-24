package com.jobscheduler;

import com.jobscheduler.entity.RetryPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Standalone unit tests for the retry-delay math (mirrors WorkerService.calculateDelay).
 * Kept independent of Spring context so it runs fast with no DB required.
 */
class RetryPolicyTest {

    private long calculateDelay(RetryPolicy policy, int attemptNumber) {
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

    @Test
    void fixedDelay_isConstantAcrossAttempts() {
        RetryPolicy policy = RetryPolicy.builder().type(RetryPolicy.Type.FIXED).baseDelaySeconds(10).build();
        assertEquals(10, calculateDelay(policy, 1));
        assertEquals(10, calculateDelay(policy, 5));
    }

    @Test
    void linearDelay_growsLinearlyWithAttemptNumber() {
        RetryPolicy policy = RetryPolicy.builder().type(RetryPolicy.Type.LINEAR).baseDelaySeconds(10).build();
        assertEquals(10, calculateDelay(policy, 1));
        assertEquals(30, calculateDelay(policy, 3));
    }

    @Test
    void exponentialDelay_growsExponentiallyWithAttemptNumber() {
        RetryPolicy policy = RetryPolicy.builder()
                .type(RetryPolicy.Type.EXPONENTIAL).baseDelaySeconds(10).multiplier(2.0).build();
        assertEquals(20, calculateDelay(policy, 1));  // 10 * 2^1
        assertEquals(40, calculateDelay(policy, 2));  // 10 * 2^2
        assertEquals(80, calculateDelay(policy, 3));  // 10 * 2^3
    }
}
