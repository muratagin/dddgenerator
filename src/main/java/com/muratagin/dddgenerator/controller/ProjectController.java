package com.muratagin.dddgenerator.controller;

import com.muratagin.dddgenerator.dto.EnvironmentalCredentialsRequest;
import com.muratagin.dddgenerator.dto.ProjectRequest;
import com.muratagin.dddgenerator.service.ProjectService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.bind.support.SessionStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;

@Controller
@RequestMapping("/ui")
@SessionAttributes("projectRequest")
public class ProjectController {

    private final ProjectService projectService;
    private static final String SESSION_PROJECT_REQUEST_SUMMARY = "projectRequestSummary";
    private static final String SESSION_PROJECT_ZIP_BYTES = "projectZipBytes";
    private static final String SESSION_PROJECT_FILE_NAME = "projectFileName";

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @ModelAttribute("projectRequest")
    public ProjectRequest getProjectRequest() {
        return new ProjectRequest();
    }

    @ModelAttribute("environmentalCredentialsRequest")
    public EnvironmentalCredentialsRequest getEnvironmentalCredentialsRequest() {
        return new EnvironmentalCredentialsRequest();
    }

    @GetMapping("/generate-project")
    public String showProjectDetailsForm(Model model) {
        if (model.containsAttribute("globalErrorMessage")) {
            // If redirected with an error, ensure projectRequest is fresh if not already populated by Spring
            if (!model.containsAttribute("projectRequest")) {
                 model.addAttribute("projectRequest", new ProjectRequest());
            }
        } else if (!model.containsAttribute("projectRequest")) {
             model.addAttribute("projectRequest", new ProjectRequest());
        }
        return "generate-project";
    }

    @PostMapping("/project-details")
    public String handleProjectDetails(@Valid @ModelAttribute("projectRequest") ProjectRequest request,
                                       BindingResult bindingResult,
                                       HttpSession session) {
        if (bindingResult.hasErrors()) {
            return "generate-project";
        }
        session.setAttribute(SESSION_PROJECT_REQUEST_SUMMARY, request);
        return "redirect:/ui/environmental-credentials";
    }

    @GetMapping("/environmental-credentials")
    public String showEnvironmentalCredentialsForm(@ModelAttribute("projectRequest") ProjectRequest projectRequest,
                                                   Model model, HttpSession session) {
        Object summary = session.getAttribute(SESSION_PROJECT_REQUEST_SUMMARY);
        if (projectRequest.getName() == null || projectRequest.getName().isEmpty() || summary == null) {
            return "redirect:/ui/generate-project";
        }

        EnvironmentalCredentialsRequest envRequest = (EnvironmentalCredentialsRequest) model.getAttribute("environmentalCredentialsRequest");
        if (envRequest == null || envRequest.getApplicationName() == null) { // Check if it needs re-initialization
             envRequest = new EnvironmentalCredentialsRequest();
             envRequest.setApplicationName(projectRequest.getName());
             envRequest.setServerPort("8080");
        }
        
        model.addAttribute("environmentalCredentialsRequest", envRequest);
        model.addAttribute(SESSION_PROJECT_REQUEST_SUMMARY, summary);
        model.addAttribute("defaultApplicationName", projectRequest.getName());

        return "environmental-credentials";
    }

