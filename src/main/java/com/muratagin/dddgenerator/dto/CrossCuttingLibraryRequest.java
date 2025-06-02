package com.muratagin.dddgenerator.dto;

import java.util.List;

public class CrossCuttingLibraryRequest {
    private String groupId;
    private String name;
    private String version;
    private List<String> dependencies;

    // Constructors
    public CrossCuttingLibraryRequest() {
    }

    public CrossCuttingLibraryRequest(String groupId, String name, String version, List<String> dependencies) {
        this.groupId = groupId;
        this.name = name;
        this.version = version;
        this.dependencies = dependencies;
    }

    // Getters and Setters
    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public List<String> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<String> dependencies) {
        if (dependencies != null) {
            this.dependencies = dependencies.stream()
                                          .filter(dep -> dep != null && !dep.trim().isEmpty())
                                          .collect(java.util.stream.Collectors.toList());
        } else {
            this.dependencies = null;
        }
    }

    // Helper method to check if any field is populated
    public boolean isPopulated() {
        return (groupId != null && !groupId.isEmpty()) ||
               (name != null && !name.isEmpty()) ||
               (version != null && !version.isEmpty()) ||
               (dependencies != null && !dependencies.isEmpty());
    }

    // Helper method to check if all required fields are populated, assuming it's intended to be used
    public boolean isFullyPopulated() {
        return (groupId != null && !groupId.isEmpty()) &&
               (name != null && !name.isEmpty()) &&
               (version != null && !version.isEmpty()) &&
               (dependencies != null && !dependencies.isEmpty());
    }
} 