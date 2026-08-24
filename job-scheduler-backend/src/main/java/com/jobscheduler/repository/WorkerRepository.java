package com.jobscheduler.repository;

import com.jobscheduler.entity.Worker;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface WorkerRepository extends JpaRepository<Worker, Long> {
    // Workers whose last heartbeat is older than the cutoff = presumed dead ("reaper" query)
    List<Worker> findByStatusAndLastPingAtBefore(Worker.Status status, LocalDateTime cutoff);
}