    @PostMapping("/generate-final")
    public String generateProjectFinalRedirect(
            @Valid @ModelAttribute("projectRequest") ProjectRequest projectRequest,
            BindingResult projectRequestBindingResult,
            @Valid @ModelAttribute("environmentalCredentialsRequest") EnvironmentalCredentialsRequest environmentalCredentialsRequest,
            BindingResult envBindingResult,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        if (projectRequestBindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("globalErrorMessage", "Project details are missing or invalid. Please start over.");
            // No sessionStatus.setComplete() here as projectRequest might be needed if we were to redirect back to first form with its errors.
            // However, for a full restart, clearing is fine if we always go to a fresh first page.
            session.removeAttribute(SESSION_PROJECT_REQUEST_SUMMARY); // Clean this one too
            return "redirect:/ui/generate-project";
        }

        if (envBindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("org.springframework.validation.BindingResult.environmentalCredentialsRequest", envBindingResult);
            redirectAttributes.addFlashAttribute("environmentalCredentialsRequest", environmentalCredentialsRequest);
            redirectAttributes.addFlashAttribute(SESSION_PROJECT_REQUEST_SUMMARY, session.getAttribute(SESSION_PROJECT_REQUEST_SUMMARY));
            if (projectRequest != null) {
                redirectAttributes.addFlashAttribute("defaultApplicationName", projectRequest.getName());
            }
            return "redirect:/ui/environmental-credentials";
        }

        try {
            if (environmentalCredentialsRequest.getServerPort() == null || environmentalCredentialsRequest.getServerPort().isEmpty()) {
                environmentalCredentialsRequest.setServerPort("8080");
            }
            // bannerMode is hardcoded in service, no need to check here
            if (environmentalCredentialsRequest.getApplicationName() == null || environmentalCredentialsRequest.getApplicationName().isEmpty()) {
                environmentalCredentialsRequest.setApplicationName(projectRequest.getName());
            }

            byte[] projectZipBytes = projectService.generateProjectZip(projectRequest, environmentalCredentialsRequest);
            String fileName = (projectRequest.getArtifactId() != null ? projectRequest.getArtifactId() : "project") + ".zip";

            session.setAttribute(SESSION_PROJECT_ZIP_BYTES, projectZipBytes);
            session.setAttribute(SESSION_PROJECT_FILE_NAME, fileName);
            
            // projectRequest and projectRequestSummary are kept in session for the download page to display info
            // They will be cleared by SessionStatus.setComplete() after the actual download

            return "redirect:/ui/download-page";

        } catch (IOException | IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("globalErrorMessage", "Error during project generation: " + e.getMessage());
            // Don't clear @SessionAttributes ("projectRequest") here, let user go back to env page from start
            session.removeAttribute(SESSION_PROJECT_REQUEST_SUMMARY);
            session.removeAttribute(SESSION_PROJECT_ZIP_BYTES); // Clean up potentially stored bytes
            session.removeAttribute(SESSION_PROJECT_FILE_NAME);
            return "redirect:/ui/generate-project"; 
        }
    }

    @GetMapping("/download-page")
    public String downloadPage(HttpSession session, Model model, RedirectAttributes redirectAttributes) {
        byte[] projectZipBytes = (byte[]) session.getAttribute(SESSION_PROJECT_ZIP_BYTES);
        String fileName = (String) session.getAttribute(SESSION_PROJECT_FILE_NAME);
        ProjectRequest projectRequest = (ProjectRequest) session.getAttribute("projectRequest"); // From @SessionAttributes

        if (projectZipBytes == null || fileName == null || projectRequest == null || projectRequest.getName() == null) {
            redirectAttributes.addFlashAttribute("globalErrorMessage", "No project available for download or session expired. Please start over.");
            return "redirect:/ui/generate-project";
        }

        model.addAttribute("fileName", fileName);
        model.addAttribute(SESSION_PROJECT_REQUEST_SUMMARY, session.getAttribute(SESSION_PROJECT_REQUEST_SUMMARY)); // For display
        return "download-project";
    }

    @GetMapping("/perform-download")
    public ResponseEntity<Resource> performDownload(HttpSession session, SessionStatus sessionStatus, RedirectAttributes redirectAttributes) {
        byte[] projectZipBytes = (byte[]) session.getAttribute(SESSION_PROJECT_ZIP_BYTES);
        String fileName = (String) session.getAttribute(SESSION_PROJECT_FILE_NAME);

        // Clear session attributes immediately after retrieving them
        sessionStatus.setComplete(); // Clears @SessionAttributes ("projectRequest")
        session.removeAttribute(SESSION_PROJECT_REQUEST_SUMMARY);
        session.removeAttribute(SESSION_PROJECT_ZIP_BYTES);
        session.removeAttribute(SESSION_PROJECT_FILE_NAME);

        if (projectZipBytes == null || fileName == null) {
            // This case should ideally be handled by the downloadPage redirect, but as a fallback:
            // Cannot set flash attributes here as we are returning ResponseEntity
            // Consider logging this unexpected state
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null); 
        }

        ByteArrayResource resource = new ByteArrayResource(projectZipBytes);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + fileName);
        headers.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE);

        return ResponseEntity.ok()
                .headers(headers)
                .contentLength(projectZipBytes.length)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}
