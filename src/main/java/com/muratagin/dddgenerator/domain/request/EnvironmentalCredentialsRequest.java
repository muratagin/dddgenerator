package com.muratagin.dddgenerator.domain.request;

public class EnvironmentalCredentialsRequest {
    private String applicationName;
    private String serverPort;
    private String bannerMode;
    private String localDatasourceUrl;
    private String localDatasourceUsername;
    private String localDatasourcePassword;
    private boolean generateDev = true;
    private boolean generateTest = true;
    private boolean generateUat = true;
    private boolean generateProd = true;

    private String selectedSchema;

    // Getters and Setters

    public String getApplicationName() {
        return applicationName;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    public String getServerPort() {
        return serverPort != null && !serverPort.isBlank() ? serverPort : "8080";
    }

    public void setServerPort(String serverPort) {
        this.serverPort = serverPort;
    }

    public String getBannerMode() {
        return bannerMode != null && !bannerMode.isBlank() ? bannerMode : "off";
    }

    public void setBannerMode(String bannerMode) {
        this.bannerMode = bannerMode;
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

    public String getSelectedSchema() {
        return selectedSchema;
    }

    public void setSelectedSchema(String selectedSchema) {
        this.selectedSchema = selectedSchema;
    }
}
