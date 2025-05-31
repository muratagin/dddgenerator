package com.muratagin.dddgenerator.dto;

import java.util.List;

public class ProjectRequest {
    private String groupId;
    private String artifactId;
    private String version;
    private String description;
    private List<String> dependencies;

    // Constructors
    public ProjectRequest() {
    }

    public ProjectRequest(String groupId, String artifactId, String version,
                          String description, List<String> dependencies) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.description = description;
        this.dependencies = dependencies;
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

    public String getVersion() {
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

    public List<String> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<String> dependencies) {
        this.dependencies = dependencies;
    }
}
