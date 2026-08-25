package com.jobscheduler.service;

import com.jobscheduler.dto.ProjectRequest;
import com.jobscheduler.entity.Project;
import com.jobscheduler.entity.User;
import com.jobscheduler.repository.ProjectRepository;
import com.jobscheduler.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    public Project create(Long userId, ProjectRequest req) {
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Project project = Project.builder()
                .name(req.getName())
                .owner(owner)
                .build();

        return projectRepository.save(project);
    }

    public List<Project> listForUser(Long userId) {
        return projectRepository.findByOwnerId(userId);
    }

    public Project getOwned(Long userId, Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));
        if (!project.getOwner().getId().equals(userId)) {
            throw new SecurityException("Not authorized to access this project");
        }
        return project;
    }

    public void delete(Long userId, Long projectId) {
        Project project = getOwned(userId, projectId);
        projectRepository.delete(project);
    }
}
