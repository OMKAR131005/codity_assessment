package com.jobscheduler.controller;

import com.jobscheduler.dto.JobRequest;
import com.jobscheduler.entity.Job;
import com.jobscheduler.entity.JobExecution;
import com.jobscheduler.service.JobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;

    @PostMapping
    public ResponseEntity<Job> create(@Valid @RequestBody JobRequest req) {
        return ResponseEntity.ok(jobService.create(req));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Job> get(@PathVariable Long id) {
        return ResponseEntity.ok(jobService.getById(id));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<List<JobExecution>> history(@PathVariable Long id) {
        return ResponseEntity.ok(jobService.getExecutionHistory(id));
    }

    @GetMapping("/queue/{queueId}")
    public ResponseEntity<Page<Job>> listByQueue(
            @PathVariable Long queueId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(jobService.listByQueue(queueId, pageable));
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Job> retry(@PathVariable Long id) {
        return ResponseEntity.ok(jobService.retry(id));
    }
}
