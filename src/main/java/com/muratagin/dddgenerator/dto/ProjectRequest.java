package com.muratagin.dddgenerator.dto;

import com.muratagin.dddgenerator.validator.ValidCrossCuttingLibrary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
// Assuming CrossCuttingLibraryRequest doesn't need validation for this step, or is handled separately.

@ValidCrossCuttingLibrary // Apply the custom validation at the class level
public class ProjectRequest {

    @NotBlank(message = "Group is required")
    private String groupId;

    @NotBlank(message = "Artifact is required")
    private String artifactId;

    @NotBlank(message = "Project name is required")
    private String name;

    private String version; // Optional, defaults to "0.0.1-SNAPSHOT"

    @NotBlank(message = "Description is required")
    private String description;

    @NotBlank(message = "Package name is required")
    // Consider adding @Pattern(regexp = "^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+[0-9a-z_]$)" for stricter package name validation if needed
    private String packageName;

    private String javaVersion; // Optional, defaults in service
    private String springBootVersion; // Optional, defaults in service
    private String lombokVersion; // Optional, defaults in service

    @Valid // Add @Valid here to trigger validation of fields within CrossCuttingLibraryRequest if it's not null
    private CrossCuttingLibraryRequest crossCuttingLibrary; // Optional

    // Constructors
    public ProjectRequest() {
    }

    public ProjectRequest(String groupId, String artifactId, String name, String version,
                          String description, String packageName, String javaVersion, 
                          String springBootVersion, String lombokVersion, 
                          CrossCuttingLibraryRequest crossCuttingLibrary) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.name = name;
        this.version = version;
        this.description = description;
        this.packageName = packageName;
        this.javaVersion = javaVersion;
        this.springBootVersion = springBootVersion;
        this.lombokVersion = lombokVersion;
        this.crossCuttingLibrary = crossCuttingLibrary;
    }

    // Getters and setters
    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        if (this.version == null || this.version.trim().isEmpty()) {
            return "0.0.1-SNAPSHOT";
        }
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getJavaVersion() {
        return javaVersion;
    }

    public void setJavaVersion(String javaVersion) {
        this.javaVersion = javaVersion;
    }

    public String getSpringBootVersion() {
        return springBootVersion;
    }

    public void setSpringBootVersion(String springBootVersion) {
        this.springBootVersion = springBootVersion;
    }

    public String getLombokVersion() {
        return lombokVersion;
    }

    public void setLombokVersion(String lombokVersion) {
        this.lombokVersion = lombokVersion;
    }

    public CrossCuttingLibraryRequest getCrossCuttingLibrary() {
        return crossCuttingLibrary;
    }

    public void setCrossCuttingLibrary(CrossCuttingLibraryRequest crossCuttingLibrary) {
        this.crossCuttingLibrary = crossCuttingLibrary;
    }
}
