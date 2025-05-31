package com.muratagin.dddgenerator.controller;

import com.muratagin.dddgenerator.dto.ProjectRequest;
import com.muratagin.dddgenerator.service.ProjectService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public ResponseEntity<byte[]> generateProject(@RequestBody ProjectRequest request) {
        try {
            byte[] zipContent = projectService.generateProjectZip(request);

            // Prepare the response
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=" + request.getArtifactId() + ".zip")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(zipContent);
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }
}
