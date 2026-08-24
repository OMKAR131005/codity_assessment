package com.jobscheduler.controller;

import com.jobscheduler.dto.ProjectRequest;
import com.jobscheduler.entity.Project;
import com.jobscheduler.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    private Long userId(Authentication auth) {
        return (Long) auth.getPrincipal();
    }

    @PostMapping
    public ResponseEntity<Project> create(Authentication auth, @Valid @RequestBody ProjectRequest req) {
        return ResponseEntity.ok(projectService.create(userId(auth), req));
    }

    @GetMapping
    public ResponseEntity<List<Project>> list(Authentication auth) {
        return ResponseEntity.ok(projectService.listForUser(userId(auth)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Project> get(Authentication auth, @PathVariable Long id) {
        return ResponseEntity.ok(projectService.getOwned(userId(auth), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication auth, @PathVariable Long id) {
        projectService.delete(userId(auth), id);
        return ResponseEntity.noContent().build();
    }
}
