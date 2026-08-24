package main.java.com.jobscheduler.service;

import com.jobscheduler.dto.QueueRequest;
import com.jobscheduler.entity.Project;
import com.jobscheduler.entity.Queue;
import com.jobscheduler.entity.RetryPolicy;
import com.jobscheduler.repository.QueueRepository;
import com.jobscheduler.repository.RetryPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class QueueService {

    private final QueueRepository queueRepository;
    private final RetryPolicyRepository retryPolicyRepository;
    private final ProjectService projectService;

    public Queue create(Long userId, Long projectId, QueueRequest req) {
        Project project = projectService.getOwned(userId, projectId);

        RetryPolicy retryPolicy = RetryPolicy.builder()
                .type(RetryPolicy.Type.valueOf(req.getRetryType()))
                .baseDelaySeconds(req.getBaseDelaySeconds())
                .multiplier(req.getMultiplier())
                .maxRetries(req.getMaxRetries())
                .build();
        retryPolicy = retryPolicyRepository.save(retryPolicy);

        Queue queue = Queue.builder()
                .project(project)
                .name(req.getName())
                .priority(req.getPriority())
                .concurrencyLimit(req.getConcurrencyLimit())
                .retryPolicy(retryPolicy)
                .status(Queue.Status.ACTIVE)
                .build();

        return queueRepository.save(queue);
    }

    public List<Queue> listForProject(Long userId, Long projectId) {
        projectService.getOwned(userId, projectId); // ownership check
        return queueRepository.findByProjectId(projectId);
    }

    public Queue getById(Long queueId) {
        return queueRepository.findById(queueId)
                .orElseThrow(() -> new IllegalArgumentException("Queue not found"));
    }

    public Queue setStatus(Long queueId, Queue.Status status) {
        Queue queue = getById(queueId);
        queue.setStatus(status);
        return queueRepository.save(queue);
    }
}
