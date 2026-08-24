package com.jobscheduler.controller;

import com.jobscheduler.dto.QueueRequest;
import com.jobscheduler.entity.Queue;
import com.jobscheduler.service.JobService;
import com.jobscheduler.service.QueueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;
    private final JobService jobService;

    private Long userId(Authentication auth) {
        return (Long) auth.getPrincipal();
    }

    @PostMapping("/projects/{projectId}/queues")
    public ResponseEntity<Queue> create(Authentication auth, @PathVariable Long projectId,
                                         @Valid @RequestBody QueueRequest req) {
        return ResponseEntity.ok(queueService.create(userId(auth), projectId, req));
    }

    @GetMapping("/projects/{projectId}/queues")
    public ResponseEntity<List<Queue>> list(Authentication auth, @PathVariable Long projectId) {
        return ResponseEntity.ok(queueService.listForProject(userId(auth), projectId));
    }

    @PatchMapping("/queues/{queueId}/pause")
    public ResponseEntity<Queue> pause(@PathVariable Long queueId) {
        return ResponseEntity.ok(queueService.setStatus(queueId, Queue.Status.PAUSED));
    }

    @PatchMapping("/queues/{queueId}/resume")
    public ResponseEntity<Queue> resume(@PathVariable Long queueId) {
        return ResponseEntity.ok(queueService.setStatus(queueId, Queue.Status.ACTIVE));
    }

    @GetMapping("/queues/{queueId}/stats")
    public ResponseEntity<Map<String, Long>> stats(@PathVariable Long queueId) {
        return ResponseEntity.ok(jobService.statsForQueue(queueId));
    }
}
