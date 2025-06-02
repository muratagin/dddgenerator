package com.muratagin.dddgenerator.dto;

public class EnvironmentalCredentialsRequest {

    private String serverPort; // Default: "8080"
    private String bannerMode; // Default: "off"
    private String applicationName; // Default: ProjectRequest.name

    // For local PostgreSQL
    private String localDatasourceUrl;
    private String localDatasourceUsername;
    private String localDatasourcePassword;

    // Environment selection for YML generation
    private boolean generateDev = true;
    private boolean generateTest = true;
    private boolean generateUat = true;
    private boolean generateProd = true;

    // Constructors
    public EnvironmentalCredentialsRequest() {
    }

    public EnvironmentalCredentialsRequest(String serverPort, String bannerMode, String applicationName,
                                       String localDatasourceUrl, String localDatasourceUsername, String localDatasourcePassword) {
        this.serverPort = serverPort;
        this.bannerMode = bannerMode;
        this.applicationName = applicationName;
        this.localDatasourceUrl = localDatasourceUrl;
        this.localDatasourceUsername = localDatasourceUsername;
        this.localDatasourcePassword = localDatasourcePassword;
    }

    // Getters and Setters
    public String getServerPort() {
        return serverPort;
    }

    public void setServerPort(String serverPort) {
        this.serverPort = serverPort;
    }

    public String getBannerMode() {
        return bannerMode;
    }

    public void setBannerMode(String bannerMode) {
        this.bannerMode = bannerMode;
    }

    public String getApplicationName() {
        return applicationName;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    public String getLocalDatasourceUrl() {
        return localDatasourceUrl;
    }

    public void setLocalDatasourceUrl(String localDatasourceUrl) {
        this.localDatasourceUrl = localDatasourceUrl;
    }

    public String getLocalDatasourceUsername() {
        return localDatasourceUsername;
    }

    public void setLocalDatasourceUsername(String localDatasourceUsername) {
        this.localDatasourceUsername = localDatasourceUsername;
    }

    public String getLocalDatasourcePassword() {
        return localDatasourcePassword;
    }

    public void setLocalDatasourcePassword(String localDatasourcePassword) {
        this.localDatasourcePassword = localDatasourcePassword;
    }

    // Getters and Setters for environment selection flags
    public boolean isGenerateDev() {
        return generateDev;
    }

    public void setGenerateDev(boolean generateDev) {
        this.generateDev = generateDev;
    }

    public boolean isGenerateTest() {
        return generateTest;
    }

    public void setGenerateTest(boolean generateTest) {
        this.generateTest = generateTest;
    }

    public boolean isGenerateUat() {
        return generateUat;
    }

    public void setGenerateUat(boolean generateUat) {
        this.generateUat = generateUat;
    }

    public boolean isGenerateProd() {
        return generateProd;
    }

    public void setGenerateProd(boolean generateProd) {
        this.generateProd = generateProd;
    }
} 