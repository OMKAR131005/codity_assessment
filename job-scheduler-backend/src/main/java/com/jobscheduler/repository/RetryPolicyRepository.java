package com.jobscheduler.repository;

import com.jobscheduler.entity.RetryPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RetryPolicyRepository extends JpaRepository<RetryPolicy, Long> {
}
