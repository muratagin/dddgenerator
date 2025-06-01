package com.muratagin.dddgenerator.controller;

import com.muratagin.dddgenerator.dto.ProjectRequest;
import com.muratagin.dddgenerator.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;

@Controller
@RequestMapping("/ui")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping("/generate")
    public String showGenerateForm(Model model) {
        model.addAttribute("projectRequest", new ProjectRequest());
        // Add default values if needed for the form, e.g.,
        // ProjectRequest defaultRequest = new ProjectRequest();
        // defaultRequest.setJavaVersion("17");
        // model.addAttribute("projectRequest", defaultRequest);
        return "generate-project"; // This will be the name of our Thymeleaf template
    }

    @PostMapping("/generate")
    public ResponseEntity<Resource> generateProject(@Valid @ModelAttribute("projectRequest") ProjectRequest request,
                                                    BindingResult bindingResult,
                                                    Model model) {
        if (bindingResult.hasErrors()) {
            // If there are validation errors, return to the form and display them
            // The "generate-project" view should be able to display these errors
            return ResponseEntity.badRequest().body(null); // Or render the form again with errors
        }

        try {
            byte[] projectZipBytes = projectService.generateProjectZip(request);
            ByteArrayResource resource = new ByteArrayResource(projectZipBytes);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + request.getArtifactId() + ".zip");
            headers.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE);

            return ResponseEntity.ok()
                    .headers(headers)
                    .contentLength(projectZipBytes.length)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(resource);

        } catch (IOException e) {
            // Handle IOException, perhaps return an error view or a ResponseEntity with an error status
            model.addAttribute("errorMessage", "Error generating project: " + e.getMessage());
            // It might be better to redirect to an error page or return a specific error response
            return ResponseEntity.internalServerError().body(null); // Placeholder
        } catch (IllegalArgumentException e) {
            model.addAttribute("errorMessage", "Invalid project request: " + e.getMessage());
             // Return to the form with an error message
            // This requires the form to be able to display "errorMessage"
            // For simplicity, sending a bad request response. Consider rendering the form again.
             return ResponseEntity.badRequest().body(new ByteArrayResource(("Error: " + e.getMessage()).getBytes()));
        }
    }
}
