package main.java.com.jobscheduler.repository;

import com.jobscheduler.entity.Queue;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface QueueRepository extends JpaRepository<Queue, Long> {
    List<Queue> findByProjectId(Long projectId);
}
