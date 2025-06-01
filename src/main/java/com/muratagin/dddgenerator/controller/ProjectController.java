package com.muratagin.dddgenerator.controller;

import com.muratagin.dddgenerator.dto.ProjectRequest;
import com.muratagin.dddgenerator.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public ResponseEntity<byte[]> generateProject(@Valid @RequestBody ProjectRequest request) throws IOException {
        byte[] zipFile = projectService.generateProjectZip(request);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment", request.getArtifactId() + ".zip");

        return new ResponseEntity<>(zipFile, headers, HttpStatus.OK);
    }
}
