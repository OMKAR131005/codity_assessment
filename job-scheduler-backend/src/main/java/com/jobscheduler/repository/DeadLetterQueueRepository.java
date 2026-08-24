package main.java.com.jobscheduler.repository;

import com.jobscheduler.entity.DeadLetterQueue;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeadLetterQueueRepository extends JpaRepository<DeadLetterQueue, Long> {
}
