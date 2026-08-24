package main.java.com.jobscheduler.service;

import com.jobscheduler.dto.JobRequest;
import com.jobscheduler.entity.Job;
import com.jobscheduler.entity.JobExecution;
import com.jobscheduler.entity.Queue;
import com.jobscheduler.repository.JobExecutionRepository;
import com.jobscheduler.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRepository jobRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final QueueService queueService;

    public Job create(JobRequest req) {
        Queue queue = queueService.getById(req.getQueueId());
        Job.JobType type = Job.JobType.valueOf(req.getJobType());

        LocalDateTime scheduledAt;
        switch (type) {
            case IMMEDIATE:
                scheduledAt = LocalDateTime.now();
                break;
            case DELAYED:
                if (req.getDelaySeconds() == null) {
                    throw new IllegalArgumentException("delaySeconds is required for DELAYED jobs");
                }
                scheduledAt = LocalDateTime.now().plusSeconds(req.getDelaySeconds());
                break;
            case SCHEDULED:
                if (req.getScheduledAt() == null) {
                    throw new IllegalArgumentException("scheduledAt is required for SCHEDULED jobs");
                }
                scheduledAt = LocalDateTime.parse(req.getScheduledAt());
                break;
            default:
                throw new IllegalArgumentException("Unsupported job type: " + type);
        }

        Job job = Job.builder()
                .queue(queue)
                .jobType(type)
                .payload(req.getPayload())
                .status(Job.Status.QUEUED)
                .priority(req.getPriority())
                .scheduledAt(scheduledAt)
                .attemptCount(0)
                .build();

        return jobRepository.save(job);
    }

    public Job getById(Long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found"));
    }

    public Page<Job> listByQueue(Long queueId, Pageable pageable) {
        return jobRepository.findByQueueId(queueId, pageable);
    }

    public List<JobExecution> getExecutionHistory(Long jobId) {
        return jobExecutionRepository.findByJobIdOrderByStartedAtDesc(jobId);
    }

    // Manually retry a job that's sitting in FAILED or DEAD_LETTER status
    public Job retry(Long jobId) {
        Job job = getById(jobId);
        job.setStatus(Job.Status.QUEUED);
        job.setScheduledAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        return jobRepository.save(job);
    }

    public Map<String, Long> statsForQueue(Long queueId) {
        List<Job> jobs = jobRepository.findByQueueId(queueId);
        Map<String, Long> stats = new HashMap<>();
        for (Job.Status s : Job.Status.values()) {
            stats.put(s.name(), 0L);
        }
        for (Job j : jobs) {
            stats.merge(j.getStatus().name(), 1L, Long::sum);
        }
        stats.put("TOTAL", (long) jobs.size());
        return stats;
    }
}
