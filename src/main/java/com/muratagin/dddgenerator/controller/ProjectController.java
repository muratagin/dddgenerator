package com.muratagin.dddgenerator.controller;

import com.muratagin.dddgenerator.domain.request.EnvironmentalCredentialsRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.bind.support.SessionStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
@RequestMapping("/ui")
@SessionAttributes({"projectRequest", "environmentalCredentialsRequest"})
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
    public String showEnvironmentalCredentialsForm(Model model, HttpSession session, RedirectAttributes redirectAttributes) {
        ProjectRequest projectRequest = (ProjectRequest) session.getAttribute(SESSION_PROJECT_REQUEST_SUMMARY);

        if (projectRequest == null || projectRequest.getGroupId() == null || projectRequest.getArtifactId() == null) {
            redirectAttributes.addFlashAttribute("globalErrorMessage", "Project details are missing. Please start over.");
            return "redirect:/ui/generate-project";
        }

        if (!model.containsAttribute("environmentalCredentialsRequest")) {
            EnvironmentalCredentialsRequest environmentalCredentialsRequest = new EnvironmentalCredentialsRequest();
            if (projectRequest.getName() != null && !projectRequest.getName().isBlank()) {
                environmentalCredentialsRequest.setApplicationName(projectRequest.getName());
            }
            model.addAttribute("environmentalCredentialsRequest", environmentalCredentialsRequest);
        }
        model.addAttribute("projectRequestSummary", projectRequest); // Ensure summary is always available
        model.addAttribute("defaultApplicationName", projectRequest.getName());
        return "environmental-credentials";
    }

    @PostMapping("/process-environmentals")
    public String processEnvironmentalCredentials(@Valid @ModelAttribute("environmentalCredentialsRequest") EnvironmentalCredentialsRequest environmentalCredentialsRequest,
                                                  BindingResult bindingResult,
                                                  @ModelAttribute("projectRequest") ProjectRequest projectRequest,
                                                  BindingResult projectRequestBindingResult, // To check if projectRequest from session is still valid
                                                  Model model,
                                                  HttpSession session,
                                                  RedirectAttributes redirectAttributes,
                                                  SessionStatus sessionStatus) {

        if (projectRequestBindingResult.hasErrors() || projectRequest.getGroupId() == null) { // Check if session projectRequest is still valid
            redirectAttributes.addFlashAttribute("globalErrorMessage", "Session expired or project details are invalid. Please start over.");
            sessionStatus.setComplete(); // Clear session attributes
            // Manually clear other session attributes if any beyond @SessionAttributes
            session.removeAttribute(SESSION_PROJECT_REQUEST_SUMMARY);
            return "redirect:/ui/generate-project";
        }

        if (bindingResult.hasErrors()) {
            model.addAttribute("projectRequestSummary", projectRequest);
            model.addAttribute("defaultApplicationName", projectRequest.getName());
            return "environmental-credentials";
        }

        // Store in session for the next step (schema selection or direct generation)
        session.setAttribute("environmentalCredentialsRequest", environmentalCredentialsRequest);


        boolean hasLocalDbDetails = environmentalCredentialsRequest.getLocalDatasourceUrl() != null && !environmentalCredentialsRequest.getLocalDatasourceUrl().isBlank() &&
                                    environmentalCredentialsRequest.getLocalDatasourceUsername() != null && !environmentalCredentialsRequest.getLocalDatasourceUsername().isBlank();
                                    // Password can be blank

        if (hasLocalDbDetails) {
            return "redirect:/ui/schema-selection";
        } else {
            // No local DB details, proceed to generation
            try {
                byte[] zipBytes = projectService.generateProjectZip(projectRequest, environmentalCredentialsRequest);
                String fileName = projectRequest.getArtifactId() + ".zip";

                session.setAttribute(SESSION_PROJECT_ZIP_BYTES, zipBytes);
                session.setAttribute(SESSION_PROJECT_FILE_NAME, fileName);
                return "redirect:/ui/download-page";
            } catch (IOException | IllegalArgumentException e) {
                redirectAttributes.addFlashAttribute("globalErrorMessage", "Error generating project: " + e.getMessage());
                // Don't clear sessionStatus here, allow user to go back and correct
                return "redirect:/ui/generate-project"; // Or back to environmental if more appropriate
            }
        }
    }

    @GetMapping("/schema-selection")
    public String showSchemaSelectionForm(Model model,
                                          @ModelAttribute("projectRequest") ProjectRequest projectRequest,
                                          @ModelAttribute("environmentalCredentialsRequest") EnvironmentalCredentialsRequest environmentalCredentialsRequest,
                                          HttpSession session,
                                          RedirectAttributes redirectAttributes) {

        if (projectRequest == null || projectRequest.getGroupId() == null ||
            environmentalCredentialsRequest == null || environmentalCredentialsRequest.getLocalDatasourceUrl() == null) {
            redirectAttributes.addFlashAttribute("globalErrorMessage", "Project or environmental details are missing. Please start over.");
            return "redirect:/ui/generate-project";
        }

        model.addAttribute("projectRequestSummary", projectRequest); // For sidebar display

        List<String> schemas = new ArrayList<>();
        String connectionError = null;
        Connection connection = null;

        try {
            // Ensure PostgreSQL driver is loaded
            Class.forName("org.postgresql.Driver");
            connection = DriverManager.getConnection(
                    environmentalCredentialsRequest.getLocalDatasourceUrl(),
                    environmentalCredentialsRequest.getLocalDatasourceUsername(),
                    environmentalCredentialsRequest.getLocalDatasourcePassword()
            );
            Statement statement = connection.createStatement();
            // Query to list schemas (excluding system schemas)
            ResultSet resultSet = statement.executeQuery(
                "SELECT schema_name FROM information_schema.schemata " +
                "WHERE schema_name NOT IN ('pg_catalog', 'information_schema', 'pg_toast') " +
                "AND schema_name NOT LIKE 'pg_temp_%' AND schema_name NOT LIKE 'pg_toast_temp_%';"
            );
            while (resultSet.next()) {
                schemas.add(resultSet.getString("schema_name"));
            }
            resultSet.close();
            statement.close();
        } catch (ClassNotFoundException e) {
            connectionError = "PostgreSQL JDBC driver not found. Please ensure it's in the classpath.";
            // Log this error server-side as well
        } catch (SQLException e) {
            connectionError = "Error connecting to database or fetching schemas: " + e.getMessage();
             // Log this error server-side as well
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    // Log error closing connection
                }
            }
        }

        if (connectionError != null) {
            model.addAttribute("connectionError", connectionError);
        }
        if (schemas.isEmpty() && connectionError == null) {
             model.addAttribute("noSchemasFoundMessage", "No user schemas found in the database, or unable to list them. You can still proceed without selecting a schema.");
        }

        model.addAttribute("schemas", schemas);
        // environmentalCredentialsRequest is already added via @ModelAttribute
        return "schema-selection"; // Name of the new Thymeleaf template
    }

    @GetMapping("/get-tables")
    @ResponseBody
    public ResponseEntity<Object> getTablesForSchema(@RequestParam("schemaName") String schemaName,
                                                   @ModelAttribute("environmentalCredentialsRequest") EnvironmentalCredentialsRequest environmentalCredentialsRequest,
                                                   HttpSession session) {
        ProjectRequest projectRequest = (ProjectRequest) session.getAttribute("projectRequest");

        if (projectRequest == null || projectRequest.getGroupId() == null ||
            environmentalCredentialsRequest == null || environmentalCredentialsRequest.getLocalDatasourceUrl() == null ||
            schemaName == null || schemaName.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Missing project, environmental details, or schema name."));
        }

        List<String> tables = new ArrayList<>();
        String connectionError = null;
        Connection connection = null;

        try {
            Class.forName("org.postgresql.Driver");
            connection = DriverManager.getConnection(
                    environmentalCredentialsRequest.getLocalDatasourceUrl(),
                    environmentalCredentialsRequest.getLocalDatasourceUsername(),
                    environmentalCredentialsRequest.getLocalDatasourcePassword()
            );
            // Use PreparedStatement to prevent SQL injection
            try (PreparedStatement preparedStatement = connection.prepareStatement(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name"
            )) {
                preparedStatement.setString(1, schemaName);
                try (ResultSet rs = preparedStatement.executeQuery()) {
                    while (rs.next()) {
                        tables.add(rs.getString("table_name"));
                    }
                }
            }

            // Get aggregate roots
            Set<String> aggregateRoots = projectService.determineAggregateRoots(
                tables,
                projectService.getForeignKeys(connection, schemaName)
            );

            Map<String, Object> responseData = Map.of(
                "tables", tables,
                "aggregateRoots", aggregateRoots
            );

            return ResponseEntity.ok(responseData);

        } catch (ClassNotFoundException e) {
            connectionError = "PostgreSQL JDBC driver not found.";
             return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", connectionError));
        } catch (SQLException e) {
            connectionError = "Error connecting to database or fetching data: " + e.getMessage();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", connectionError));
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    // Log error closing connection
                }
            }
        }
    }

    @PostMapping("/generate-final")
    public String generateProjectFinal(@ModelAttribute("projectRequest") ProjectRequest projectRequest,
                                       @Valid @ModelAttribute("environmentalCredentialsRequest") EnvironmentalCredentialsRequest environmentalCredentialsRequest,
                                       BindingResult bindingResult, // Binding result for environmentalCredentialsRequest
                                       HttpSession session,
                                       RedirectAttributes redirectAttributes,
                                       SessionStatus sessionStatus) {

        // Re-check projectRequest from session as it might have been cleared or become invalid if user navigates weirdly
        ProjectRequest sessionProjectRequest = (ProjectRequest) session.getAttribute("projectRequest");
        if (sessionProjectRequest == null || sessionProjectRequest.getGroupId() == null) {
             redirectAttributes.addFlashAttribute("globalErrorMessage", "Session expired or project details are invalid. Please start over.");
             sessionStatus.setComplete();
             session.removeAttribute(SESSION_PROJECT_REQUEST_SUMMARY);
             session.removeAttribute("environmentalCredentialsRequest"); // also clear this
             return "redirect:/ui/generate-project";
        }
        
        // If coming from schema selection, environmentalCredentialsRequest might have selectedSchema.
        // If bindingResult has errors specifically for fields on schema-selection page (if any were validated there), handle them.
        // For now, we assume schema selection itself doesn't have complex validation beyond choosing a schema.
        if (bindingResult.hasErrors()) {
            // This case would typically be if schema selection introduced new validated fields
            // and they failed. For now, schema is just a string.
            // If there are errors on environmentalCredentialsRequest, redirect back to the relevant page.
            // Since generate-final can be hit after environmental-credentials OR schema-selection,
            // we need to be careful.
            // For now, if local DB details were present, assume error is on schema page or environmental page.
            boolean hasLocalDbDetails = environmentalCredentialsRequest.getLocalDatasourceUrl() != null && !environmentalCredentialsRequest.getLocalDatasourceUrl().isBlank();
            if (hasLocalDbDetails) {
                 // If errors exist and local DB details were provided, user might have been on schema-selection
                 // or environmental-credentials. Let's redirect to schema-selection if it's an option,
                 // otherwise to environmental-credentials.
                 // This logic might need refinement based on specific errors.
                 // For now, a simple redirect to schema-selection if local DB details are present.
                 redirectAttributes.addFlashAttribute("org.springframework.validation.BindingResult.environmentalCredentialsRequest", bindingResult);
                 redirectAttributes.addFlashAttribute("environmentalCredentialsRequest", environmentalCredentialsRequest);
                 redirectAttributes.addFlashAttribute("projectRequestSummary", sessionProjectRequest); // Pass summary again
                 return "redirect:/ui/schema-selection"; // Or environmental-credentials if schema selection was skipped
            } else {
                // Errors but no local DB details, implies error from environmental-credentials page
                redirectAttributes.addFlashAttribute("org.springframework.validation.BindingResult.environmentalCredentialsRequest", bindingResult);
                redirectAttributes.addFlashAttribute("environmentalCredentialsRequest", environmentalCredentialsRequest);
                redirectAttributes.addFlashAttribute("projectRequestSummary", sessionProjectRequest);
                redirectAttributes.addFlashAttribute("defaultApplicationName", sessionProjectRequest.getName());
                return "redirect:/ui/environmental-credentials";
            }
        }


        try {
            byte[] zipBytes = projectService.generateProjectZip(sessionProjectRequest, environmentalCredentialsRequest);
            String fileName = sessionProjectRequest.getArtifactId() + ".zip";

            session.setAttribute(SESSION_PROJECT_ZIP_BYTES, zipBytes);
            session.setAttribute(SESSION_PROJECT_FILE_NAME, fileName);

            // environmentalCredentialsRequest will be cleared from session by SessionStatus.setComplete()
            // which is called in /perform-download, along with projectRequest.
            return "redirect:/ui/download-page";

        } catch (IOException | IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("globalErrorMessage", "Error generating project: " + e.getMessage());
            // Don't clear sessionStatus here, allow user to go back and correct
            // Redirect to the page that submitted here. If schema selection was involved, that's the one.
             boolean hasLocalDbDetails = environmentalCredentialsRequest.getLocalDatasourceUrl() != null && !environmentalCredentialsRequest.getLocalDatasourceUrl().isBlank();
            if (hasLocalDbDetails) {
                return "redirect:/ui/schema-selection";
            }
            return "redirect:/ui/environmental-credentials";
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

    @GetMapping("/selected-schema")
    public String getSelectedSchema(Model model, @ModelAttribute("environmentalCredentialsRequest") EnvironmentalCredentialsRequest environmentalCredentialsRequest,
                                    HttpSession session) {
        String url = environmentalCredentialsRequest.getLocalDatasourceUrl();
        String username = environmentalCredentialsRequest.getLocalDatasourceUsername();
        String password = environmentalCredentialsRequest.getLocalDatasourcePassword();
        List<String> tables = new ArrayList<>();
        try {
            tables = getTables(url, username, password, environmentalCredentialsRequest.getSelectedSchema());
        } catch (SQLException e) {
            e.printStackTrace();
        }
        model.addAttribute("tables", tables);
        return "selected-schema";
    }

    private List<String> getTables(String url, String username, String password, String schema) throws SQLException {
        List<String> tables = new ArrayList<>();
        String query = "SELECT table_name FROM information_schema.tables WHERE table_schema = ?";

        try (Connection conn = DriverManager.getConnection(url, username, password);
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, schema);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString("table_name"));
                }
            }
        }
        return tables;
    }

    private List<Map<String, String>> getColumnsForTable(String url, String username, String password, String schema, String table) throws SQLException {
        List<Map<String, String>> columns = new ArrayList<>();
        String query = "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = ? AND table_name = ?";

        try (Connection conn = DriverManager.getConnection(url, username, password);
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, schema);
            pstmt.setString(2, table);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    columns.add(Map.of("name", rs.getString("column_name"), "type", rs.getString("data_type")));
                }
            }
        }
        return columns;
    }
}
