package com.muratagin.dddgenerator.dto;

import java.util.List;

public class ProjectRequest {
    private String groupId;
    private String artifactId;
    private String version;
    private String description;
    private String javaVersion;
    private String springBootVersion;
    private CrossCuttingLibraryRequest crossCuttingLibrary;
    private List<String> dependencies;

    // Constructors
    public ProjectRequest() {
    }

    public ProjectRequest(String groupId, String artifactId, String version,
                          String description, String javaVersion, String springBootVersion, 
                          CrossCuttingLibraryRequest crossCuttingLibrary, List<String> dependencies) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.description = description;
        this.javaVersion = javaVersion;
        this.springBootVersion = springBootVersion;
        this.crossCuttingLibrary = crossCuttingLibrary;
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

    public CrossCuttingLibraryRequest getCrossCuttingLibrary() {
        return crossCuttingLibrary;
    }

    public void setCrossCuttingLibrary(CrossCuttingLibraryRequest crossCuttingLibrary) {
        this.crossCuttingLibrary = crossCuttingLibrary;
    }

    public List<String> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<String> dependencies) {
        this.dependencies = dependencies;
    }
}
