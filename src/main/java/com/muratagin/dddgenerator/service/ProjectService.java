package com.muratagin.dddgenerator.service;

import com.muratagin.dddgenerator.domain.request.EnvironmentalCredentialsRequest;
import com.muratagin.dddgenerator.dto.ProjectRequest;
import com.muratagin.dddgenerator.dto.CrossCuttingLibraryRequest;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.stream.Collectors;
import java.util.Locale;

@Service
public class ProjectService {

    private static final String DEFAULT_VERSION = "0.0.1-SNAPSHOT";
    private static final String DEFAULT_JAVA_VERSION = "21";
    private static final String DEFAULT_SPRING_BOOT_VERSION = "3.3.1";

    public byte[] generateProjectZip(ProjectRequest projectRequest, EnvironmentalCredentialsRequest environmentalCredentialsRequest) throws IOException, SQLException {
        String tempDirName = "project-" + projectRequest.getArtifactId() + "-" + System.currentTimeMillis();
        Path tempDirPath = Files.createTempDirectory(tempDirName);
        CrossCuttingLibraryRequest crossCuttingLib = projectRequest.getCrossCuttingLibrary();
        boolean useCrossCuttingLibrary = false;
        // Validation for CrossCuttingLibrary is now handled by @ValidCrossCuttingLibrary annotation on ProjectRequest
        // and @Valid on the crossCuttingLibrary field itself for its internal constraints (if any were added).
        // The custom validator CrossCuttingLibraryValidator handles the conditional logic.
        
        // Determine if a valid, fully populated cross-cutting library is being used.
        if (crossCuttingLib != null && 
            crossCuttingLib.isPopulated() && 
            crossCuttingLib.isFullyPopulated()) {
            // Further check if dependencies are correct (this part is also in the custom validator but good for safety here)
            List<String> requiredDeps = Arrays.asList("domain", "application", "persistence");
            Set<String> providedDeps = new HashSet<>(crossCuttingLib.getDependencies());
            if (providedDeps.containsAll(requiredDeps)) {
                useCrossCuttingLibrary = true;
            } else {
                // This case should ideally be caught by the validator, but as a safeguard:
                throw new IllegalArgumentException("Cross-cutting library dependencies must include 'domain', 'application', and 'persistence'. This should have been caught by earlier validation.");
            }
        } else if (crossCuttingLib != null && crossCuttingLib.isPopulated() && !crossCuttingLib.isFullyPopulated()){
            // This case should also be caught by the validator.
            throw new IllegalArgumentException("Cross-cutting library details are incomplete. This should have been caught by earlier validation.");
        }
        // If crossCuttingLib is null or not populated, useCrossCuttingLibrary remains false.

        String serverPort = (environmentalCredentialsRequest.getServerPort() != null && !environmentalCredentialsRequest.getServerPort().isEmpty()) ? environmentalCredentialsRequest.getServerPort() : "8080";
        String bannerMode = (environmentalCredentialsRequest.getBannerMode() != null && !environmentalCredentialsRequest.getBannerMode().isEmpty()) ? environmentalCredentialsRequest.getBannerMode() : "off";
        String springAppName = (environmentalCredentialsRequest.getApplicationName() != null && !environmentalCredentialsRequest.getApplicationName().isEmpty()) ? environmentalCredentialsRequest.getApplicationName() : projectRequest.getName();

        Path tempDir = Files.createTempDirectory("ddd-project-" + projectRequest.getArtifactId() + "-");
        String rootArtifactId = projectRequest.getArtifactId();
        String groupId = projectRequest.getGroupId();
        String version = (projectRequest.getVersion() != null && !projectRequest.getVersion().isEmpty()) ? projectRequest.getVersion() : DEFAULT_VERSION;
        String description = projectRequest.getDescription();

        String originalPackageName = projectRequest.getPackageName().toLowerCase(Locale.ENGLISH);
        String sanitizedPackageName = originalPackageName.replace('-', '_');

        String basePackagePath = sanitizedPackageName.replace('.', File.separatorChar);
        String basePackageNameForClassGen = sanitizedPackageName;

        Path pomFile = Paths.get(tempDir.toString(), "pom.xml");
        Files.writeString(pomFile, generateRootPomXmlContent(projectRequest, version));

        String containerArtifactId = rootArtifactId + "-container";
        Path containerModuleDir = Paths.get(tempDir.toString(), containerArtifactId);
        Files.createDirectories(containerModuleDir);
        Path containerPom = Paths.get(containerModuleDir.toString(), "pom.xml");
        Files.writeString(containerPom, generateContainerPomXmlContent(projectRequest, containerArtifactId, rootArtifactId, version));
        Path containerMainJavaDir = Paths.get(containerModuleDir.toString(), "src", "main", "java", basePackagePath, "container");
        Files.createDirectories(containerMainJavaDir);
        Path containerAppFile = Paths.get(containerMainJavaDir.toString(), snakeKebabCaseToPascalCase(rootArtifactId) + "ContainerApplication.java");
        Files.writeString(containerAppFile, generateContainerApplicationJavaContent(basePackageNameForClassGen, projectRequest, rootArtifactId, "container", useCrossCuttingLibrary));
        Path containerResources = Paths.get(containerModuleDir.toString(), "src", "main", "resources");
        Files.createDirectories(containerResources);
        Path applicationYml = Paths.get(containerResources.toString(), "application.yml");
        Files.writeString(applicationYml, generateApplicationYmlContent(springAppName, serverPort, bannerMode));

        // Always generate application-local.yml; the method provides defaults if details are not entered.
        Path applicationLocalYml = Paths.get(containerResources.toString(), "application-local.yml");
        Files.writeString(applicationLocalYml, generateApplicationLocalYmlContent(environmentalCredentialsRequest));

        // Conditionally generate profile-specific application.yml files
        if (environmentalCredentialsRequest.isGenerateDev()) {
            Path profileApplicationYml = Paths.get(containerResources.toString(), "application-dev.yml");
            Files.writeString(profileApplicationYml, generateProfileApplicationYmlContent(projectRequest.getName(), "dev"));
        }
        if (environmentalCredentialsRequest.isGenerateTest()) {
            Path profileApplicationYml = Paths.get(containerResources.toString(), "application-test.yml");
            Files.writeString(profileApplicationYml, generateProfileApplicationYmlContent(projectRequest.getName(), "test"));
        }
        if (environmentalCredentialsRequest.isGenerateUat()) {
            Path profileApplicationYml = Paths.get(containerResources.toString(), "application-uat.yml");
            Files.writeString(profileApplicationYml, generateProfileApplicationYmlContent(projectRequest.getName(), "uat"));
        }
        if (environmentalCredentialsRequest.isGenerateProd()) {
            Path profileApplicationYml = Paths.get(containerResources.toString(), "application-prod.yml");
            Files.writeString(profileApplicationYml, generateProfileApplicationYmlContent(projectRequest.getName(), "prod"));
        }

        String domainParentArtifactId = rootArtifactId + "-domain";
        Path domainModuleDir = Paths.get(tempDir.toString(), domainParentArtifactId);
        Files.createDirectories(domainModuleDir);
        Path domainParentPom = Paths.get(domainModuleDir.toString(), "pom.xml");
        Files.writeString(domainParentPom, generateDomainParentPomXmlContent(projectRequest, domainParentArtifactId, rootArtifactId, version));

        String domainCoreArtifactId = rootArtifactId + "-domain-core";
        Path domainCoreModuleDir = Paths.get(domainModuleDir.toString(), domainCoreArtifactId);
        Files.createDirectories(domainCoreModuleDir);
        Path domainCorePom = Paths.get(domainCoreModuleDir.toString(), "pom.xml");
        Files.writeString(domainCorePom, generateDomainCorePomXmlContent(projectRequest, domainCoreArtifactId, domainParentArtifactId, version));
        Path domainCoreMainJava = Paths.get(domainCoreModuleDir.toString(), "src", "main", "java", basePackagePath, "domain", "core");
        Files.createDirectories(domainCoreMainJava);

        String domainExceptionClassName = useCrossCuttingLibrary ? snakeKebabCaseToPascalCase(rootArtifactId) + "DomainException" : "DomainException";

        Path domainCoreExceptionDir = Paths.get(domainCoreMainJava.toString(), "exception");
        Files.createDirectories(domainCoreExceptionDir);
        Files.writeString(Paths.get(domainCoreExceptionDir.toString(), domainExceptionClassName + ".java"), generateDomainExceptionContent(basePackageNameForClassGen, domainExceptionClassName));
        Files.writeString(Paths.get(domainCoreExceptionDir.toString(), "DomainEntityNotFoundException.java"), generateDomainEntityNotFoundExceptionContent(basePackageNameForClassGen));
        Files.writeString(Paths.get(domainCoreExceptionDir.toString(), "RepositoryOutputPortException.java"), generateRepositoryOutputPortExceptionContent(basePackageNameForClassGen));

        Files.writeString(Paths.get(domainCoreMainJava.toString(), "DomainConstants.java"), generateDomainConstantsContent(basePackageNameForClassGen));

        Path domainCorePayloadDir = Paths.get(domainCoreMainJava.toString(), "payload");
        Files.createDirectories(domainCorePayloadDir);
        Files.writeString(Paths.get(domainCorePayloadDir.toString(), "BaseQuery.java"), generateBaseQueryContent(basePackageNameForClassGen));
        Files.writeString(Paths.get(domainCorePayloadDir.toString(), "BaseQueryResponse.java"), generateBaseQueryResponseContent(basePackageNameForClassGen));

        if (environmentalCredentialsRequest.getSelectedSchema() != null && !environmentalCredentialsRequest.getSelectedSchema().isEmpty()) {
            generateDomainClasses(environmentalCredentialsRequest, domainCoreMainJava, basePackageNameForClassGen);
        }

        if (!useCrossCuttingLibrary) {
            Path domainCoreEntityDir = Paths.get(domainCoreMainJava.toString(), "entity");
            Files.createDirectories(domainCoreEntityDir);
            Files.writeString(Paths.get(domainCoreEntityDir.toString(), "AggregateRoot.java"), generateDefaultAggregateRootContent(basePackageNameForClassGen));
            Files.writeString(Paths.get(domainCoreEntityDir.toString(), "BaseDomainEntity.java"), generateDefaultBaseDomainEntityContent(basePackageNameForClassGen));

            Path domainCoreValueObjectDir = Paths.get(domainCoreMainJava.toString(), "valueobject");
            Files.createDirectories(domainCoreValueObjectDir);
            Files.writeString(Paths.get(domainCoreValueObjectDir.toString(), "BaseId.java"), generateDefaultBaseIdContent(basePackageNameForClassGen));
        }

        String appServiceArtifactId = rootArtifactId + "-application-service";
        Path appServiceModuleDir = Paths.get(domainModuleDir.toString(), appServiceArtifactId);
        Files.createDirectories(appServiceModuleDir);
        Path appServicePom = Paths.get(appServiceModuleDir.toString(), "pom.xml");
        Files.writeString(appServicePom, generateApplicationServicePomXmlContent(projectRequest, appServiceArtifactId, domainParentArtifactId, domainCoreArtifactId, version));
        Path appServiceMainJava = Paths.get(appServiceModuleDir.toString(), "src", "main", "java", basePackagePath, "domain", "applicationservice");
        Files.createDirectories(appServiceMainJava);
        if (environmentalCredentialsRequest.getSelectedSchema() != null && !environmentalCredentialsRequest.getSelectedSchema().isEmpty()) {
            generateApplicationServiceClasses(projectRequest, environmentalCredentialsRequest, appServiceMainJava, domainCoreMainJava, basePackageNameForClassGen);
        } else {
        Files.createFile(Paths.get(appServiceMainJava.toString(), ".gitkeep"));
        }

        String infraParentArtifactId = rootArtifactId + "-infrastructure";
        Path infraModuleDir = Paths.get(tempDir.toString(), infraParentArtifactId);
        Files.createDirectories(infraModuleDir);
        Path infraParentPom = Paths.get(infraModuleDir.toString(), "pom.xml");
        Files.writeString(infraParentPom, generateInfrastructureParentPomXmlContent(projectRequest, infraParentArtifactId, rootArtifactId, version));

        String persistenceArtifactId = rootArtifactId + "-persistence";
        Path persistenceModuleDir = Paths.get(infraModuleDir.toString(), persistenceArtifactId);
        Files.createDirectories(persistenceModuleDir);
        Path persistencePom = Paths.get(persistenceModuleDir.toString(), "pom.xml");
        Files.writeString(persistencePom, generatePersistencePomXmlContent(projectRequest, persistenceArtifactId, infraParentArtifactId, appServiceArtifactId, version));
        Path persistenceMainJava = Paths.get(persistenceModuleDir.toString(), "src", "main", "java", basePackagePath, "infrastructure", "persistence");
        Files.createDirectories(persistenceMainJava);

        if (!useCrossCuttingLibrary) {
            Path persistenceEntityDir = Paths.get(persistenceMainJava.toString(), "entity");
            Files.createDirectories(persistenceEntityDir);
            Files.writeString(Paths.get(persistenceEntityDir.toString(), "BaseEntity.java"), generateDefaultBaseEntityContent(basePackageNameForClassGen));
        }
        
        // Generate persistence layer implementation if database details are provided
        if (environmentalCredentialsRequest.getLocalDatasourceUrl() != null && 
            !environmentalCredentialsRequest.getLocalDatasourceUrl().isBlank() &&
            environmentalCredentialsRequest.getSelectedSchema() != null &&
            !environmentalCredentialsRequest.getSelectedSchema().isBlank()) {
            generatePersistenceImplementation(projectRequest, environmentalCredentialsRequest, persistenceMainJava, basePackageNameForClassGen, useCrossCuttingLibrary);
        } else if (useCrossCuttingLibrary) {
            Files.createFile(Paths.get(persistenceMainJava.toString(), ".gitkeep"));
        }

        String appLayerArtifactId = rootArtifactId + "-application";
        Path appLayerModuleDir = Paths.get(tempDir.toString(), appLayerArtifactId);
        Files.createDirectories(appLayerModuleDir);
        Path appLayerPom = Paths.get(appLayerModuleDir.toString(), "pom.xml");
        Files.writeString(appLayerPom, generateApplicationLayerPomXmlContent(projectRequest, appLayerArtifactId, rootArtifactId, appServiceArtifactId, version));
        Path appLayerMainJava = Paths.get(appLayerModuleDir.toString(), "src", "main", "java", basePackagePath, "application");
        Files.createDirectories(appLayerMainJava);

        if (!useCrossCuttingLibrary) {
            String globalExceptionHandlerClassName = "GlobalExceptionHandler";
            Path appLayerExceptionDir = Paths.get(appLayerMainJava.toString(), "exception");
            Files.createDirectories(appLayerExceptionDir);
            Files.writeString(Paths.get(appLayerExceptionDir.toString(), globalExceptionHandlerClassName + ".java"), generateDefaultGlobalExceptionHandlerContent(basePackageNameForClassGen, globalExceptionHandlerClassName, domainExceptionClassName));

            Path appLayerPayloadDir = Paths.get(appLayerMainJava.toString(), "payload");
            Files.createDirectories(appLayerPayloadDir);
            Files.writeString(Paths.get(appLayerPayloadDir.toString(), "ResultObject.java"), generateDefaultResultObjectContent(basePackageNameForClassGen));

            if (environmentalCredentialsRequest.getSelectedSchema() != null && !environmentalCredentialsRequest.getSelectedSchema().isEmpty()) {
                // Generate REST controllers for aggregate roots
                Path appLayerRestDir = Paths.get(appLayerMainJava.toString(), "rest");
                Files.createDirectories(appLayerRestDir);
                generateRestControllers(environmentalCredentialsRequest, appLayerRestDir, basePackageNameForClassGen);
            }
        } else {
            Files.createFile(Paths.get(appLayerMainJava.toString(), ".gitkeep"));
        }

        // Generate requests documentation structure
        String requestsDir = "requests";
        Path requestsModuleDir = Paths.get(tempDir.toString(), requestsDir);
        Files.createDirectories(requestsModuleDir);

        // Create http subfolder
        Path httpDir = Paths.get(requestsModuleDir.toString(), "http");
        Files.createDirectories(httpDir);

        // Create postman subfolder  
        Path postmanDir = Paths.get(requestsModuleDir.toString(), "postman");
        Files.createDirectories(postmanDir);

        // Generate HTTP request files and Postman collection if schema is selected
        if (environmentalCredentialsRequest.getSelectedSchema() != null && 
            !environmentalCredentialsRequest.getSelectedSchema().isEmpty()) {
            generateHttpRequestFiles(environmentalCredentialsRequest, httpDir, basePackageNameForClassGen);
            generatePostmanCollection(environmentalCredentialsRequest, postmanDir, basePackageNameForClassGen, projectRequest);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zipOut = new ZipOutputStream(baos)) {
            zipDirectory(tempDir.toFile(), "", zipOut);
        }

        deleteDirectoryRecursively(tempDir);

        return baos.toByteArray();
    }

    private void zipDirectory(File folderToZip, String parentName, ZipOutputStream zipOut) throws IOException {
        File[] children = folderToZip.listFiles();
        if (children == null) {
            return;
        }

        for (File file : children) {
            String zipEntryName = parentName.isEmpty() ? file.getName() : parentName + "/" + file.getName();
            if (file.isDirectory()) {
                zipDirectory(file, zipEntryName, zipOut);
            } else {
                try (FileInputStream fis = new FileInputStream(file)) {
                    ZipEntry zipEntry = new ZipEntry(zipEntryName);
                    zipOut.putNextEntry(zipEntry);

                    byte[] bytes = new byte[1024];
                    int length;
                    while ((length = fis.read(bytes)) >= 0) {
                        zipOut.write(bytes, 0, length);
                    }
                    zipOut.closeEntry();
                }
            }
        }
    }
    
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase(Locale.ENGLISH) + str.substring(1);
    }

    private String snakeKebabCaseToPascalCase(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return Arrays.stream(input.split("[_-]")) // Split by underscore or hyphen
                .filter(part -> !part.isEmpty())
                .map(part -> Character.toUpperCase(part.charAt(0)) + part.substring(1).toLowerCase(Locale.ENGLISH))
                .collect(Collectors.joining());
    }

    private String snakeCaseToCamelCase(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String pascalCase = snakeKebabCaseToPascalCase(s);
        if (pascalCase.isEmpty()) {
            return pascalCase;
        }
        return Character.toLowerCase(pascalCase.charAt(0)) + pascalCase.substring(1);
    }

    private String firstCharToLowerCase(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private String capitalizeFirstLetter(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(input.charAt(0)) + input.substring(1);
    }

    private String pluralize(String word) {
        if (word == null || word.isEmpty()) {
            return word;
        }
        
        String lowerWord = word.toLowerCase();
        
        // Handle irregular plurals
        Map<String, String> irregularPlurals = Map.of(
            "person", "people",
            "child", "children",
            "foot", "feet",
            "tooth", "teeth",
            "goose", "geese",
            "mouse", "mice",
            "man", "men",
            "woman", "women",
            "ox", "oxen"
        );
        
        if (irregularPlurals.containsKey(lowerWord)) {
            return irregularPlurals.get(lowerWord);
        }
        
        // Words ending in 'y' preceded by a consonant: change 'y' to 'ies'
        if (lowerWord.endsWith("y") && lowerWord.length() > 1) {
            char beforeY = lowerWord.charAt(lowerWord.length() - 2);
            if (!isVowel(beforeY)) {
                return lowerWord.substring(0, lowerWord.length() - 1) + "ies";
            }
        }
        
        // Words ending in 's', 'ss', 'sh', 'ch', 'x', 'z': add 'es'
        if (lowerWord.endsWith("s") || lowerWord.endsWith("ss") || 
            lowerWord.endsWith("sh") || lowerWord.endsWith("ch") || 
            lowerWord.endsWith("x") || lowerWord.endsWith("z")) {
            // Special case for words ending in 'z': double the 'z' then add 'es'
            if (lowerWord.endsWith("z") && !lowerWord.endsWith("zz")) {
                return lowerWord + "zes";
            }
            return lowerWord + "es";
        }
        
        // Words ending in 'f' or 'fe': change to 'ves'
        if (lowerWord.endsWith("f")) {
            return lowerWord.substring(0, lowerWord.length() - 1) + "ves";
        }
        if (lowerWord.endsWith("fe")) {
            return lowerWord.substring(0, lowerWord.length() - 2) + "ves";
        }
        
        // Words ending in 'o' preceded by a consonant: add 'es'
        if (lowerWord.endsWith("o") && lowerWord.length() > 1) {
            char beforeO = lowerWord.charAt(lowerWord.length() - 2);
            if (!isVowel(beforeO)) {
                // Common exceptions that just add 's'
                if (lowerWord.equals("photo") || lowerWord.equals("piano") || 
                    lowerWord.equals("halo") || lowerWord.equals("soprano")) {
                    return lowerWord + "s";
                }
                return lowerWord + "es";
            }
        }
        
        // Default: just add 's'
        return lowerWord + "s";
    }
    
    private boolean isVowel(char c) {
        return "aeiou".indexOf(Character.toLowerCase(c)) != -1;
    }

    private String generateRootPomXmlContent(ProjectRequest request, String effectiveVersion) {
        String artifactId = request.getArtifactId();
        String javaVersion = (request.getJavaVersion() != null && !request.getJavaVersion().isEmpty()) ? request.getJavaVersion() : DEFAULT_JAVA_VERSION;
        String springBootVersion = (request.getSpringBootVersion() != null && !request.getSpringBootVersion().isEmpty()) ? request.getSpringBootVersion() : DEFAULT_SPRING_BOOT_VERSION;
        String lombokVersion = request.getLombokVersion();

        StringBuilder propertiesBuilder = new StringBuilder();
        propertiesBuilder.append(String.format("        <java.version>%s</java.version>\n", javaVersion));
        propertiesBuilder.append("        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n");

        String lombokDependencyManagementEntry = "";
        String lombokCompilerPathVersionTag = "";

        if (lombokVersion != null && !lombokVersion.isEmpty()) {
            propertiesBuilder.append(String.format("        <lombok.version>%s</lombok.version>\n", lombokVersion));
            String lombokVersionPropertyRef = "${lombok.version}";
            lombokDependencyManagementEntry =
                    "            <dependency>\n" +
                    "                <groupId>org.projectlombok</groupId>\n" +
                    "                <artifactId>lombok</artifactId>\n" +
                    String.format("                <version>%s</version>\n", lombokVersionPropertyRef) +
                    "                <scope>provided</scope>\n" +
                    "            </dependency>";
            lombokCompilerPathVersionTag = String.format("                            <version>%s</version>\n", lombokVersionPropertyRef);
        } else {
        }

        StringBuilder crossCuttingDepsXmlBuilder = new StringBuilder();
        CrossCuttingLibraryRequest crossCuttingLib = request.getCrossCuttingLibrary();

        if (crossCuttingLib != null && crossCuttingLib.getName() != null && !crossCuttingLib.getName().isEmpty() &&
            crossCuttingLib.getVersion() != null && !crossCuttingLib.getVersion().isEmpty() &&
            crossCuttingLib.getGroupId() != null && !crossCuttingLib.getGroupId().isEmpty() &&
            crossCuttingLib.getDependencies() != null && !crossCuttingLib.getDependencies().isEmpty()) {
            
            String libName = crossCuttingLib.getName();
            String libVersionProperty = libName.toLowerCase(Locale.ENGLISH).replace("-", "") + ".version";
            propertiesBuilder.append(String.format("        <%s>%s</%s>\n", libVersionProperty, crossCuttingLib.getVersion(), libVersionProperty));

            crossCuttingDepsXmlBuilder.append("\n            <!-- Cross-Cutting Library: ").append(libName).append(" -->");

            List<String> actualDeps = crossCuttingLib.getDependencies();
            
            crossCuttingDepsXmlBuilder.append("\n");

            for (int i = 0; i < actualDeps.size(); i++) {
                String depSuffix = actualDeps.get(i);
                String depBlock =
                        "            <dependency>\n" +
                        String.format("                <groupId>%s</groupId>\n", crossCuttingLib.getGroupId()) +
                        String.format("                <artifactId>%s-%s</artifactId>\n", libName, depSuffix) +
                        String.format("                <version>${%s}</version>\n", libVersionProperty) +
                        "            </dependency>";
                crossCuttingDepsXmlBuilder.append(depBlock);
                if (i < actualDeps.size() - 1) {
                    crossCuttingDepsXmlBuilder.append("\n");
                }
            }
        }

        return String.format("""
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>%s</version>
        <relativePath/>
    </parent>
    <groupId>%s</groupId>
    <artifactId>%s</artifactId>
    <version>%s</version>
    <name>%s</name>
    <description>%s</description>

    <packaging>pom</packaging>

    <modules>
        <module>%s-container</module>
        <module>%s-domain</module>
        <module>%s-infrastructure</module>
        <module>%s-application</module>
    </modules>

    <properties>
%s    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>%s</groupId>
                <artifactId>%s-container</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>%s</groupId>
                <artifactId>%s-application</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>%s</groupId>
                <artifactId>%s-domain-core</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>%s</groupId>
                <artifactId>%s-application-service</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>%s</groupId>
                <artifactId>%s-persistence</artifactId>
                <version>${project.version}</version>
            </dependency>

            <!-- Common Third-Party Dependencies -->
%s
%s
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                </plugin>
            </plugins>
        </pluginManagement>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
%s                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
                """,
                springBootVersion,
                request.getGroupId(),
                artifactId,
                effectiveVersion,
                request.getName(),
                request.getDescription(),
                artifactId, artifactId, artifactId, artifactId,
                propertiesBuilder.toString(),
                request.getGroupId(), artifactId,
                request.getGroupId(), artifactId,
                request.getGroupId(), artifactId,
                request.getGroupId(), artifactId,
                request.getGroupId(), artifactId,
                lombokDependencyManagementEntry,
                crossCuttingDepsXmlBuilder.toString(),
                lombokCompilerPathVersionTag
        );
    }

    private String generateContainerPomXmlContent(ProjectRequest request, String containerArtifactId, String rootArtifactId, String effectiveVersion) {
        String sanitizedPackageName = request.getPackageName().replace('-', '_');
        return String.format("""
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>%s</groupId>
        <artifactId>%s</artifactId>
        <version>%s</version>
    </parent>

    <artifactId>%s</artifactId>

    <properties>
        <start-class>%s.container.%sContainerApplication</start-class>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>

        <!-- Project Modules -->
        <dependency>
            <groupId>%s</groupId>
            <artifactId>%s-domain-core</artifactId>
        </dependency>
        <dependency>
            <groupId>%s</groupId>
            <artifactId>%s-application-service</artifactId>
        </dependency>
        <dependency>
            <groupId>%s</groupId>
            <artifactId>%s-application</artifactId>
        </dependency>
        <dependency>
            <groupId>%s</groupId>
            <artifactId>%s-persistence</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <mainClass>${start-class}</mainClass>
                </configuration>
                
                <executions>
                    <execution>
                        <goals>
                            <goal>repackage</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
                """,
                request.getGroupId(),
                rootArtifactId,
                effectiveVersion,
                containerArtifactId,
                sanitizedPackageName,
                capitalize(rootArtifactId),
                request.getGroupId(), rootArtifactId,
                request.getGroupId(), rootArtifactId,
                request.getGroupId(), rootArtifactId,
                request.getGroupId(), rootArtifactId
        );
    }

    private String generateContainerApplicationJavaContent(String basePackageName, ProjectRequest projectRequest, String rootArtifactId, String moduleSuffix, boolean useCrossCuttingLibrary) {
        String appName = capitalize(rootArtifactId) + capitalize(moduleSuffix) + "Application";
        String moduleSpecificPackage = basePackageName + "." + moduleSuffix.replace("-", "").toLowerCase(Locale.ENGLISH);

        if (useCrossCuttingLibrary) {
            CrossCuttingLibraryRequest crossCuttingLib = projectRequest.getCrossCuttingLibrary();
            String crossCuttingBasePackage = crossCuttingLib.getGroupId() + "." + crossCuttingLib.getName().replace("-", "");

        return String.format("""
package %s;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@EnableJpaRepositories(basePackages = {"%s.infrastructure.persistence"})
@EntityScan(basePackages = {"%s.infrastructure.persistence"})
@SpringBootApplication(scanBasePackages = {"%s", "%s"})
public class %s {

    public static void main(String[] args) {
        SpringApplication.run(%s.class, args);
    }

}
""", moduleSpecificPackage, basePackageName, basePackageName, basePackageName, crossCuttingBasePackage, appName, appName).stripIndent();
        } else {
            return String.format("""
package %s;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@EnableJpaRepositories(basePackages = {"%s.infrastructure.persistence"})
@EntityScan(basePackages = {"%s.infrastructure.persistence"})
@SpringBootApplication(scanBasePackages = {"%s"})
public class %s {

    public static void main(String[] args) {
        SpringApplication.run(%s.class, args);
    }

}
""", moduleSpecificPackage, basePackageName, basePackageName, basePackageName, appName, appName).stripIndent();
        }
    }

    private String generateApplicationYmlContent(String springApplicationName, String serverPort, String bannerMode) {
        // bannerMode argument is no longer used as it's hardcoded to off.
        // springApplicationName and serverPort are used.
        return String.format("""
spring:
  application:
    name: ${SPRING_APPLICATION_NAME:%s}
  main:
    banner-mode: off
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:local}
  # Datasource configuration is expected to be provided by an active profile (e.g., local, dev, prod)
  # or environment variables if no specific datasource is configured in a profile.
  jpa:
    hibernate:
      ddl-auto: ${JPA_HIBERNATE_DDL_AUTO:validate} # Defaults to validate; can be overridden by profiles
server:
  port: ${SERVER_PORT:%s}
""", springApplicationName, serverPort);
    }

    private String generateApplicationLocalYmlContent(EnvironmentalCredentialsRequest envRequest) {
        String url = (envRequest.getLocalDatasourceUrl() != null && !envRequest.getLocalDatasourceUrl().isEmpty())
                     ? envRequest.getLocalDatasourceUrl() : "jdbc:postgresql://localhost:5432/your_db_name_local";
        String username = (envRequest.getLocalDatasourceUsername() != null && !envRequest.getLocalDatasourceUsername().isEmpty())
                          ? envRequest.getLocalDatasourceUsername() : "your_username_local";
        String password = envRequest.getLocalDatasourcePassword() != null 
                          ? envRequest.getLocalDatasourcePassword() : "your_password_local";

        return String.format("""
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL_LOCAL:%s}
    username: ${SPRING_DATASOURCE_USERNAME_LOCAL:%s}
    password: ${SPRING_DATASOURCE_PASSWORD_LOCAL:%s}
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
""", url, username, password);
    }

    private String generateProfileApplicationYmlContent(String baseSpringApplicationName, String profile) {
        // baseSpringApplicationName is not used here.
        return String.format("""
# Configuration for '%s' environment.
# Expecting datasource credentials to be provided via environment variables or a secure configuration server.
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
""", profile);
    }

    private String generateDomainParentPomXmlContent(ProjectRequest request, String domainParentArtifactId, String rootArtifactId, String effectiveVersion) {
        return String.format("""
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>%s</groupId>
        <artifactId>%s</artifactId>
        <version>%s</version>
    </parent>

    <artifactId>%s</artifactId>

    <packaging>pom</packaging>

    <modules>
        <module>%s-application-service</module>    
        <module>%s-domain-core</module>
    </modules>
</project>
                """,
                request.getGroupId(),
                rootArtifactId,
                effectiveVersion,
                domainParentArtifactId,
                rootArtifactId,
                rootArtifactId
        );
    }

    private String generateDomainCorePomXmlContent(ProjectRequest request, String domainCoreArtifactId, String domainParentArtifactId, String effectiveVersion) {
         return String.format("""
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>%s</groupId>
        <artifactId>%s</artifactId>
        <version>%s</version>
    </parent>

    <artifactId>%s</artifactId>

</project>
                """,
                request.getGroupId(),
                domainParentArtifactId,
                effectiveVersion,
                domainCoreArtifactId
        );
    }
    
    private String generateApplicationServicePomXmlContent(ProjectRequest request, String appServiceArtifactId, String domainParentArtifactId, String domainCoreArtifactId, String effectiveVersion) {
        return String.format("""
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>%s</groupId>
        <artifactId>%s</artifactId>
        <version>%s</version>
    </parent>

    <artifactId>%s</artifactId>

    <dependencies>
        <!-- Domain Core -->
        <dependency>
            <groupId>%s</groupId>
            <artifactId>%s</artifactId>
        </dependency>

        <!-- Spring Boot Validation -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- Spring Transactions -->
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-tx</artifactId>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
        </dependency>

        <!-- Jackson Annotations (often used with DTOs) -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-annotations</artifactId>
        </dependency>

        <!-- Test Dependencies -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
                """,
                request.getGroupId(),
                domainParentArtifactId,
                effectiveVersion,
                appServiceArtifactId,
                request.getGroupId(), domainCoreArtifactId
        );
    }

    private String generateInfrastructureParentPomXmlContent(ProjectRequest request, String infraParentArtifactId, String rootArtifactId, String effectiveVersion) {
        return String.format("""
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>%s</groupId>
        <artifactId>%s</artifactId>
        <version>%s</version>
    </parent>

    <artifactId>%s</artifactId>

    <packaging>pom</packaging>

    <modules>
        <module>%s-persistence</module>
    </modules>
</project>
                """,
                request.getGroupId(),
                rootArtifactId,
                effectiveVersion,
                infraParentArtifactId,
                rootArtifactId
        );
    }

    private String generatePersistencePomXmlContent(ProjectRequest request, String persistenceArtifactId, String infraParentArtifactId, String appServiceArtifactId, String effectiveVersion) {
        return String.format("""
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>%s</groupId>
        <artifactId>%s</artifactId>
        <version>%s</version>
    </parent>

    <artifactId>%s</artifactId>

    <dependencies>
        <dependency>
            <groupId>%s</groupId>
            <artifactId>%s</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
        </dependency>
    </dependencies>
</project>
                """,
                request.getGroupId(),
                infraParentArtifactId,
                effectiveVersion,
                persistenceArtifactId,
                request.getGroupId(), appServiceArtifactId
        );
    }

    private String generateApplicationLayerPomXmlContent(ProjectRequest request, String appLayerArtifactId, String rootArtifactId, String appServiceArtifactId, String effectiveVersion) {
        return String.format("""
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>%s</groupId>
        <artifactId>%s</artifactId>
        <version>%s</version>
    </parent>

    <artifactId>%s</artifactId>

    <dependencies>
        <dependency>
            <groupId>%s</groupId>
            <artifactId>%s</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
        </dependency>
    </dependencies>
</project>
                """,
                request.getGroupId(),
                rootArtifactId,
                effectiveVersion,
                appLayerArtifactId,
                request.getGroupId(), appServiceArtifactId
        );
    }

    private void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.notExists(path)) {
            return;
        }
        Files.walk(path)
                .sorted((o1, o2) -> o2.compareTo(o1))
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        System.err.println("Failed to delete: " + p + " - " + e.getMessage());
                    }
                });
    }

    private String generateDefaultGlobalExceptionHandlerContent(String basePackageName, String globalExceptionHandlerClassName, String domainExceptionClassName) {
        return String.format("""
package %s.application.exception;

import %s.domain.core.exception.%s;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.net.URI;
import java.util.List;

@Slf4j
@ControllerAdvice
public class %s {

    @ResponseBody
    @ExceptionHandler(value = {%s.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handleException(%s exception) {
        log.error(exception.getMessage(), exception);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problemDetail.setType(URI.create("https://www.rfc-editor.org/rfc/rfc9110#status.400"));
        return problemDetail;
    }

    @ResponseBody
    @ExceptionHandler(value = {DataIntegrityViolationException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ProblemDetail handleException(DataIntegrityViolationException exception) {
        log.error(exception.getMessage(), exception);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problemDetail.setType(URI.create("https://www.rfc-editor.org/rfc/rfc9110#status.404"));
        return problemDetail;
    }

    @ResponseBody
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException exception) {
        log.error(exception.getMessage(), exception);
        List<String> errorMessages = exception.getConstraintViolations()
                .stream()
                .map(constraintViolation ->
                    constraintViolation.getPropertyPath().toString() +
                    ": " + constraintViolation.getMessage())
                .toList();
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, errorMessages.toString());
        problemDetail.setType(URI.create("https://www.rfc-editor.org/rfc/rfc9110#status.400"));
        return problemDetail;
    }
}
""", basePackageName, basePackageName, domainExceptionClassName, globalExceptionHandlerClassName, domainExceptionClassName, domainExceptionClassName, domainExceptionClassName, domainExceptionClassName);
    }

    private String generateDefaultResultObjectContent(String basePackageName) {
        return String.format("""
package %s.application.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
public class ResultObject<T> {

    private Boolean isSuccess;
    private T data;
    private List<String> messages;

    public static <T> ResultObject<T> empty() {
        return success(null);
    }

    public static <T> ResultObject<T> success(T data) {
        return ResultObject.<T>builder()
                .isSuccess(true)
                .data(data)
                .build();
    }

    public static <T> ResultObject<T> success(T data, String message) {
        return ResultObject.<T>builder()
                .isSuccess(true)
                .data(data)
                .messages(List.of(message))
                .build();
    }

    public static <T> ResultObject<T> error(List<String> failureMessages) {
        return ResultObject.<T>builder()
                .isSuccess(false)
                .messages(failureMessages)
                .build();
    }

    public static <T> ResultObject<T> error(String failureMessage) {
        return ResultObject.<T>builder()
                .isSuccess(false)
                .messages(List.of(failureMessage))
                .build();
    }
}
""", basePackageName);
    }

    private String generateDefaultAggregateRootContent(String basePackageName) {
        return String.format("""
package %s.domain.core.entity;

public abstract class AggregateRoot<ID> extends BaseDomainEntity<ID> {
}
""", basePackageName);
    }

    private String generateDefaultBaseDomainEntityContent(String basePackageName) {
        return String.format("""
package %s.domain.core.entity;

import java.util.Objects;

public abstract class BaseDomainEntity<ID> {

    private ID id;

    public ID getId() {
        return id;
    }

    public void setId(ID id) {
        this.id = id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BaseDomainEntity<?> that = (BaseDomainEntity<?>) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
""", basePackageName);
    }

    private String generateDefaultBaseEntityContent(String basePackageName) {
        return String.format("""
package %s.infrastructure.persistence.entity;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@MappedSuperclass
public class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    protected UUID id;

    protected Boolean isDeleted;

    public BaseEntity(UUID id, Boolean isDeleted) {
        this.id = id;
        this.isDeleted = isDeleted;
    }
}
""", basePackageName);
    }

    private String generateDefaultBaseIdContent(String basePackageName) {
        return String.format("""
package %s.domain.core.valueobject;

import java.util.Objects;

public abstract class BaseId<T> {

    private final T value;

    protected BaseId(T value) {
        this.value = value;
    }

    public T getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BaseId<?> baseId = (BaseId<?>) o;
        return Objects.equals(value, baseId.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
""", basePackageName);
    }

    private String generateDomainExceptionContent(String basePackageName, String className) {
        return String.format("""
package %s.domain.core.exception;

public class %s extends RuntimeException {

    public %s(String message) {
        super(message);
    }

    public %s(String message, Throwable cause) {
        super(message, cause);
    }
}
""", basePackageName, className, className, className);
    }

    private String generateDomainEntityNotFoundExceptionContent(String basePackageName) {
        return String.format("""
package %s.domain.core.exception;

public class DomainEntityNotFoundException extends RuntimeException {

    public DomainEntityNotFoundException() {
        super();
    }
}
""", basePackageName);
    }

    private String generateRepositoryOutputPortExceptionContent(String basePackageName) {
        return String.format("""
package %s.domain.core.exception;

public class RepositoryOutputPortException extends RuntimeException {

    public RepositoryOutputPortException() {
        super();
    }
}
""", basePackageName);
    }

    private void generateDomainClasses(EnvironmentalCredentialsRequest envRequest, Path domainCoreMainJava, String basePackageName) {
        String url = envRequest.getLocalDatasourceUrl();
        String username = envRequest.getLocalDatasourceUsername();
        String password = envRequest.getLocalDatasourcePassword();
        String schema = envRequest.getSelectedSchema();

        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            List<String> tables = getTables(conn, schema);
            Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys = getDetailedForeignKeys(conn, schema);

            Map<String, Set<String>> foreignKeys = new HashMap<>();
            for (Map.Entry<String, Map<String, ForeignKeyInfo>> entry : detailedForeignKeys.entrySet()) {
                foreignKeys.put(entry.getKey(), entry.getValue().values().stream().map(ForeignKeyInfo::getPkTableName).collect(Collectors.toSet()));
            }

            Set<String> aggregateRoots = determineAggregateRootsFromDB(tables, foreignKeys);
            Map<String, String> tableEntityTypes = envRequest.getTableEntityTypes();
            Map<String, String> columnToEnumMap = new HashMap<>();

            for (String table : tables) {
                List<Map<String, String>> columns = getColumnsForTable(conn, schema, table);
                for (Map<String, String> column : columns) {
                    String comment = column.get("comment");
                    if (comment != null && !comment.isBlank()) {
                        String enumFqn = generateEnumIfApplicable(comment, column.get("name"), basePackageName, domainCoreMainJava);
                        if (enumFqn != null) {
                            columnToEnumMap.put(table + "." + column.get("name"), enumFqn);
                        }
                    }
                }
            }

            for (String table : tables) {
                String classNamePrefix = snakeKebabCaseToPascalCase(table);
                String extendsClass;
                if (tableEntityTypes != null && tableEntityTypes.containsKey(table)) {
                    extendsClass = tableEntityTypes.get(table);
                } else {
                    extendsClass = aggregateRoots.contains(table) ? "AggregateRoot" : "BaseDomainEntity";
                }

                Path valueObjectDir = Paths.get(domainCoreMainJava.toString(), "valueobject");
                Files.createDirectories(valueObjectDir);
                String idClassName = classNamePrefix + "Id";
                String idClassContent = generateIdClassContent(basePackageName, idClassName);
                Files.write(Paths.get(valueObjectDir.toString(), idClassName + ".java"), idClassContent.getBytes());

                List<Map<String, String>> columns = getColumnsForTable(conn, schema, table);
                Path entityDir = Paths.get(domainCoreMainJava.toString(), "entity");
                Files.createDirectories(entityDir);
                String domainEntityClassName = classNamePrefix + "DomainEntity";
                String domainEntityClassContent = generateDomainEntityClassContent(basePackageName, classNamePrefix, domainEntityClassName, idClassName, columns, extendsClass, columnToEnumMap, table, detailedForeignKeys, aggregateRoots);
                Files.write(Paths.get(entityDir.toString(), domainEntityClassName + ".java"), domainEntityClassContent.getBytes());
            }
        } catch (SQLException | IOException e) {
            e.printStackTrace();
        }
    }

    private String generateEnumIfApplicable(String comment, String columnName, String basePackageName, Path domainCoreMainJava) throws IOException {
        if (comment == null || !comment.startsWith("Enum:")) {
            return null;
        }

        String spec = comment.substring("Enum:".length());
        String enumClassName;
        String valuesPart;

        int braceStart = spec.indexOf('{');
        if (braceStart == -1 || !spec.endsWith("}")) {
            return null;
        }

        String classNamePart = spec.substring(0, braceStart).trim();
        enumClassName = !classNamePart.isEmpty() ? classNamePart : snakeKebabCaseToPascalCase(columnName);

        valuesPart = spec.substring(braceStart + 1, spec.length() - 1);
        if (valuesPart.trim().isEmpty()) {
            return null;
        }

        StringBuilder enumValues = new StringBuilder();
        int nextOrdinalToAssign = 1;
        boolean first = true;

        for (String def : valuesPart.split(";")) {
            def = def.trim();
            if (def.isEmpty()) continue;

            String enumConstantName;
            int ordinal;

            if (def.contains("-")) {
                String[] parts = def.split("-", 2);
                try {
                    ordinal = Integer.parseInt(parts[0].trim());
                    enumConstantName = parts[1].trim().replaceAll("[^a-zA-Z0-9_]", "_").toUpperCase(Locale.ENGLISH);
                    nextOrdinalToAssign = ordinal + 1;
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                    continue; // Skip invalid entries
                }
            } else {
                ordinal = nextOrdinalToAssign;
                enumConstantName = def.trim().replaceAll("[^a-zA-Z0-9_]", "_").toUpperCase(Locale.ENGLISH);
                nextOrdinalToAssign++;
            }

            if (!first) {
                enumValues.append(",\n");
            }
            enumValues.append(String.format("    %s(%d)", enumConstantName, ordinal));
            first = false;
        }

        if (enumValues.length() == 0) {
            return null;
        }

        String enumContent = String.format("""
package %s.domain.core.valueobject;

import java.util.stream.Stream;

public enum %s {
%s;

    private final int value;

    %s(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static %s fromValue(int value) {
        return Stream.of(%s.values())
                .filter(targetEnum -> targetEnum.value == value)
                .findFirst()
                .orElse(null);
    }
}
""", basePackageName, enumClassName, enumValues.toString(), enumClassName, enumClassName, enumClassName);

        Path valueObjectDir = Paths.get(domainCoreMainJava.toString(), "valueobject");
        Files.createDirectories(valueObjectDir);
        Files.write(Paths.get(valueObjectDir.toString(), enumClassName + ".java"), enumContent.getBytes());

        return basePackageName + ".domain.core.valueobject." + enumClassName;
    }

    private String generateIdClassContent(String basePackageName, String idClassName) {
        return String.format("""
package %s.domain.core.valueobject;

import java.util.UUID;

public class %s extends BaseId<UUID> {

    public %s(UUID value) {
        super(value);
    }
}
""", basePackageName, idClassName, idClassName);
    }

    private String generateDomainEntityClassContent(String basePackageName, String entityName, String domainEntityClassName, String idClassName, List<Map<String, String>> columns, String extendsClass, Map<String, String> columnToEnumMap, String currentTable, Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys, Set<String> aggregateRoots) {
        StringBuilder fields = new StringBuilder();
        StringBuilder constructorParams = new StringBuilder();
        StringBuilder constructorBody = new StringBuilder();
        StringBuilder getters = new StringBuilder();
        Set<String> imports = new TreeSet<>();

        imports.add(String.format("%s.domain.core.valueobject.%s", basePackageName, idClassName));

        constructorParams.append(String.format("%s id, ", idClassName));
        if (extendsClass.equals("AggregateRoot")) {
            constructorBody.append(String.format("        super.setId(id);%n"));
        } else {
            constructorBody.append(String.format("        this.setId(id);%n"));
        }

        Map<String, ForeignKeyInfo> tableForeignKeys = detailedForeignKeys.getOrDefault(currentTable, new HashMap<>());
        String classNamePrefix = domainEntityClassName.replace("DomainEntity", "");
        String fieldPrefix = classNamePrefix.substring(0, 1).toLowerCase(Locale.ENGLISH) + classNamePrefix.substring(1);

        for (Map<String, String> column : columns) {
            String columnName = column.get("name");
            if (!columnName.equals("id")) {
                String camelCaseName = snakeCaseToCamelCase(columnName);
                String fieldName;
                if (JAVA_KEYWORDS.contains(camelCaseName)) {
                    fieldName = firstCharToLowerCase(classNamePrefix) + snakeKebabCaseToPascalCase(columnName);
                } else {
                    fieldName = camelCaseName;
                }

                String fqnFieldType;
                String columnIdentifier = currentTable + "." + columnName;

                if (columnToEnumMap.containsKey(columnIdentifier)) {
                    fqnFieldType = columnToEnumMap.get(columnIdentifier);
                } else if (tableForeignKeys.containsKey(columnName)) {
                    fqnFieldType = basePackageName + ".domain.core.valueobject." + snakeKebabCaseToPascalCase(tableForeignKeys.get(columnName).getPkTableName()) + "Id";
                } else {
                    fqnFieldType = toJavaType(column.get("type"));
                }

                String simpleFieldType;
                if (fqnFieldType.contains(".")) {
                    if (!fqnFieldType.startsWith("java.lang") && !fqnFieldType.startsWith(basePackageName + ".domain.core.valueobject")) {
                        imports.add(fqnFieldType);
                    } else if (fqnFieldType.startsWith(basePackageName + ".domain.core.valueobject")) {
                        imports.add(fqnFieldType);
                    }
                    simpleFieldType = fqnFieldType.substring(fqnFieldType.lastIndexOf('.') + 1);
                } else {
                    simpleFieldType = fqnFieldType;
                }
                fields.append(String.format("    private final %s %s;%n", simpleFieldType, fieldName));
                constructorParams.append(String.format("%s %s, ", simpleFieldType, fieldName));
                constructorBody.append(String.format("        this.%s = %s;%n", fieldName, fieldName));

                String getterMethodName;
                if (simpleFieldType.equals("Boolean")) {
                    // Always prefix boolean getters with "get" for consistency, as requested.
                    getterMethodName = "get" + capitalizeFirstLetter(fieldName);
                } else {
                    getterMethodName = "get" + capitalizeFirstLetter(fieldName);
                }
                getters.append(String.format("    public %s %s() {%n        return %s;%n    }%n%n", simpleFieldType, getterMethodName, fieldName));
            }
        }

        if (constructorParams.length() > 0) {
            constructorParams.setLength(constructorParams.length() - 2); // Remove last ", "
        }

        StringBuilder importStatements = new StringBuilder();
        for (String imp : imports) {
            importStatements.append(String.format("import %s;%n", imp));
        }

        return String.format("""
package %s.domain.core.entity;

%s
public class %s extends %s<%s> {

%s
    public %s(%s) {
%s    }

%s}
""", basePackageName, importStatements.toString(), domainEntityClassName, extendsClass, idClassName, fields.toString(), domainEntityClassName, constructorParams.toString(), constructorBody.toString(), getters.toString());
    }

    private List<String> getTables(Connection conn, String schema) throws SQLException {
        List<String> tables = new ArrayList<>();
        String query = "SELECT table_name FROM information_schema.tables WHERE table_schema = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, schema);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString("table_name"));
                }
            }
        }
        return tables;
    }

    public Map<String, Set<String>> getForeignKeys(Connection conn, String schema) throws SQLException {
        Map<String, Set<String>> foreignKeys = new HashMap<>();
        Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys = getDetailedForeignKeys(conn, schema);
        
        for (Map.Entry<String, Map<String, ForeignKeyInfo>> entry : detailedForeignKeys.entrySet()) {
            Set<String> referencedTables = entry.getValue().values().stream()
                .map(ForeignKeyInfo::getPkTableName)
                .collect(Collectors.toSet());
            foreignKeys.put(entry.getKey(), referencedTables);
        }
        
        return foreignKeys;
    }

    private static class ForeignKeyInfo {
        private final String pkTableName;
        private final String fkColumnName;
        private final String pkColumnName;

        public ForeignKeyInfo(String pkTableName, String fkColumnName, String pkColumnName) {
            this.pkTableName = pkTableName;
            this.fkColumnName = fkColumnName;
            this.pkColumnName = pkColumnName;
        }

        public String getPkTableName() {
            return pkTableName;
        }

        public String getFkColumnName() {
            return fkColumnName;
        }

        public String getPkColumnName() {
            return pkColumnName;
        }
    }

    private Map<String, Map<String, ForeignKeyInfo>> getDetailedForeignKeys(Connection conn, String schema) throws SQLException {
        Map<String, Map<String, ForeignKeyInfo>> foreignKeys = new HashMap<>();
        DatabaseMetaData metaData = conn.getMetaData();
        List<String> allTables = getTables(conn, schema);
        
        for (String tableName : allTables) {
            try (ResultSet rs = metaData.getImportedKeys(conn.getCatalog(), schema, tableName)) {
                while (rs.next()) {
                    String pkTableName = rs.getString("PKTABLE_NAME");
                    String fkColumnName = rs.getString("FKCOLUMN_NAME");
                    String pkColumnName = rs.getString("PKCOLUMN_NAME");
                    
                    foreignKeys
                        .computeIfAbsent(tableName, k -> new HashMap<>())
                        .put(fkColumnName, new ForeignKeyInfo(pkTableName, fkColumnName, pkColumnName));
                }
            }
        }
        return foreignKeys;
    }

    public Set<String> determineAggregateRootsFromDB(List<String> allTables, Map<String, Set<String>> foreignKeys) {
        Set<String> aggregateRoots = new HashSet<>();
        // Rule 1: Tables with no outgoing FKs are roots.
        for (String table : allTables) {
            if (!foreignKeys.containsKey(table)) {
                aggregateRoots.add(table);
            }
        }

        // Rule 2: Iteratively find tables that only point to existing roots.
        boolean changed;
        do {
            changed = false;
            for (String table : allTables) {
                if (!aggregateRoots.contains(table) && foreignKeys.containsKey(table)) {
                    if (aggregateRoots.containsAll(foreignKeys.get(table))) {
                        aggregateRoots.add(table);
                        changed = true;
                    }
                }
            }
        } while (changed);

        return aggregateRoots;
    }

    private Set<String> determineAggregateRootsFromUserSelection(Map<String, String> tableEntityTypes) {
        Set<String> aggregateRoots = new HashSet<>();

        for (Map.Entry<String, String> entry : tableEntityTypes.entrySet()) {
            if ("AggregateRoot".equals(entry.getValue())) {
                aggregateRoots.add(entry.getKey());
            }
        }

        return aggregateRoots;
    }

    private List<Map<String, String>> getColumnsForTable(Connection conn, String schema, String table) throws SQLException {
        List<Map<String, String>> columns = new ArrayList<>();
        String query = "SELECT c.column_name, c.data_type, pgd.description " +
                "FROM information_schema.columns AS c " +
                "LEFT JOIN pg_catalog.pg_class AS pgc ON pgc.relname = c.table_name AND pgc.relkind = 'r' " +
                "LEFT JOIN pg_catalog.pg_namespace AS pgns ON pgns.oid = pgc.relnamespace AND pgns.nspname = c.table_schema " +
                "LEFT JOIN pg_catalog.pg_description AS pgd ON pgd.objoid = pgc.oid AND pgd.objsubid = c.ordinal_position " +
                "WHERE c.table_schema = ? AND c.table_name = ? " +
                "ORDER BY c.ordinal_position";
        try (PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, schema);
            pstmt.setString(2, table);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> columnData = new HashMap<>();
                    columnData.put("name", rs.getString("column_name"));
                    columnData.put("type", rs.getString("data_type"));
                    columnData.put("comment", rs.getString("description"));
                    columns.add(columnData);
                }
            }
        }
        return columns;
    }

    private String toJavaType(String dbType) {
        switch (dbType.toLowerCase(Locale.ENGLISH)) {
            case "uuid":
                return "java.util.UUID";
            case "character varying":
            case "varchar":
            case "text":
            case "bpchar":
            case "character":
                return "String";
            case "jsonb":
                return "String"; // Representing jsonb as String is a safe default
            case "timestamp without time zone":
            case "timestamp":
                return "java.time.LocalDateTime";
            case "timestamptz":
            case "timestamp with time zone":
                return "java.time.ZonedDateTime";
            case "date":
                return "java.time.LocalDate";
            case "numeric":
                return "java.math.BigDecimal";
            case "int2":
            case "smallint":
                return "Short";
            case "int4":
            case "integer":
                return "Integer";
            case "int8":
            case "bigint":
                return "Long";
            case "boolean":
            case "bool":
                return "Boolean";
            default:
                return "Object";
        }
    }

    private static final Set<String> JAVA_KEYWORDS = new HashSet<>(Arrays.asList(
            "abstract", "continue", "for", "new", "switch", "assert", "default", "goto", "package", "synchronized",
            "boolean", "do", "if", "private", "this", "break", "double", "implements", "protected", "throw",
            "byte", "else", "import", "public", "throws", "case", "enum", "instanceof", "return", "transient",
            "catch", "extends", "int", "short", "try", "char", "final", "interface", "static", "void",
            "class", "finally", "long", "strictfp", "volatile", "const", "float", "native", "super", "while",
            "true", "false", "null"
    ));

    private void generateApplicationServiceClasses(ProjectRequest projectRequest, EnvironmentalCredentialsRequest envRequest, Path appServiceMainJava, Path domainCoreMainJava, String basePackageName) throws IOException {
        try (Connection conn = DriverManager.getConnection(envRequest.getLocalDatasourceUrl(), envRequest.getLocalDatasourceUsername(), envRequest.getLocalDatasourcePassword())) {
            String schema = envRequest.getSelectedSchema();
            List<String> tables = getTables(conn, schema);
            Map<String, Set<String>> foreignKeys = getForeignKeys(conn, schema);
            Set<String> aggregateRoots = determineAggregateRootsFromUserSelection(envRequest.getTableEntityTypes());
            Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys = getDetailedForeignKeys(conn, schema);

            Map<String, String> columnToEnumMap = new HashMap<>();
            for (String table : tables) {
                List<Map<String, String>> columns = getColumnsForTable(conn, schema, table);
                for (Map<String, String> column : columns) {
                    String comment = column.get("comment");
                    if (comment != null && !comment.isBlank()) {
                        String enumFqn = generateEnumIfApplicable(comment, column.get("name"), basePackageName, domainCoreMainJava);
                        if (enumFqn != null) {
                            columnToEnumMap.put(table + "." + column.get("name"), enumFqn);
                        }
                    }
                }
            }

            String domainMapperName = snakeKebabCaseToPascalCase(projectRequest.getArtifactId()) + "DomainMapper";

            // Domain Entities (non-aggregate tables) should not have command/query packages generated

            // Generate repositories and full command classes for aggregate roots (includes GetByIdResponse DTOs)
            for (String table : tables) {
                if (aggregateRoots.contains(table)) {
                    generateRepositoryInterface(table, basePackageName, appServiceMainJava);
                    generateCommandClasses(table, basePackageName, appServiceMainJava, conn, schema, detailedForeignKeys, aggregateRoots, columnToEnumMap, domainMapperName, projectRequest);
                    generateApplicationServiceInterface(table, basePackageName, appServiceMainJava);
                    generateApplicationServiceImplementation(table, basePackageName, appServiceMainJava, domainMapperName, projectRequest);
                }
            }

            // Generate DomainMapper only for Aggregate Roots (not Domain Entities)
            generateDomainMapper(domainMapperName, basePackageName, appServiceMainJava, aggregateRoots, conn, schema, columnToEnumMap, detailedForeignKeys, aggregateRoots);

            // Domain Entities (non-aggregate tables) should not have query handlers generated
        } catch (SQLException e) {
            throw new RuntimeException("Failed to generate application service classes", e);
        }
    }

    private void generateDomainMapper(String domainMapperName, String basePackageName, Path appServiceMainJava, Set<String> aggregateRoots, Connection conn, String schema, Map<String, String> columnToEnumMap, Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys, Set<String> allAggregateRoots) throws IOException, SQLException {
        Path mapperDir = Paths.get(appServiceMainJava.toString(), "mapper");
        Files.createDirectories(mapperDir);
        StringBuilder methods = new StringBuilder();
        Set<String> mapperImports = new TreeSet<>();

        mapperImports.add("import org.springframework.stereotype.Component;");
        mapperImports.add(String.format("import %s.domain.core.entity.*;", basePackageName));
        mapperImports.add(String.format("import %s.domain.core.valueobject.*;", basePackageName));
        mapperImports.add("import java.time.ZonedDateTime;");

        for (String rootTable : aggregateRoots) {
            String entityName = snakeKebabCaseToPascalCase(rootTable);
            String domainEntityName = entityName + "DomainEntity";
            String createCommandName = "Create" + entityName + "Command";
            String createCommandVar = firstCharToLowerCase(createCommandName);
            String createResponseName = "Create" + entityName + "Response";
            String updateCommandName = "Update" + entityName + "Command";
            String updateCommandVar = firstCharToLowerCase(updateCommandName);
            String updateResponseName = "Update" + entityName + "Response";
            String getByIdResponseName = "GetById" + entityName + "Response";
            String queryResponseName = entityName + "QueryResponse";
            String entityNameLower = rootTable.toLowerCase(Locale.ENGLISH).replace("_", "");

            // Generate imports for commands and responses (only processing aggregate roots now)
            mapperImports.add(String.format("import %s.domain.applicationservice.commands.%s.create.%s;", basePackageName, entityNameLower, createCommandName));
            mapperImports.add(String.format("import %s.domain.applicationservice.commands.%s.create.%s;", basePackageName, entityNameLower, createResponseName));
            mapperImports.add(String.format("import %s.domain.applicationservice.commands.%s.update.%s;", basePackageName, entityNameLower, updateCommandName));
            mapperImports.add(String.format("import %s.domain.applicationservice.commands.%s.update.%s;", basePackageName, entityNameLower, updateResponseName));
            mapperImports.add(String.format("import %s.domain.applicationservice.queries.%s.query.%s;", basePackageName, entityNameLower, queryResponseName));
            mapperImports.add(String.format("import %s.domain.applicationservice.queries.%s.getbyid.%s;", basePackageName, entityNameLower, getByIdResponseName));
            
            List<Map<String, String>> columns = getColumnsForTable(conn, schema, rootTable);
            Map<String, ForeignKeyInfo> tableForeignKeys = detailedForeignKeys.getOrDefault(rootTable, new HashMap<>());

            // Generate Command to DomainEntity mapping
            StringBuilder domainEntityConstructorArgs = new StringBuilder();
            domainEntityConstructorArgs.append(String.format("new %s(null)", entityName + "Id")); // For ID, always new ID(null) for creation

            StringBuilder responseConstructorArgs = new StringBuilder();
            String rootTableCamelCase = snakeCaseToCamelCase(rootTable);
            responseConstructorArgs.append(String.format("%sDomainEntity.getId().getValue()", rootTableCamelCase));

            for (Map<String, String> column : columns) {
                String columnName = column.get("name");
                if (columnName.equals("id")) continue;

                String camelCaseName = snakeCaseToCamelCase(columnName);
                String fieldName;
                if (JAVA_KEYWORDS.contains(camelCaseName)) {
                    fieldName = firstCharToLowerCase(entityName) + snakeKebabCaseToPascalCase(columnName);
                } else {
                    fieldName = camelCaseName;
                }

                String columnIdentifier = rootTable + "." + columnName;

                // For Command to DomainEntity mapping (CREATE)
                if (columnToEnumMap.containsKey(columnIdentifier)) {
                    domainEntityConstructorArgs.append(String.format(", %s.get%s()", createCommandVar, capitalizeFirstLetter(fieldName)));
                } else if (tableForeignKeys.containsKey(columnName)) {
                    String referencedEntityPascal = snakeKebabCaseToPascalCase(tableForeignKeys.get(columnName).getPkTableName());
                    domainEntityConstructorArgs.append(String.format(", new %sId(%s.get%s())", referencedEntityPascal, createCommandVar, capitalizeFirstLetter(fieldName)));
                } else if (columnName.equals("occurred_at")) {
                    domainEntityConstructorArgs.append(", now"); // Special handling for 'now' from handler
                } else {
                    domainEntityConstructorArgs.append(String.format(", %s.get%s()", createCommandVar, capitalizeFirstLetter(fieldName)));
                }

                // For DomainEntity to Response mapping (CREATE/UPDATE)
                if (columnToEnumMap.containsKey(columnIdentifier)) {
                    responseConstructorArgs.append(String.format(", %sDomainEntity.get%s()", rootTableCamelCase, capitalizeFirstLetter(fieldName)));
                } else if (tableForeignKeys.containsKey(columnName)) {
                    responseConstructorArgs.append(String.format(", %sDomainEntity.get%s().getValue()", rootTableCamelCase, capitalizeFirstLetter(fieldName)));
                } else {
                    responseConstructorArgs.append(String.format(", %sDomainEntity.get%s()", rootTableCamelCase, capitalizeFirstLetter(fieldName)));
                }
            }

            // Generate CREATE and UPDATE mapping methods (only processing aggregate roots now)
            methods.append(String.format("    public %s %sTo%s(%s %s, ZonedDateTime now) {\n", domainEntityName, createCommandVar, domainEntityName, createCommandName, createCommandVar));
            methods.append(String.format("        return new %s(%s);\n", domainEntityName, domainEntityConstructorArgs.toString()));
            methods.append("    }\n\n");

            methods.append(String.format("    public %s %sTo%s(%s %s) {\n", createResponseName, firstCharToLowerCase(domainEntityName), createResponseName, domainEntityName, firstCharToLowerCase(domainEntityName)));
            methods.append(String.format("        return new %s(%s);\n", createResponseName, responseConstructorArgs.toString()));
            methods.append("    }\n\n");

            // --- UPDATE methods (NEW) ---
            // 1. UpdateCommand to DomainEntity
            StringBuilder updateDomainEntityConstructorArgs = new StringBuilder();
            updateDomainEntityConstructorArgs.append(String.format("new %s(update%sCommand.getId())", entityName + "Id", entityName));
            for (Map<String, String> column : columns) {
                String columnName = column.get("name");
                if (columnName.equals("id")) continue;
                String camelCaseName = snakeCaseToCamelCase(columnName);
                String fieldName;
                if (JAVA_KEYWORDS.contains(camelCaseName)) {
                    fieldName = firstCharToLowerCase(entityName) + snakeKebabCaseToPascalCase(columnName);
                } else {
                    fieldName = camelCaseName;
                }
                String columnIdentifier = rootTable + "." + columnName;
                if (columnToEnumMap.containsKey(columnIdentifier)) {
                    updateDomainEntityConstructorArgs.append(String.format(", update%sCommand.get%s()", entityName, capitalizeFirstLetter(fieldName)));
                } else if (tableForeignKeys.containsKey(columnName)) {
                    String referencedEntityPascal = snakeKebabCaseToPascalCase(tableForeignKeys.get(columnName).getPkTableName());
                    updateDomainEntityConstructorArgs.append(String.format(", new %sId(update%sCommand.get%s())", referencedEntityPascal, entityName, capitalizeFirstLetter(fieldName)));
                } else if (columnName.equals("occurred_at")) {
                    updateDomainEntityConstructorArgs.append(", now");
                } else {
                    updateDomainEntityConstructorArgs.append(String.format(", update%sCommand.get%s()", entityName, capitalizeFirstLetter(fieldName)));
                }
            }
            methods.append(String.format("    public %s update%sCommandTo%s(%s update%sCommand, ZonedDateTime now) {\n", domainEntityName, entityName, domainEntityName, updateCommandName, entityName));
            methods.append(String.format("        return new %s(%s);\n", domainEntityName, updateDomainEntityConstructorArgs.toString()));
            methods.append("    }\n\n");

            // 2. DomainEntity to UpdateResponse
            methods.append(String.format("    public %s %sDomainEntityToUpdate%sResponse(%s %sDomainEntity) {\n", updateResponseName, rootTableCamelCase, entityName, domainEntityName, rootTableCamelCase));
            methods.append(String.format("        return new %s(%s);\n", updateResponseName, responseConstructorArgs.toString()));
            methods.append("    }\n\n");

            // Add QueryResponse mapping method
            methods.append(String.format("    public %s %sDomainEntityTo%s(%s %s) {\n", queryResponseName, firstCharToLowerCase(entityName), queryResponseName, domainEntityName, firstCharToLowerCase(domainEntityName)));
            methods.append(String.format("        return new %s(%s);\n", queryResponseName, responseConstructorArgs.toString()));
            methods.append("    }\n\n");

            // --- GET BY ID methods ---
            methods.append(String.format("    public %s %sDomainEntityToGetById%sResponse(%s %sDomainEntity) {\n", getByIdResponseName, rootTableCamelCase, entityName, domainEntityName, rootTableCamelCase));
            methods.append(String.format("        return new %s(%s);\n", getByIdResponseName, responseConstructorArgs.toString()));
            methods.append("    }\n\n");
        }

        String importStatements = mapperImports.stream().collect(Collectors.joining("\n"));

        String content = String.format("""
package %s.domain.applicationservice.mapper;

%s

@Component
public class %s {

%s
}
""", basePackageName, importStatements, domainMapperName, methods.toString());
    Files.write(Paths.get(mapperDir.toString(), domainMapperName + ".java"), content.getBytes());
}

private void generateRepositoryInterface(String tableName, String basePackageName, Path appServiceMainJava) throws IOException {
    String entityName = snakeKebabCaseToPascalCase(tableName);
    String domainEntityName = entityName + "DomainEntity";
    String repositoryName = entityName + "Repository";
    Path repoDir = Paths.get(appServiceMainJava.toString(), "ports", "output", "repository");
    Files.createDirectories(repoDir);
    StringBuilder content = new StringBuilder();
    content.append("package " + basePackageName + ".domain.applicationservice.ports.output.repository;\n\n");
    content.append("import java.util.Optional;\n");
    content.append("import java.util.UUID;\n");
    content.append("import java.time.ZonedDateTime;\n");
    content.append("import " + basePackageName + ".domain.core.entity." + domainEntityName + ";\n");
    content.append("import " + basePackageName + ".domain.core.payload.BaseQueryResponse;\n");
    content.append("import " + basePackageName + ".domain.applicationservice.queries." + tableName.toLowerCase(Locale.ENGLISH).replace("_", "") + ".query." + entityName + "Query;\n");
    content.append("\npublic interface " + repositoryName + " {\n\n");
    content.append("    " + domainEntityName + " create(" + domainEntityName + " entity, UUID createdBy, ZonedDateTime now);\n");
    content.append("    " + domainEntityName + " update(" + domainEntityName + " entity, UUID updatedBy, ZonedDateTime now);\n");
    content.append("    " + domainEntityName + " delete(" + domainEntityName + " entity, UUID updatedBy, ZonedDateTime now);\n");
    content.append("    Optional<" + domainEntityName + "> getById(UUID id);\n");
    content.append("    BaseQueryResponse<" + domainEntityName + "> query(" + entityName + "Query query);\n");
    content.append("}\n");
    Files.write(Paths.get(repoDir.toString(), repositoryName + ".java"), content.toString().getBytes());
}

    private void generateCommandClasses(String tableName, String basePackageName, Path appServiceMainJava, Connection conn, String schema, Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys, Set<String> aggregateRoots, Map<String, String> columnToEnumMap, String domainMapperName, ProjectRequest projectRequest) throws IOException, SQLException {
        String entityName = snakeKebabCaseToPascalCase(tableName);
        String entityNameLower = tableName.toLowerCase(Locale.ENGLISH).replace("_", "");
        Path createCommandDir = Paths.get(appServiceMainJava.toString(), "commands", entityNameLower, "create");
        Files.createDirectories(createCommandDir);
        Path updateCommandDir = Paths.get(appServiceMainJava.toString(), "commands", entityNameLower, "update");
        Files.createDirectories(updateCommandDir);
        Path deleteCommandDir = Paths.get(appServiceMainJava.toString(), "commands", entityNameLower, "delete");
        Files.createDirectories(deleteCommandDir);
        Files.createDirectories(Paths.get(appServiceMainJava.toString(), "queries", entityNameLower, "getbyid"));

        List<Map<String, String>> columns = getColumnsForTable(conn, schema, tableName);

        // Create
        String createCommandContent = generateCreateCommand(entityName, basePackageName, columns, tableName, detailedForeignKeys, aggregateRoots, columnToEnumMap);
        Files.write(Paths.get(createCommandDir.toString(), "Create" + entityName + "Command.java"), createCommandContent.getBytes());

        String createResponseContent = generateCreateResponse(entityName, basePackageName, columns, tableName, detailedForeignKeys, aggregateRoots, columnToEnumMap);
        Files.write(Paths.get(createCommandDir.toString(), "Create" + entityName + "Response.java"), createResponseContent.getBytes());

        String commandHandlerContent = generateCreateCommandHandler(entityName, basePackageName, domainMapperName);
        Files.write(Paths.get(createCommandDir.toString(), entityName + "CreateCommandHandler.java"), commandHandlerContent.getBytes());

        // Update
        String updateCommandContent = generateUpdateCommand(entityName, basePackageName, columns, tableName, detailedForeignKeys, aggregateRoots, columnToEnumMap);
        Files.write(Paths.get(updateCommandDir.toString(), "Update" + entityName + "Command.java"), updateCommandContent.getBytes());

        String updateResponseContent = generateUpdateResponse(entityName, basePackageName, columns, tableName, detailedForeignKeys, aggregateRoots, columnToEnumMap);
        Files.write(Paths.get(updateCommandDir.toString(), "Update" + entityName + "Response.java"), updateResponseContent.getBytes());

        String updateHandlerContent = generateUpdateCommandHandler(entityName, basePackageName, domainMapperName);
        Files.write(Paths.get(updateCommandDir.toString(), entityName + "UpdateCommandHandler.java"), updateHandlerContent.getBytes());

        String updateCommandName = "Update" + entityName + "Command";
        String updateCommandVar = firstCharToLowerCase(updateCommandName);

        // Delete
        String deleteResponseContent = generateDeleteResponse(entityName, basePackageName);
        Files.write(Paths.get(deleteCommandDir.toString(), "Delete" + entityName + "Response.java"), deleteResponseContent.getBytes());

        String deleteHandlerContent = generateDeleteCommandHandler(entityName, basePackageName);
        Files.write(Paths.get(deleteCommandDir.toString(), entityName + "DeleteCommandHandler.java"), deleteHandlerContent.getBytes());

        // Queries - getById
        Path getByIdQueryDir = Paths.get(appServiceMainJava.toString(), "queries", entityNameLower, "getbyid");
        Files.createDirectories(getByIdQueryDir);
        String getByIdQueryHandlerContent = generateGetByIdQueryHandler(entityName, basePackageName, domainMapperName);
        Files.write(Paths.get(getByIdQueryDir.toString(), entityName + "GetByIdQueryHandler.java"), getByIdQueryHandlerContent.getBytes());
        String getByIdResponseContent = generateGetByIdResponse(entityName, basePackageName, columns, tableName, detailedForeignKeys, aggregateRoots, columnToEnumMap);
        Files.write(Paths.get(getByIdQueryDir.toString(), "GetById" + entityName + "Response.java"), getByIdResponseContent.getBytes());

        // General Query
        Path queryDir = Paths.get(appServiceMainJava.toString(), "queries", entityNameLower, "query");
        Files.createDirectories(queryDir);
        String queryDtoContent = generateGeneralQueryDto(entityName, basePackageName, columns, tableName, detailedForeignKeys, aggregateRoots, columnToEnumMap);
        Files.write(Paths.get(queryDir.toString(), entityName + "Query.java"), queryDtoContent.getBytes());
        String queryResponseContent = generateGeneralQueryResponse(entityName, basePackageName, columns, tableName, detailedForeignKeys, aggregateRoots, columnToEnumMap);
        Files.write(Paths.get(queryDir.toString(), entityName + "QueryResponse.java"), queryResponseContent.getBytes());
        String queryHandlerContent = generateGeneralQueryHandler(entityName, basePackageName, domainMapperName);
        Files.write(Paths.get(queryDir.toString(), entityName + "QueryHandler.java"), queryHandlerContent.getBytes());
    }

    // Helper to determine if a column is filterable for Query DTO
    private boolean isFilterableField(String columnName, String dbType) {
        String lower = columnName.toLowerCase(Locale.ENGLISH);
        if (lower.equals("id") || lower.endsWith("_id") || lower.endsWith("id")) return false;
        if (dbType == null) return false;
        String type = dbType.toLowerCase(Locale.ENGLISH);
        return type.contains("char") || type.contains("text") || type.contains("enum") || type.contains("bool");
    }

    private String generateGeneralQueryDto(String entityName, String basePackageName, List<Map<String, String>> columns, String currentTable, Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys, Set<String> aggregateRoots, Map<String, String> columnToEnumMap) {
        StringBuilder fields = new StringBuilder();
        StringBuilder constructorParams = new StringBuilder();
        StringBuilder constructorBody = new StringBuilder();
        Set<String> imports = new TreeSet<>();
        imports.add("import lombok.Getter;");
        imports.add("import lombok.ToString;");
        imports.add("import " + basePackageName + ".domain.core.payload.BaseQuery;");
        for (Map<String, String> column : columns) {
            String columnName = column.get("name");
            String dbType = column.get("type");
            if (!isFilterableField(columnName, dbType)) continue;
            String camelCaseName = snakeCaseToCamelCase(columnName);
            String safeFieldName = getSafeFieldName(entityName, camelCaseName);
            String fieldType;
            String columnIdentifier = currentTable + "." + columnName;
            if (columnToEnumMap.containsKey(columnIdentifier)) {
                String fqn = columnToEnumMap.get(columnIdentifier);
                fieldType = fqn.substring(fqn.lastIndexOf('.') + 1);
                imports.add("import " + fqn + ";");
            } else {
                String fqn = toJavaType(dbType);
                if (fqn.contains(".")) {
                    fieldType = fqn.substring(fqn.lastIndexOf('.') + 1);
                    if (!fqn.startsWith("java.lang")) imports.add("import " + fqn + ";");
                } else {
                    fieldType = fqn;
                }
            }
            fields.append("    private final " + fieldType + " " + safeFieldName + ";\n");
            constructorParams.append(fieldType + " " + safeFieldName + ", ");
            constructorBody.append("        this." + safeFieldName + " = " + safeFieldName + ";\n");
        }
        // Constructor with all fields + super fields
        String params = "Integer pageNo, Integer pageSize, String sortBy, String sortDirection, " + constructorParams.toString();
        if (params.endsWith(", ")) params = params.substring(0, params.length() - 2);
        StringBuilder classContent = new StringBuilder();
        classContent.append("package " + basePackageName + ".domain.applicationservice.queries." + entityName.toLowerCase(Locale.ENGLISH) + ".query;\n\n");
        for (String imp : imports) classContent.append(imp + "\n");
        classContent.append("\n@Getter\n@ToString\npublic class " + entityName + "Query extends BaseQuery {\n\n");
        classContent.append(fields);
        classContent.append("\n    public " + entityName + "Query(" + params + ") {\n");
        classContent.append("        super(pageNo, pageSize, sortBy, sortDirection);\n");
        classContent.append(constructorBody);
        classContent.append("    }\n}");
        return classContent.toString();
    }

    private String generateGeneralQueryResponse(String entityName, String basePackageName, List<Map<String, String>> columns, String currentTable, Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys, Set<String> aggregateRoots, Map<String, String> columnToEnumMap) {
        StringBuilder fields = new StringBuilder();
        StringBuilder constructorParams = new StringBuilder();
        StringBuilder constructorBody = new StringBuilder();
        Set<String> imports = new TreeSet<>();
        imports.add("import lombok.Getter;");
        imports.add("import java.util.UUID;");
        
        // Always include id field first
        fields.append("    private final UUID id;\n\n");
        constructorParams.append("UUID id, ");
        constructorBody.append("        this.id = id;\n");
        
        for (Map<String, String> column : columns) {
            String columnName = column.get("name");
            // Skip id column since we already added it
            if (columnName.equals("id")) continue;
            String dbType = column.get("type");
            String camelCaseName = snakeCaseToCamelCase(columnName);
            String safeFieldName = getSafeFieldName(entityName, camelCaseName);
            String fieldType;
            String columnIdentifier = currentTable + "." + columnName;
            if (columnToEnumMap.containsKey(columnIdentifier)) {
                String fqn = columnToEnumMap.get(columnIdentifier);
                fieldType = fqn.substring(fqn.lastIndexOf('.') + 1);
                imports.add("import " + fqn + ";");
            } else {
                String fqn = toJavaType(dbType);
                if (fqn.contains(".")) {
                    fieldType = fqn.substring(fqn.lastIndexOf('.') + 1);
                    if (!fqn.startsWith("java.lang")) imports.add("import " + fqn + ";");
                } else {
                    fieldType = fqn;
                }
            }
            fields.append("    private final " + fieldType + " " + safeFieldName + ";\n");
            constructorParams.append(fieldType + " " + safeFieldName + ", ");
            constructorBody.append("        this." + safeFieldName + " = " + safeFieldName + ";\n");
        }
        String params = constructorParams.toString();
        if (params.endsWith(", ")) params = params.substring(0, params.length() - 2);
        StringBuilder classContent = new StringBuilder();
        classContent.append("package " + basePackageName + ".domain.applicationservice.queries." + entityName.toLowerCase(Locale.ENGLISH) + ".query;\n\n");
        for (String imp : imports) classContent.append(imp + "\n");
        classContent.append("\n@Getter\npublic class " + entityName + "QueryResponse {\n\n");
        classContent.append(fields);
        classContent.append("\n    public " + entityName + "QueryResponse(" + params + ") {\n");
        classContent.append(constructorBody);
        classContent.append("    }\n}");
        return classContent.toString();
    }

    private String generateGeneralQueryHandler(String entityName, String basePackageName, String domainMapperName) {
        String repositoryName = entityName + "Repository";
        String repositoryVar = firstCharToLowerCase(repositoryName);
        String domainMapperVar = firstCharToLowerCase(domainMapperName);
        String queryDto = entityName + "Query";
        String queryResponse = entityName + "QueryResponse";
        String domainEntity = entityName + "DomainEntity";
        Set<String> imports = new TreeSet<>();
        imports.add("import lombok.extern.slf4j.Slf4j;");
        imports.add("import org.springframework.stereotype.Component;");
        imports.add("import org.springframework.transaction.annotation.Transactional;");
        imports.add("import java.util.ArrayList;");
        imports.add("import java.util.List;");
        imports.add("import " + basePackageName + ".domain.core.payload.BaseQueryResponse;");
        imports.add("import " + basePackageName + ".domain.core.entity." + domainEntity + ";");
        imports.add("import " + basePackageName + ".domain.core.exception.DomainEntityNotFoundException;");
        imports.add("import " + basePackageName + ".domain.applicationservice.ports.output.repository." + repositoryName + ";");
        imports.add("import " + basePackageName + ".domain.applicationservice.mapper." + domainMapperName + ";");
        StringBuilder classContent = new StringBuilder();
        classContent.append("package " + basePackageName + ".domain.applicationservice.queries." + entityName.toLowerCase(Locale.ENGLISH) + ".query;\n\n");
        for (String imp : imports) classContent.append(imp + "\n");
        classContent.append("\n@Slf4j\n@Component\npublic class " + entityName + "QueryHandler {\n\n");
        classContent.append("    private final " + repositoryName + " " + repositoryVar + ";\n");
        classContent.append("    private final " + domainMapperName + " " + domainMapperVar + ";\n\n");
        classContent.append("    public " + entityName + "QueryHandler(" + repositoryName + " " + repositoryVar + ", " + domainMapperName + " " + domainMapperVar + ") {\n");
        classContent.append("        this." + repositoryVar + " = " + repositoryVar + ";\n");
        classContent.append("        this." + domainMapperVar + " = " + domainMapperVar + ";\n    }\n\n");
        classContent.append("    @Transactional\n");
        classContent.append("    public BaseQueryResponse<" + queryResponse + "> query(" + queryDto + " query) {\n");
        classContent.append("        BaseQueryResponse<" + domainEntity + "> entityList = " + repositoryVar + ".query(query);\n");
        classContent.append("        if (entityList.content().isEmpty()) {\n");
        classContent.append("            log.error(\"Could not find any " + entityName.toLowerCase(Locale.ENGLISH) + " with given filters: {}\", query);\n");
        classContent.append("            throw new DomainEntityNotFoundException();\n        }\n");
        classContent.append("        List<" + queryResponse + "> responseList = new ArrayList<>();\n");
        classContent.append("        entityList.content().forEach(e -> responseList.add(" + domainMapperVar + "." + firstCharToLowerCase(entityName) + "DomainEntityTo" + queryResponse + "(e)));\n");
        classContent.append("        return new BaseQueryResponse<>(responseList,\n");
        classContent.append("                entityList.pageNo(),\n");
        classContent.append("                entityList.pageSize(),\n");
        classContent.append("                entityList.totalElements(),\n");
        classContent.append("                entityList.totalPages(),\n");
        classContent.append("                entityList.isLast());\n    }\n}\n");
        return classContent.toString();
    }

    private String generateCreateCommandHandler(String entityName, String basePackageName, String domainMapperName) {
        String repositoryName = entityName + "Repository";
        String repositoryVarName = firstCharToLowerCase(repositoryName);
        String commandName = "Create" + entityName + "Command";
        String commandVarName = firstCharToLowerCase(commandName);
        String domainEntityName = entityName + "DomainEntity";
        String domainMapperVarName = firstCharToLowerCase(domainMapperName);

        return String.format("""
package %s.domain.applicationservice.commands.%s.create;

import %s.domain.applicationservice.mapper.%s;
import %s.domain.applicationservice.ports.output.repository.%s;
import %s.domain.core.entity.%s;
import %s.domain.core.exception.RepositoryOutputPortException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.ZonedDateTime;

@Slf4j
@Component
public class %sCreateCommandHandler {

    private final %s %s;
    private final %s %s;

    public %sCreateCommandHandler(%s %s,
                                    %s %s) {
        this.%s = %s;
        this.%s = %s;
    }

    @Transactional
    public %s create%s(%s %s) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        %s domainEntity = %s.%sTo%s(%s, now);
        %s savedDomainEntity = %s.create(domainEntity, %s.getCreatedBy(), now);
        if (savedDomainEntity == null) {
            log.error("Could not create %s");
            throw new RepositoryOutputPortException();
        }
        log.info("Returning %s for %s id: {}", savedDomainEntity.getId().getValue());
        return savedDomainEntity;
    }
}
""",
            basePackageName, entityName.toLowerCase(Locale.ENGLISH),
            basePackageName, domainMapperName,
            basePackageName, repositoryName,
            basePackageName, domainEntityName,
            basePackageName,
            entityName, repositoryName, repositoryVarName, domainMapperName, domainMapperVarName,
            entityName, repositoryName, repositoryVarName, domainMapperName, domainMapperVarName,
            repositoryVarName, repositoryVarName, domainMapperVarName, domainMapperVarName,
            domainEntityName, entityName, commandName, commandVarName,
            domainEntityName, domainMapperVarName, commandVarName, domainEntityName, commandVarName,
            domainEntityName, repositoryVarName, commandVarName,
            entityName.toLowerCase(Locale.ENGLISH),
            domainEntityName, entityName.toLowerCase(Locale.ENGLISH));
    }

    private String generateCreateCommand(String entityName, String basePackageName, List<Map<String, String>> columns, String currentTable, Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys, Set<String> aggregateRoots, Map<String, String> columnToEnumMap) {
        StringBuilder fields = new StringBuilder();
        Set<String> imports = new TreeSet<>();
        imports.add("jakarta.validation.constraints.NotNull;");
        imports.add("lombok.AllArgsConstructor;");
        imports.add("lombok.Builder;");
        imports.add("lombok.Getter;");
        imports.add("lombok.ToString;");
        imports.add("com.fasterxml.jackson.annotation.JsonIgnoreProperties;");
        imports.add("java.util.UUID;");

        Map<String, ForeignKeyInfo> tableForeignKeys = detailedForeignKeys.getOrDefault(currentTable, new HashMap<>());

        // Check if createdBy field already exists in database columns
        boolean hasCreatedByColumn = columns.stream()
            .anyMatch(column -> "created_by".equals(column.get("name")));
        
        // Add createdBy field only if it doesn't exist in database columns
        if (!hasCreatedByColumn) {
            fields.append("    @NotNull\n");
            fields.append("    private final UUID createdBy;\n\n");
        }

        for (Map<String, String> column : columns) {
            String columnName = column.get("name");
            if (columnName.equals("id")) continue;

            String camelCaseName = snakeCaseToCamelCase(columnName);
            String fieldName;
            if (JAVA_KEYWORDS.contains(camelCaseName)) {
                fieldName = firstCharToLowerCase(entityName) + snakeKebabCaseToPascalCase(columnName);
            } else {
                fieldName = camelCaseName;
            }

            String fqnFieldType;
            String columnIdentifier = currentTable + "." + columnName;

            if (columnToEnumMap.containsKey(columnIdentifier)) {
                fqnFieldType = columnToEnumMap.get(columnIdentifier);
            } else if (tableForeignKeys.containsKey(columnName)) {
                fqnFieldType = "java.util.UUID";
            } else {
                fqnFieldType = toJavaType(column.get("type"));
            }

            String simpleFieldType;
            if (fqnFieldType.contains(".")) {
                if (!fqnFieldType.startsWith("java.lang")) {
                    imports.add(fqnFieldType + ";");
                }
                simpleFieldType = fqnFieldType.substring(fqnFieldType.lastIndexOf('.') + 1);
            } else {
                simpleFieldType = fqnFieldType;
            }
            fields.append("    @NotNull\n");
            fields.append(String.format("    private final %s %s;\n\n", simpleFieldType, fieldName));
        }
        String importStatements = imports.stream().map(s -> "import " + s).collect(Collectors.joining("\n"));

        return String.format("""
package %s.domain.applicationservice.commands.%s.create;

%s

@Getter
@Builder
@AllArgsConstructor
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class Create%sCommand {

%s
}
""", basePackageName, entityName.toLowerCase(Locale.ENGLISH), importStatements, entityName, fields.toString());
    }

    private String generateCreateResponse(String entityName, String basePackageName, List<Map<String, String>> columns, String currentTable, Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys, Set<String> aggregateRoots, Map<String, String> columnToEnumMap) {
        StringBuilder fields = new StringBuilder();
        Set<String> imports = new TreeSet<>();
        imports.add("import lombok.AllArgsConstructor;");
        imports.add("import lombok.Getter;");
        imports.add("import java.util.UUID;");

        Map<String, ForeignKeyInfo> tableForeignKeys = detailedForeignKeys.getOrDefault(currentTable, new HashMap<>());

        fields.append("    private final UUID id;\n\n");

        for (Map<String, String> column : columns) {
            String columnName = column.get("name");
            if (columnName.equals("id")) continue;

            String camelCaseName = snakeCaseToCamelCase(columnName);
            String fieldName;
            if (JAVA_KEYWORDS.contains(camelCaseName)) {
                fieldName = firstCharToLowerCase(entityName) + snakeKebabCaseToPascalCase(columnName);
            } else {
                fieldName = camelCaseName;
            }

            String fqnFieldType;
            String columnIdentifier = currentTable + "." + columnName;

            if (columnToEnumMap.containsKey(columnIdentifier)) {
                fqnFieldType = columnToEnumMap.get(columnIdentifier);
            } else if (tableForeignKeys.containsKey(columnName)) {
                fqnFieldType = "java.util.UUID";
            } else {
                fqnFieldType = toJavaType(column.get("type"));
            }

            String simpleFieldType;
            if (fqnFieldType.contains(".")) {
                if (!fqnFieldType.startsWith("java.lang")) {
                    imports.add("import " + fqnFieldType + ";");
                }
                simpleFieldType = fqnFieldType.substring(fqnFieldType.lastIndexOf('.') + 1);
            } else {
                simpleFieldType = fqnFieldType;
            }
            fields.append(String.format("    private final %s %s;\n\n", simpleFieldType, fieldName));
        }
        String importStatements = imports.stream().collect(Collectors.joining("\n"));

        return String.format("""
package %s.domain.applicationservice.commands.%s.create;

%s

@Getter
@AllArgsConstructor
public class Create%sResponse {

%s
}
""", basePackageName, entityName.toLowerCase(Locale.ENGLISH), importStatements, entityName, fields.toString());
    }

    private String generateUpdateCommand(String entityName, String basePackageName, List<Map<String, String>> columns, String currentTable, Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys, Set<String> aggregateRoots, Map<String, String> columnToEnumMap) {
        StringBuilder fields = new StringBuilder();
        Set<String> imports = new TreeSet<>();
        imports.add("jakarta.validation.constraints.NotNull;");
        imports.add("lombok.AllArgsConstructor;");
        imports.add("lombok.Builder;");
        imports.add("lombok.Getter;");
        imports.add("lombok.ToString;");
        imports.add("com.fasterxml.jackson.annotation.JsonIgnoreProperties;");
        imports.add("lombok.Setter;");

        Map<String, ForeignKeyInfo> tableForeignKeys = detailedForeignKeys.getOrDefault(currentTable, new HashMap<>());

        // Always include id for update
        fields.append("    @NotNull\n    @Setter\n    private UUID id;\n\n");
        
        // Check if updatedBy field already exists in database columns
        boolean hasUpdatedByColumn = columns.stream()
            .anyMatch(column -> "updated_by".equals(column.get("name")));
        
        // Add updatedBy field only if it doesn't exist in database columns
        if (!hasUpdatedByColumn) {
            fields.append("    @NotNull\n");
            fields.append("    private final UUID updatedBy;\n\n");
        }
        imports.add("java.util.UUID;");

        for (Map<String, String> column : columns) {
            String columnName = column.get("name");
            if (columnName.equals("id")) continue;

            String camelCaseName = snakeCaseToCamelCase(columnName);
            String fieldName;
            if (JAVA_KEYWORDS.contains(camelCaseName)) {
                fieldName = firstCharToLowerCase(entityName) + snakeKebabCaseToPascalCase(columnName);
            } else {
                fieldName = camelCaseName;
            }

            String fqnFieldType;
            String columnIdentifier = currentTable + "." + columnName;

            if (columnToEnumMap.containsKey(columnIdentifier)) {
                fqnFieldType = columnToEnumMap.get(columnIdentifier);
            } else if (tableForeignKeys.containsKey(columnName)) {
                fqnFieldType = "java.util.UUID";
            } else {
                fqnFieldType = toJavaType(column.get("type"));
            }

            String simpleFieldType;
            if (fqnFieldType.contains(".")) {
                if (!fqnFieldType.startsWith("java.lang")) {
                    imports.add(fqnFieldType + ";");
                }
                simpleFieldType = fqnFieldType.substring(fqnFieldType.lastIndexOf('.') + 1);
            } else {
                simpleFieldType = fqnFieldType;
            }
            fields.append("    @NotNull\n");
            fields.append(String.format("    private final %s %s;\n\n", simpleFieldType, fieldName));
        }
        String importStatements = imports.stream().map(s -> "import " + s).collect(Collectors.joining("\n"));

        return String.format("""
package %s.domain.applicationservice.commands.%s.update;

%s

@Getter
@Builder
@AllArgsConstructor
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class Update%sCommand {

%s}
""", basePackageName, entityName.toLowerCase(Locale.ENGLISH), importStatements, entityName, fields.toString());
    }

    private String generateUpdateResponse(String entityName, String basePackageName, List<Map<String, String>> columns, String currentTable, Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys, Set<String> aggregateRoots, Map<String, String> columnToEnumMap) {
        StringBuilder fields = new StringBuilder();
        Set<String> imports = new TreeSet<>();
        imports.add("import lombok.AllArgsConstructor;");
        imports.add("import lombok.Getter;");
        imports.add("import java.util.UUID;");

        Map<String, ForeignKeyInfo> tableForeignKeys = detailedForeignKeys.getOrDefault(currentTable, new HashMap<>());

        fields.append("    private final UUID id;\n\n");

        for (Map<String, String> column : columns) {
            String columnName = column.get("name");
            if (columnName.equals("id")) continue;

            String camelCaseName = snakeCaseToCamelCase(columnName);
            String fieldName;
            if (JAVA_KEYWORDS.contains(camelCaseName)) {
                fieldName = firstCharToLowerCase(entityName) + snakeKebabCaseToPascalCase(columnName);
            } else {
                fieldName = camelCaseName;
            }

            String fqnFieldType;
            String columnIdentifier = currentTable + "." + columnName;

            if (columnToEnumMap.containsKey(columnIdentifier)) {
                fqnFieldType = columnToEnumMap.get(columnIdentifier);
            } else if (tableForeignKeys.containsKey(columnName)) {
                fqnFieldType = "java.util.UUID";
            } else {
                fqnFieldType = toJavaType(column.get("type"));
            }

            String simpleFieldType;
            if (fqnFieldType.contains(".")) {
                if (!fqnFieldType.startsWith("java.lang")) {
                    imports.add("import " + fqnFieldType + ";");
                }
                simpleFieldType = fqnFieldType.substring(fqnFieldType.lastIndexOf('.') + 1);
            } else {
                simpleFieldType = fqnFieldType;
            }
            fields.append(String.format("    private final %s %s;\n\n", simpleFieldType, fieldName));
        }
        String importStatements = imports.stream().collect(Collectors.joining("\n"));

        return String.format("""
package %s.domain.applicationservice.commands.%s.update;

%s

@Getter
@AllArgsConstructor
public class Update%sResponse {

%s}
""", basePackageName, entityName.toLowerCase(Locale.ENGLISH), importStatements, entityName, fields.toString());
    }

    private String generateUpdateCommandHandler(String entityName, String basePackageName, String domainMapperName) {
        String repositoryName = entityName + "Repository";
        String repositoryVarName = firstCharToLowerCase(repositoryName);
        String commandName = "Update" + entityName + "Command";
        String commandVarName = firstCharToLowerCase(commandName);
        String domainEntityName = entityName + "DomainEntity";
        String domainMapperVarName = firstCharToLowerCase(domainMapperName);

        return String.format("""
package %s.domain.applicationservice.commands.%s.update;

import %s.domain.applicationservice.mapper.%s;
import %s.domain.applicationservice.ports.output.repository.%s;
import %s.domain.core.entity.%s;
import %s.domain.core.exception.RepositoryOutputPortException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.ZonedDateTime;

@Slf4j
@Component
public class %sUpdateCommandHandler {

    private final %s %s;
    private final %s %s;

    public %sUpdateCommandHandler(%s %s,
                                    %s %s) {
        this.%s = %s;
        this.%s = %s;
    }

    @Transactional
    public %s update%s(%s %s) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        %s domainEntity = %s.%sTo%s(%s, now);
        %s updatedDomainEntity = %s.update(domainEntity, %s.getUpdatedBy(), now);
        if (updatedDomainEntity == null) {
            log.error("Could not update %s");
            throw new RepositoryOutputPortException();
        }
        log.info("Returning updated %s for %s id: {}", updatedDomainEntity.getId().getValue());
        return updatedDomainEntity;
    }
}
""",
            basePackageName, entityName.toLowerCase(Locale.ENGLISH),
            basePackageName, domainMapperName,
            basePackageName, repositoryName,
            basePackageName, domainEntityName,
            basePackageName,
            entityName, repositoryName, repositoryVarName, domainMapperName, domainMapperVarName,
            entityName, repositoryName, repositoryVarName, domainMapperName, domainMapperVarName,
            repositoryVarName, repositoryVarName, domainMapperVarName, domainMapperVarName,
            domainEntityName, entityName, commandName, commandVarName,
            domainEntityName, domainMapperVarName, commandVarName, domainEntityName, commandVarName,
            domainEntityName, repositoryVarName, commandVarName,
            entityName.toLowerCase(Locale.ENGLISH),
            domainEntityName, entityName.toLowerCase(Locale.ENGLISH));
    }

    private String generateDeleteCommandHandler(String entityName, String basePackageName) {
        String repositoryName = entityName + "Repository";
        String domainEntityName = entityName + "DomainEntity";
        String entityLower = entityName.toLowerCase(Locale.ENGLISH);
        String repositoryVar = firstCharToLowerCase(repositoryName);
        return String.format("""
package %s.domain.applicationservice.commands.%s.delete;

import %s.domain.applicationservice.ports.output.repository.%s;
import %s.domain.core.entity.%s;
import %s.domain.core.exception.DomainEntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class %sDeleteCommandHandler {

    private final %s %s;

    public %sDeleteCommandHandler(%s %s) {
        this.%s = %s;
    }

    @Transactional
    public Delete%sResponse delete(UUID id, UUID updatedBy) {
        Optional<%s> domainEntityOptional = %s.getById(id);
        if (domainEntityOptional.isPresent()) {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of(\"UTC\"));
            %s deletedDomainEntity = %s.delete(domainEntityOptional.get(), updatedBy, now);
            return new Delete%sResponse(id, now, updatedBy);
        } else {
            throw new DomainEntityNotFoundException();
        }
    }
}
""",
            basePackageName, entityLower,
            basePackageName, repositoryName,
            basePackageName, domainEntityName,
            basePackageName,
            entityName, repositoryName, repositoryVar,
            entityName, repositoryName, repositoryVar,
            repositoryVar, repositoryVar,
            entityName,
            domainEntityName, repositoryVar,
            domainEntityName, repositoryVar,
            entityName
        );
    }

    private String generateDeleteResponse(String entityName, String basePackageName) {
        return String.format("""
package %s.domain.applicationservice.commands.%s.delete;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.time.ZonedDateTime;
import java.util.UUID;

@Getter
@AllArgsConstructor
public class Delete%sResponse {
    private final UUID id;
    private final ZonedDateTime updatedAt;
    private final UUID updatedBy;
}
""",
            basePackageName, entityName.toLowerCase(Locale.ENGLISH), entityName
        );
    }

    private String generateGetByIdQueryHandler(String entityName, String basePackageName, String domainMapperName) {
        String repositoryName = entityName + "Repository";
        String domainEntityName = entityName + "DomainEntity";
        String entityLower = entityName.toLowerCase(Locale.ENGLISH);
        String repositoryVar = firstCharToLowerCase(repositoryName);
        String domainMapperVar = firstCharToLowerCase(domainMapperName);
        String responseClassName = "GetById" + entityName + "Response";
        String entityCamelCase = firstCharToLowerCase(entityName);
        String mappingMethod = entityCamelCase + "DomainEntityToGetById" + entityName + "Response";
        return String.format("""
package %s.domain.applicationservice.queries.%s.getbyid;

import %s.domain.applicationservice.ports.output.repository.%s;
import %s.domain.applicationservice.mapper.%s;
import %s.domain.core.entity.%s;
import %s.domain.core.exception.DomainEntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class %sGetByIdQueryHandler {

    private final %s %s;
    private final %s %s;

    public %sGetByIdQueryHandler(%s %s, %s %s) {
        this.%s = %s;
        this.%s = %s;
    }

    public %s getById(UUID id) {
        Optional<%s> domainEntityOptional = %s.getById(id);
        if (domainEntityOptional.isEmpty()) {
            log.error("Could not find %s by id: {}", id);
            throw new DomainEntityNotFoundException();
        }
        return %s.%s(domainEntityOptional.get());
    }
}
""",
        basePackageName, entityLower,
        basePackageName, repositoryName,
        basePackageName, domainMapperName,
        basePackageName, domainEntityName,
        basePackageName,
        entityName, repositoryName, repositoryVar, domainMapperName, domainMapperVar,
        entityName, repositoryName, repositoryVar, domainMapperName, domainMapperVar,
        repositoryVar, repositoryVar, domainMapperVar, domainMapperVar,
        responseClassName,
        domainEntityName, repositoryVar,
        entityLower,
        domainMapperVar, mappingMethod
    );
    }

    private String generateGetByIdResponse(String entityName, String basePackageName, List<Map<String, String>> columns, String currentTable, Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys, Set<String> aggregateRoots, Map<String, String> columnToEnumMap) {
        StringBuilder fields = new StringBuilder();
        Set<String> imports = new TreeSet<>();
        imports.add("import lombok.AllArgsConstructor;");
        imports.add("import lombok.Getter;");
        imports.add("import java.util.UUID;");

        Map<String, ForeignKeyInfo> tableForeignKeys = detailedForeignKeys.getOrDefault(currentTable, new HashMap<>());

        fields.append("    private final UUID id;\n\n");

        for (Map<String, String> column : columns) {
            String columnName = column.get("name");
            if (columnName.equals("id")) continue;

            String camelCaseName = snakeCaseToCamelCase(columnName);
            String fieldName;
            if (JAVA_KEYWORDS.contains(camelCaseName)) {
                fieldName = firstCharToLowerCase(entityName) + snakeKebabCaseToPascalCase(columnName);
            } else {
                fieldName = camelCaseName;
            }

            String fqnFieldType;
            String columnIdentifier = currentTable + "." + columnName;

            if (columnToEnumMap.containsKey(columnIdentifier)) {
                fqnFieldType = columnToEnumMap.get(columnIdentifier);
            } else if (tableForeignKeys.containsKey(columnName)) {
                fqnFieldType = "java.util.UUID";
            } else {
                fqnFieldType = toJavaType(column.get("type"));
            }

            String simpleFieldType;
            if (fqnFieldType.contains(".")) {
                if (!fqnFieldType.startsWith("java.lang")) {
                    imports.add("import " + fqnFieldType + ";");
                }
                simpleFieldType = fqnFieldType.substring(fqnFieldType.lastIndexOf('.') + 1);
            } else {
                simpleFieldType = fqnFieldType;
            }
            fields.append(String.format("    private final %s %s;\n\n", simpleFieldType, fieldName));
        }
        String importStatements = imports.stream().collect(Collectors.joining("\n"));

        return String.format("""
package %s.domain.applicationservice.queries.%s.getbyid;

%s

@Getter
@AllArgsConstructor
public class GetById%sResponse {

%s}
""",
            basePackageName, entityName.toLowerCase(Locale.ENGLISH), importStatements, entityName, fields.toString());
    }

    private String generateDomainConstantsContent(String basePackageName) {
        return String.format("""
package %s.domain.core;

import java.time.ZoneId;

public class DomainConstants {

    // Date Time Constants
    public static final String UTC = "UTC";

    // Pagination Constants
    public static final int MIN_PAGE_NO = 0;
    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 100;
    public static final String DEFAULT_SORT_BY = "created_at";
    public static final String DEFAULT_SORT_DIRECTION = "desc";
}
""", basePackageName);
    }

    private String generateBaseQueryContent(String basePackageName) {
        return String.format("""
package %s.domain.core.payload;

import %s.domain.core.DomainConstants;

import java.util.Objects;

public class BaseQuery {

    private Integer pageNo = DomainConstants.MIN_PAGE_NO;
    private Integer pageSize = DomainConstants.DEFAULT_PAGE_SIZE;
    private String sortBy = DomainConstants.DEFAULT_SORT_BY;
    private String sortDirection = DomainConstants.DEFAULT_SORT_DIRECTION;

    public BaseQuery(Integer pageNo, Integer pageSize, String sortBy, String sortDirection) {
        this.pageNo = Objects.nonNull(pageNo) ? pageNo : this.pageNo;
        this.pageSize = Objects.nonNull(pageSize)
                ? (pageSize > DomainConstants.MAX_PAGE_SIZE
                    ? DomainConstants.MAX_PAGE_SIZE
                    : pageSize)
                : this.pageSize;
        this.sortBy = Objects.nonNull(sortBy) ? sortBy : this.sortBy;
        this.sortDirection = Objects.nonNull(sortDirection) ? sortDirection : this.sortDirection;
    }

    public Integer getPageNo() {
        return pageNo;
    }

    public void setPageNo(Integer pageNo) {
        this.pageNo = pageNo;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public String getSortBy() {
        return sortBy;
    }

    public void setSortBy(String sortBy) {
        this.sortBy = sortBy;
    }

    public String getSortDirection() {
        return sortDirection;
    }

    public void setSortDirection(String sortDirection) {
        this.sortDirection = sortDirection;
    }
}
""", basePackageName, basePackageName);
    }

    private String generateBaseQueryResponseContent(String basePackageName) {
        return String.format("""
package %s.domain.core.payload;

import java.util.List;

public record BaseQueryResponse<T>(
        List<T> content,
        int pageNo,
        int pageSize,
        long totalElements,
        int totalPages,
        boolean isLast) {
 }
""", basePackageName);
    }

    private boolean isJavaKeyword(String name) {
        return JAVA_KEYWORDS.contains(name);
    }

    private String getSafeFieldName(String entityName, String fieldName) {
        if (isJavaKeyword(fieldName)) {
            return firstCharToLowerCase(entityName) + capitalizeFirstLetter(fieldName);
        }
        return fieldName;
    }

    private void generatePersistenceImplementation(ProjectRequest projectRequest, EnvironmentalCredentialsRequest envRequest, Path persistenceMainJava, String basePackageName, boolean useCrossCuttingLibrary) throws IOException {
        try (Connection conn = DriverManager.getConnection(envRequest.getLocalDatasourceUrl(), envRequest.getLocalDatasourceUsername(), envRequest.getLocalDatasourcePassword())) {
            String schema = envRequest.getSelectedSchema();
            List<String> tables = getTables(conn, schema);
            Map<String, Set<String>> foreignKeys = getForeignKeys(conn, schema);
            Set<String> aggregateRoots = determineAggregateRootsFromUserSelection(envRequest.getTableEntityTypes());
            Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys = getDetailedForeignKeys(conn, schema);
            
            // Create persistence package structure
            Path entityDir = Paths.get(persistenceMainJava.toString(), "entity");
            Path repositoryDir = Paths.get(persistenceMainJava.toString(), "repository");
            Path adapterDir = Paths.get(persistenceMainJava.toString(), "adapter");
            Path mapperDir = Paths.get(persistenceMainJava.toString(), "mapper");
            
            Files.createDirectories(entityDir);
            Files.createDirectories(repositoryDir);
            Files.createDirectories(adapterDir);
            Files.createDirectories(mapperDir);
            
            // Generate JPA entities for all tables
            generateJpaEntities(tables, conn, schema, entityDir, basePackageName, detailedForeignKeys, useCrossCuttingLibrary);
            
            // Generate JPA repository interfaces for aggregate roots
            generateJpaRepositoryInterfaces(aggregateRoots, repositoryDir, basePackageName);
            
            // Generate repository adapter implementations for aggregate roots
            generateRepositoryAdapters(aggregateRoots, adapterDir, basePackageName, projectRequest.getArtifactId(), conn, schema);
            
            // Generate persistence mapper
            Map<String, String> columnToEnumMap = new HashMap<>();
            for (String table : tables) {
                List<Map<String, String>> columns = getColumnsForTable(conn, schema, table);
                for (Map<String, String> column : columns) {
                    String comment = column.get("comment");
                    if (comment != null && !comment.isBlank()) {
                        String enumFqn = generateEnumIfApplicable(comment, column.get("name"), basePackageName, Paths.get("dummy"));
                        if (enumFqn != null) {
                            columnToEnumMap.put(table + "." + column.get("name"), enumFqn);
                        }
                    }
                }
            }
            generatePersistenceMapper(tables, mapperDir, basePackageName, conn, schema, detailedForeignKeys, projectRequest.getArtifactId(), columnToEnumMap);
            
        } catch (SQLException e) {
            throw new RuntimeException("Failed to generate persistence implementation", e);
        }
    }

    private void generateJpaEntities(List<String> tables, Connection conn, String schema, Path entityDir, String basePackageName, Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys, boolean useCrossCuttingLibrary) throws SQLException, IOException {
        for (String table : tables) {
            String entityName = snakeKebabCaseToPascalCase(table);
            String jpaEntityContent = generateJpaEntityContent(table, basePackageName, conn, schema, detailedForeignKeys, useCrossCuttingLibrary);
            Files.writeString(Paths.get(entityDir.toString(), entityName + "Entity.java"), jpaEntityContent);
        }
    }

    private void generateJpaRepositoryInterfaces(Set<String> aggregateRoots, Path repositoryDir, String basePackageName) throws IOException {
        for (String aggregateRoot : aggregateRoots) {
            String entityName = snakeKebabCaseToPascalCase(aggregateRoot);
            String jpaRepoContent = generateJpaRepositoryContent(entityName, basePackageName);
            Files.writeString(Paths.get(repositoryDir.toString(), entityName + "JpaRepository.java"), jpaRepoContent);
        }
    }

    private void generateRepositoryAdapters(Set<String> aggregateRoots, Path adapterDir, String basePackageName, String projectArtifactId, Connection conn, String schema) throws IOException, SQLException {
        for (String aggregateRoot : aggregateRoots) {
            String entityName = snakeKebabCaseToPascalCase(aggregateRoot);
            List<Map<String, String>> columns = getColumnsForTable(conn, schema, aggregateRoot);
            String adapterContent = generateRepositoryAdapterContent(entityName, basePackageName, projectArtifactId, columns);
            Files.writeString(Paths.get(adapterDir.toString(), entityName + "RepositoryImpl.java"), adapterContent);
        }
    }

    private void generatePersistenceMapper(List<String> tables, Path mapperDir, String basePackageName, Connection conn, String schema, Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys, String projectArtifactId, Map<String, String> columnToEnumMap) throws IOException, SQLException {
        String mapperContent = generatePersistenceMapperContent(tables, basePackageName, conn, schema, detailedForeignKeys, projectArtifactId, columnToEnumMap);
        String mapperName = snakeKebabCaseToPascalCase(projectArtifactId) + "PersistenceMapper";
        Files.writeString(Paths.get(mapperDir.toString(), mapperName + ".java"), mapperContent);
    }

    private String generateJpaEntityContent(String tableName, String basePackageName, Connection conn, String schema, Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys, boolean useCrossCuttingLibrary) throws SQLException {
        String entityName = snakeKebabCaseToPascalCase(tableName);
        List<Map<String, String>> columns = getColumnsForTable(conn, schema, tableName);
        Map<String, ForeignKeyInfo> tableForeignKeys = detailedForeignKeys.getOrDefault(tableName, new HashMap<>());
        
        StringBuilder fields = new StringBuilder();
        StringBuilder getters = new StringBuilder();
        StringBuilder setters = new StringBuilder();
        Set<String> imports = new TreeSet<>();
        
        imports.add("import jakarta.persistence.*;");
        imports.add("import java.util.UUID;");
        
        String extendsClass = useCrossCuttingLibrary ? "" : " extends BaseEntity";
        // Note: BaseEntity import not needed as it's in the same package
        
        // Check if table has an id column
        boolean hasIdColumn = columns.stream().anyMatch(col -> "id".equals(col.get("name")));
        
        // Add ID field if not present in database (common for junction tables)
        if (!hasIdColumn) {
            fields.append("    @Id\n");
            fields.append("    @Column(name = \"id\")\n");
            fields.append("    private UUID id;\n\n");
            
            getters.append("    public UUID getId() {\n");
            getters.append("        return id;\n");
            getters.append("    }\n\n");
            
            setters.append("    public void setId(UUID id) {\n");
            setters.append("        this.id = id;\n");
            setters.append("    }\n\n");
        }
        
        for (Map<String, String> column : columns) {
            String columnName = column.get("name");
            String camelCaseName = snakeCaseToCamelCase(columnName);
            String fieldName;
            if (JAVA_KEYWORDS.contains(camelCaseName)) {
                fieldName = firstCharToLowerCase(entityName) + capitalizeFirstLetter(camelCaseName);
            } else {
                fieldName = camelCaseName;
            }
            String javaType;
            
            if (tableForeignKeys.containsKey(columnName)) {
                javaType = "UUID";
            } else {
                String dbType = column.get("type");
                javaType = toJavaType(dbType);
                if (javaType.contains(".")) {
                    if (!javaType.startsWith("java.lang")) {
                        imports.add("import " + javaType + ";");
                    }
                    javaType = javaType.substring(javaType.lastIndexOf('.') + 1);
                }
            }
            
            // Generate field
            if (columnName.equals("id")) {
                fields.append("    @Id\n");
                fields.append("    @Column(name = \"id\")\n");
            } else {
                fields.append("    @Column(name = \"" + columnName + "\")\n");
            }
            fields.append("    private " + javaType + " " + fieldName + ";\n\n");
            
            // Generate getter
            getters.append("    public " + javaType + " get" + capitalizeFirstLetter(fieldName) + "() {\n");
            getters.append("        return " + fieldName + ";\n");
            getters.append("    }\n\n");
            
            // Generate setter  
            setters.append("    public void set" + capitalizeFirstLetter(fieldName) + "(" + javaType + " " + fieldName + ") {\n");
            setters.append("        this." + fieldName + " = " + fieldName + ";\n");
            setters.append("    }\n\n");
        }
        
        StringBuilder importStatements = new StringBuilder();
        for (String imp : imports) {
            importStatements.append(imp + "\n");
        }
        
        // Generate @Table annotation with schema if provided and not default
        String tableAnnotation;
        if (schema != null && !schema.isEmpty() && !schema.equals("public")) {
            tableAnnotation = String.format("@Table(schema = \"%s\", name = \"%s\")", schema, tableName);
        } else {
            tableAnnotation = String.format("@Table(name = \"%s\")", tableName);
        }
        
        return String.format("""
package %s.infrastructure.persistence.entity;

%s
@Entity
%s
public class %sEntity%s {

%s%s%s}
""", basePackageName, importStatements.toString(), tableAnnotation, entityName, extendsClass, fields.toString(), getters.toString(), setters.toString());
    }

    private String generateJpaRepositoryContent(String entityName, String basePackageName) {
        return String.format("""
package %s.infrastructure.persistence.repository;

import %s.infrastructure.persistence.entity.%sEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface %sJpaRepository extends JpaRepository<%sEntity, UUID> {
}
""", basePackageName, basePackageName, entityName, entityName, entityName);
    }

    private String generateRepositoryAdapterContent(String entityName, String basePackageName, String projectArtifactId, List<Map<String, String>> columns) {
        String repositoryName = entityName + "Repository";
        String jpaRepositoryName = entityName + "JpaRepository";
        String domainEntityName = entityName + "DomainEntity";
        String jpaEntityName = entityName + "Entity";
        String queryClass = entityName + "Query";
        String queryClassLower = entityName.toLowerCase(Locale.ENGLISH).replace("_", "");
        String jpaRepoVar = firstCharToLowerCase(jpaRepositoryName);
        String entityVar = firstCharToLowerCase(entityName);
        String mapperClassName = snakeKebabCaseToPascalCase(projectArtifactId) + "PersistenceMapper";
        String mapperVar = firstCharToLowerCase(mapperClassName);
        
        // Check which audit columns exist in the table
        Set<String> columnNames = columns.stream()
            .map(col -> col.get("name"))
            .collect(java.util.stream.Collectors.toSet());
        
        boolean hasCreatedBy = columnNames.contains("created_by");
        boolean hasCreatedAt = columnNames.contains("created_at");
        boolean hasUpdatedBy = columnNames.contains("updated_by");
        boolean hasUpdatedAt = columnNames.contains("updated_at");
        boolean hasDeleted = columnNames.contains("is_deleted");
        
        // Check column types for date fields to determine conversion
        String createdAtType = null;
        String updatedAtType = null;
        for (Map<String, String> column : columns) {
            String columnName = column.get("name");
            if ("created_at".equals(columnName)) {
                createdAtType = column.get("type");
            } else if ("updated_at".equals(columnName)) {
                updatedAtType = column.get("type");
            }
        }
        
        boolean createdAtNeedsConversion = createdAtType != null && 
            (createdAtType.toLowerCase().contains("timestamp")
                    && !(createdAtType.toLowerCase().contains("with time zone") || createdAtType.toLowerCase().contains("timestamptz")));
        boolean updatedAtNeedsConversion = updatedAtType != null && 
            (updatedAtType.toLowerCase().contains("timestamp")
                    && !(updatedAtType.toLowerCase().contains("with time zone") || updatedAtType.toLowerCase().contains("timestamptz")));
        
        StringBuilder content = new StringBuilder();
        content.append("package ").append(basePackageName).append(".infrastructure.persistence.adapter;\n\n");
        content.append("import ").append(basePackageName).append(".domain.applicationservice.ports.output.repository.").append(repositoryName).append(";\n");
        content.append("import ").append(basePackageName).append(".domain.core.entity.").append(domainEntityName).append(";\n");
        content.append("import ").append(basePackageName).append(".domain.core.payload.BaseQueryResponse;\n");
        content.append("import ").append(basePackageName).append(".domain.applicationservice.queries.").append(queryClassLower).append(".query.").append(queryClass).append(";\n");
        content.append("import ").append(basePackageName).append(".infrastructure.persistence.entity.").append(jpaEntityName).append(";\n");
        content.append("import ").append(basePackageName).append(".infrastructure.persistence.repository.").append(jpaRepositoryName).append(";\n");
        content.append("import ").append(basePackageName).append(".infrastructure.persistence.mapper.").append(mapperClassName).append(";\n");
        content.append("import org.springframework.stereotype.Component;\n");
        content.append("import org.springframework.transaction.annotation.Transactional;\n");
        content.append("import java.time.ZonedDateTime;\n");
        if (createdAtNeedsConversion || updatedAtNeedsConversion) {
            content.append("import java.time.LocalDateTime;\n");
        }
        content.append("import java.util.List;\n");
        content.append("import java.util.Optional;\n");
        content.append("import java.util.UUID;\n");
        content.append("import java.util.stream.Collectors;\n\n");
        
        content.append("@Component\n");
        content.append("public class ").append(entityName).append("RepositoryImpl implements ").append(repositoryName).append(" {\n\n");
        
        content.append("    private final ").append(jpaRepositoryName).append(" ").append(jpaRepoVar).append(";\n");
        content.append("    private final ").append(mapperClassName).append(" ").append(mapperVar).append(";\n\n");
        
        content.append("    public ").append(entityName).append("RepositoryImpl(").append(jpaRepositoryName).append(" ").append(jpaRepoVar).append(", ").append(mapperClassName).append(" ").append(mapperVar).append(") {\n");
        content.append("        this.").append(jpaRepoVar).append(" = ").append(jpaRepoVar).append(";\n");
        content.append("        this.").append(mapperVar).append(" = ").append(mapperVar).append(";\n");
        content.append("    }\n\n");
        
        // Create method
        content.append("    @Override\n    @Transactional\n");
        content.append("    public ").append(domainEntityName).append(" create(").append(domainEntityName).append(" domainEntity, UUID createdBy, ZonedDateTime now) {\n");
        content.append("        ").append(jpaEntityName).append(" entity = ").append(mapperVar).append(".").append(firstCharToLowerCase(domainEntityName)).append("To").append(jpaEntityName).append("(domainEntity);\n");
        if (hasCreatedBy) {
            content.append("        entity.setCreatedBy(createdBy);\n");
        }
        if (hasCreatedAt) {
            if (createdAtNeedsConversion) {
                content.append("        entity.setCreatedAt(LocalDateTime.from(now));\n");
            } else {
                content.append("        entity.setCreatedAt(now);\n");
            }
        }
        if (hasUpdatedBy) {
            content.append("        entity.setUpdatedBy(createdBy);\n");
        }
        if (hasUpdatedAt) {
            if (updatedAtNeedsConversion) {
                content.append("        entity.setUpdatedAt(LocalDateTime.from(now));\n");
            } else {
                content.append("        entity.setUpdatedAt(now);\n");
            }
        }
        content.append("        ").append(jpaEntityName).append(" saved = ").append(jpaRepoVar).append(".save(entity);\n");
        content.append("        return ").append(mapperVar).append(".").append(firstCharToLowerCase(jpaEntityName)).append("To").append(domainEntityName).append("(saved);\n");
        content.append("    }\n\n");
        
        // Update method  
        content.append("    @Override\n    @Transactional\n");
        content.append("    public ").append(domainEntityName).append(" update(").append(domainEntityName).append(" domainEntity, UUID updatedBy, ZonedDateTime now) {\n");
        content.append("        ").append(jpaEntityName).append(" entity = ").append(mapperVar).append(".").append(firstCharToLowerCase(domainEntityName)).append("To").append(jpaEntityName).append("(domainEntity);\n");
        if (hasUpdatedBy) {
            content.append("        entity.setUpdatedBy(updatedBy);\n");
        }
        if (hasUpdatedAt) {
            if (updatedAtNeedsConversion) {
                content.append("        entity.setUpdatedAt(LocalDateTime.from(now));\n");
            } else {
                content.append("        entity.setUpdatedAt(now);\n");
            }
        }
        content.append("        ").append(jpaEntityName).append(" updated = ").append(jpaRepoVar).append(".save(entity);\n");
        content.append("        return ").append(mapperVar).append(".").append(firstCharToLowerCase(jpaEntityName)).append("To").append(domainEntityName).append("(updated);\n");
        content.append("    }\n\n");
        
        // Delete method
        content.append("    @Override\n    @Transactional\n");
        content.append("    public ").append(domainEntityName).append(" delete(").append(domainEntityName).append(" domainEntity, UUID updatedBy, ZonedDateTime now) {\n");
        content.append("        ").append(jpaEntityName).append(" entity = ").append(mapperVar).append(".").append(firstCharToLowerCase(domainEntityName)).append("To").append(jpaEntityName).append("(domainEntity);\n");
        if (hasUpdatedBy) {
            content.append("        entity.setUpdatedBy(updatedBy);\n");
        }
        if (hasUpdatedAt) {
            if (updatedAtNeedsConversion) {
                content.append("        entity.setUpdatedAt(LocalDateTime.from(now));\n");
            } else {
                content.append("        entity.setUpdatedAt(now);\n");
            }
        }
        if (hasDeleted) {
            content.append("        entity.setIsDeleted(true);\n");
        }
        content.append("        ").append(jpaEntityName).append(" deleted = ").append(jpaRepoVar).append(".save(entity);\n");
        content.append("        return ").append(mapperVar).append(".").append(firstCharToLowerCase(jpaEntityName)).append("To").append(domainEntityName).append("(deleted);\n");
        content.append("    }\n\n");
        
        // GetById method
        content.append("    @Override\n");
        content.append("    public Optional<").append(domainEntityName).append("> getById(UUID id) {\n");
        content.append("        return ").append(jpaRepoVar).append(".findById(id)\n");
        content.append("            .map(").append(mapperVar).append("::").append(firstCharToLowerCase(jpaEntityName)).append("To").append(domainEntityName).append(");\n");
        content.append("    }\n\n");
        
        // Query method
        content.append("    @Override\n");
        content.append("    public BaseQueryResponse<").append(domainEntityName).append("> query(").append(queryClass).append(" query) {\n");
        content.append("        // TODO: Implement query logic based on query parameters\n");
        content.append("        List<").append(jpaEntityName).append("> entities = ").append(jpaRepoVar).append(".findAll();\n");
        content.append("        List<").append(domainEntityName).append("> domainEntities = entities.stream()\n");
        content.append("            .map(").append(mapperVar).append("::").append(firstCharToLowerCase(jpaEntityName)).append("To").append(domainEntityName).append(")\n");
        content.append("            .collect(Collectors.toList());\n");
        content.append("        \n");
        content.append("        return new BaseQueryResponse<>(domainEntities, 0, domainEntities.size(),\n");
        content.append("            domainEntities.size(), 1, true);\n");
        content.append("    }\n");
        content.append("}\n");
        
        return content.toString();
    }

    private String generatePersistenceMapperContent(List<String> tables, String basePackageName, Connection conn, String schema, Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys, String projectArtifactId, Map<String, String> columnToEnumMap) throws SQLException {
        StringBuilder methods = new StringBuilder();
        Set<String> imports = new TreeSet<>();
        
        imports.add("import org.springframework.stereotype.Component;");
        imports.add("import " + basePackageName + ".domain.core.entity.*;");
        imports.add("import " + basePackageName + ".domain.core.valueobject.*;");
        imports.add("import " + basePackageName + ".infrastructure.persistence.entity.*;");
        
        // UUID import not needed since we use value objects (InstitutionId, etc.) not raw UUID
        
        for (String table : tables) {
            String entityName = snakeKebabCaseToPascalCase(table);
            String domainEntityName = entityName + "DomainEntity";
            String jpaEntityName = entityName + "Entity";
            String idClassName = entityName + "Id";
            
            List<Map<String, String>> columns = getColumnsForTable(conn, schema, table);
            Map<String, ForeignKeyInfo> tableForeignKeys = detailedForeignKeys.getOrDefault(table, new HashMap<>());
            
            // Check if table has an id column
            boolean hasIdColumn = columns.stream().anyMatch(col -> "id".equals(col.get("name")));
            
            // Generate Entity to DomainEntity method
            methods.append("    public " + domainEntityName + " " + firstCharToLowerCase(jpaEntityName) + "To" + domainEntityName + "(" + jpaEntityName + " entity) {\n");
            methods.append("        if (entity == null) return null;\n");
            methods.append("        return new " + domainEntityName + "(\n");
            
            StringBuilder constructorParams = new StringBuilder();
            
            // Always add ID parameter first for all domain entities
            constructorParams.append("            new " + idClassName + "(entity.getId()),\n");
            
            for (Map<String, String> column : columns) {
                String columnName = column.get("name");
                String camelCaseName = snakeCaseToCamelCase(columnName);
                String fieldName;
                if (JAVA_KEYWORDS.contains(camelCaseName)) {
                    fieldName = firstCharToLowerCase(entityName) + capitalizeFirstLetter(camelCaseName);
                } else {
                    fieldName = camelCaseName;
                }
                String getterName = "get" + capitalizeFirstLetter(fieldName);
                String columnIdentifier = table + "." + columnName;
                
                if (columnName.equals("id")) {
                    // Skip ID column since we already added it at the beginning
                    continue;
                } else if (tableForeignKeys.containsKey(columnName)) {
                    // Foreign key field
                    String referencedTable = tableForeignKeys.get(columnName).getPkTableName();
                    String referencedIdClass = snakeKebabCaseToPascalCase(referencedTable) + "Id";
                    constructorParams.append("            entity." + getterName + "() != null ? new " + referencedIdClass + "(entity." + getterName + "()) : null,\n");
                } else if (columnToEnumMap.containsKey(columnIdentifier)) {
                    // Enum field - convert from Short to Enum
                    String enumFqn = columnToEnumMap.get(columnIdentifier);
                    String enumClassName = enumFqn.substring(enumFqn.lastIndexOf('.') + 1);
                    imports.add("import " + enumFqn + ";");
                    constructorParams.append("            entity." + getterName + "() != null ? " + enumClassName + ".fromValue(entity." + getterName + "().intValue()) : null,\n");
                } else {
                    constructorParams.append("            entity." + getterName + "(),\n");
                }
            }
            if (constructorParams.length() > 0) {
                constructorParams.setLength(constructorParams.length() - 2); // Remove last comma and newline
            }
            methods.append(constructorParams.toString());
            methods.append("\n        );\n    }\n\n");
            
            // Generate DomainEntity to Entity method
            methods.append("    public " + jpaEntityName + " " + firstCharToLowerCase(domainEntityName) + "To" + jpaEntityName + "(" + domainEntityName + " domainEntity) {\n");
            methods.append("        if (domainEntity == null) return null;\n");
            methods.append("        " + jpaEntityName + " entity = new " + jpaEntityName + "();\n");
            
            // Set ID first if it wasn't in the database columns (generated ID)
            if (!hasIdColumn) {
                methods.append("        entity.setId(domainEntity.getId().getValue());\n");
            }
            
            for (Map<String, String> column : columns) {
                String columnName = column.get("name");
                String camelCaseName = snakeCaseToCamelCase(columnName);
                String fieldName;
                if (JAVA_KEYWORDS.contains(camelCaseName)) {
                    fieldName = firstCharToLowerCase(entityName) + capitalizeFirstLetter(camelCaseName);
                } else {
                    fieldName = camelCaseName;
                }
                String setterName = "set" + capitalizeFirstLetter(fieldName);
                String getterName = "get" + capitalizeFirstLetter(fieldName);
                String columnIdentifier = table + "." + columnName;
                
                if (columnName.equals("id")) {
                    methods.append("        entity." + setterName + "(domainEntity.getId().getValue());\n");
                } else if (tableForeignKeys.containsKey(columnName)) {
                    // Foreign key field
                    methods.append("        entity." + setterName + "(domainEntity." + getterName + "() != null ? domainEntity." + getterName + "().getValue() : null);\n");
                } else if (columnToEnumMap.containsKey(columnIdentifier)) {
                    // Enum field - convert from Enum to Short
                    methods.append("        entity." + setterName + "(domainEntity." + getterName + "() != null ? (short) domainEntity." + getterName + "().getValue() : null);\n");
                } else {
                    methods.append("        entity." + setterName + "(domainEntity." + getterName + "());\n");
                }
            }
            methods.append("        return entity;\n    }\n\n");
        }
        
        // UUID import not needed since all methods use value objects
        
        StringBuilder importStatements = new StringBuilder();
        for (String imp : imports) {
            importStatements.append(imp + "\n");
        }
        
        String mapperClassName = snakeKebabCaseToPascalCase(projectArtifactId) + "PersistenceMapper";
        return String.format("""
package %s.infrastructure.persistence.mapper;

%s
@Component
public class %s {

%s}
""", basePackageName, importStatements.toString(), mapperClassName, methods.toString());
    }

    private void generateRestControllers(EnvironmentalCredentialsRequest envRequest, Path restDir, String basePackageName) throws IOException {
        Set<String> aggregateRoots = determineAggregateRootsFromUserSelection(envRequest.getTableEntityTypes());
        for (String aggregateRoot : aggregateRoots) {
            String entityName = snakeKebabCaseToPascalCase(aggregateRoot);
            String controllerName = entityName + "Controller";
            String controllerContent = generateRestControllerContent(entityName, aggregateRoot, basePackageName);
            Files.writeString(Paths.get(restDir.toString(), controllerName + ".java"), controllerContent);
        }
    }

    private String generateRestControllerContent(String entityName, String tableName, String basePackageName) {
        String camelCaseEntityName = firstCharToLowerCase(entityName);
        String entityPackageName = camelCaseEntityName.toLowerCase(); // All lowercase for package names
        String pluralPath = pluralize(camelCaseEntityName);
        String appServiceName = entityName + "ApplicationService";
        String appServiceVarName = firstCharToLowerCase(appServiceName);
        
        // Create parameter array to debug count
        Object[] params = {
            basePackageName, // package
            basePackageName, appServiceName, // import ApplicationService
            basePackageName, entityPackageName, entityName, // import Create Command
            basePackageName, entityPackageName, entityName, // import Create Response
            basePackageName, entityPackageName, entityName, // import Update Command
            basePackageName, entityPackageName, entityName, // import Update Response
            basePackageName, entityPackageName, entityName, // import Delete Response
            basePackageName, entityPackageName, entityName, // import GetById Response
            basePackageName, entityPackageName, entityName, // import Query
            basePackageName, entityPackageName, entityName, // import Query Response
            basePackageName, // import BaseQueryResponse
            basePackageName, // import ResultObject
            pluralPath, entityName + "Controller", // RequestMapping and class name
            appServiceName, appServiceVarName, // field declaration
            entityName + "Controller", appServiceName, appServiceVarName, // constructor name and parameters  
            appServiceVarName, appServiceVarName, // constructor assignment
            entityName, entityName, entityName, // create method
            camelCaseEntityName, entityName, // create method log
            appServiceVarName, entityName, // create method call
            entityName, // query method response type
            entityName, camelCaseEntityName, // query method parameters
            pluralPath, camelCaseEntityName, // query method log
            appServiceVarName, camelCaseEntityName, // query method call
            entityName, // getById method
            camelCaseEntityName, // getById method log
            appServiceVarName, // getById method call
            entityName, // update method
            entityName, entityName, // update method parameters
            camelCaseEntityName, entityName, // update method log
            entityName, // update method setId
            appServiceVarName, entityName, // update method call
            entityName, // delete method
            camelCaseEntityName, // delete method log
            appServiceVarName // delete method call
        };
        
        return String.format("""
package %s.application.rest;

import %s.domain.applicationservice.ports.input.service.%s;
import %s.domain.applicationservice.commands.%s.create.Create%sCommand;
import %s.domain.applicationservice.commands.%s.create.Create%sResponse;
import %s.domain.applicationservice.commands.%s.update.Update%sCommand;
import %s.domain.applicationservice.commands.%s.update.Update%sResponse;
import %s.domain.applicationservice.commands.%s.delete.Delete%sResponse;
import %s.domain.applicationservice.queries.%s.getbyid.GetById%sResponse;
import %s.domain.applicationservice.queries.%s.query.%sQuery;
import %s.domain.applicationservice.queries.%s.query.%sQueryResponse;
import %s.domain.core.payload.BaseQueryResponse;
import %s.application.payload.ResultObject;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping(value = "/api/v1/%s")
public class %s {

    private final %s %s;

    public %s(%s %s) {
        this.%s = %s;
    }

    @PostMapping
    public ResponseEntity<ResultObject<Create%sResponse>> create(@RequestBody Create%sCommand create%sCommand) {
        log.info("Creating %s with data: {}", create%sCommand);
        return ResponseEntity.ok(ResultObject.success(%s.create(create%sCommand)));
    }

    @PostMapping("query")
    public ResponseEntity<ResultObject<BaseQueryResponse<%sQueryResponse>>> query(
            @RequestBody %sQuery %sQuery) {
        log.info("Query %s : {}", %sQuery);
        return ResponseEntity.ok(ResultObject.success(%s.query(%sQuery)));
    }

    @GetMapping("{id}")
    public ResponseEntity<ResultObject<GetById%sResponse>> getById(@PathVariable @NotNull @NotEmpty String id) {
        log.info("Getting %s by id: {}", id);
        return ResponseEntity.ok(ResultObject.success(%s.getById(UUID.fromString(id))));
    }

    @PutMapping("{id}")
    public ResponseEntity<ResultObject<Update%sResponse>> update(@PathVariable @NotNull @NotEmpty String id,
                                                                             @RequestBody Update%sCommand update%sCommand) {
        log.info("Updating %s by id: {} with data: {}", id, update%sCommand);
        update%sCommand.setId(UUID.fromString(id));
        return ResponseEntity.ok(ResultObject.success(%s.update(update%sCommand)));
    }

    @DeleteMapping("{id}")
    public ResponseEntity<ResultObject<Delete%sResponse>> delete(@PathVariable @NotNull @NotEmpty String id,
                                                                             @RequestHeader("X-Updated-By") @NotNull @NotEmpty String updatedBy) {
        log.info("Deleting %s by id: {}", id);
        return ResponseEntity.ok(ResultObject.success(%s.delete(UUID.fromString(id), UUID.fromString(updatedBy))));
    }
}
""", params);
    }

    private void generateApplicationServiceInterface(String tableName, String basePackageName, Path appServiceMainJava) throws IOException {
        String entityName = snakeKebabCaseToPascalCase(tableName);
        String interfaceName = entityName + "ApplicationService";
        String camelCaseEntityName = firstCharToLowerCase(entityName);
        String entityPackageName = camelCaseEntityName.toLowerCase(); // All lowercase for package names
        
        Path interfaceDir = Paths.get(appServiceMainJava.toString(), "ports", "input", "service");
        Files.createDirectories(interfaceDir);
        
        String interfaceContent = String.format("""
package %s.domain.applicationservice.ports.input.service;

import %s.domain.applicationservice.commands.%s.create.Create%sCommand;
import %s.domain.applicationservice.commands.%s.create.Create%sResponse;
import %s.domain.applicationservice.commands.%s.update.Update%sCommand;
import %s.domain.applicationservice.commands.%s.update.Update%sResponse;
import %s.domain.applicationservice.commands.%s.delete.Delete%sResponse;
import %s.domain.applicationservice.queries.%s.getbyid.GetById%sResponse;
import %s.domain.applicationservice.queries.%s.query.%sQuery;
import %s.domain.applicationservice.queries.%s.query.%sQueryResponse;
import %s.domain.core.payload.BaseQueryResponse;
import jakarta.validation.Valid;

import java.util.UUID;

public interface %s {

    Create%sResponse create(@Valid Create%sCommand create%sCommand);

    Update%sResponse update(@Valid Update%sCommand update%sCommand);

    Delete%sResponse delete(UUID id, UUID updatedBy);

    GetById%sResponse getById(UUID id);

    BaseQueryResponse<%sQueryResponse> query(@Valid %sQuery %sQuery);
}
""",
            basePackageName, // package
            basePackageName, entityPackageName, entityName, // import Create Command
            basePackageName, entityPackageName, entityName, // import Create Response
            basePackageName, entityPackageName, entityName, // import Update Command
            basePackageName, entityPackageName, entityName, // import Update Response
            basePackageName, entityPackageName, entityName, // import Delete Response
            basePackageName, entityPackageName, entityName, // import GetById Response
            basePackageName, entityPackageName, entityName, // import Query
            basePackageName, entityPackageName, entityName, // import Query Response
            basePackageName, // import BaseQueryResponse
            interfaceName, // interface name
            entityName, entityName, entityName, // create method
            entityName, entityName, entityName, // update method
            entityName, // delete method
            entityName, // getById method
            entityName, entityName, camelCaseEntityName // query method
        );
        
        Files.writeString(Paths.get(interfaceDir.toString(), interfaceName + ".java"), interfaceContent);
    }

    private void generateApplicationServiceImplementation(String tableName, String basePackageName, Path appServiceMainJava, String domainMapperName, ProjectRequest projectRequest) throws IOException {
        String entityName = snakeKebabCaseToPascalCase(tableName);
        String implName = entityName + "ApplicationServiceImpl";
        String interfaceName = entityName + "ApplicationService";
        String camelCaseEntityName = firstCharToLowerCase(entityName);
        String entityPackageName = camelCaseEntityName.toLowerCase(); // All lowercase for package names
        
        Path implDir = Paths.get(appServiceMainJava.toString(), "ports", "input", "service", "impl");
        Files.createDirectories(implDir);
        
        String implContent = String.format("""
package %s.domain.applicationservice.ports.input.service.impl;

import %s.domain.applicationservice.commands.%s.create.%sCreateCommandHandler;
import %s.domain.applicationservice.commands.%s.create.Create%sCommand;
import %s.domain.applicationservice.commands.%s.create.Create%sResponse;
import %s.domain.applicationservice.commands.%s.update.%sUpdateCommandHandler;
import %s.domain.applicationservice.commands.%s.update.Update%sCommand;
import %s.domain.applicationservice.commands.%s.update.Update%sResponse;
import %s.domain.applicationservice.commands.%s.delete.%sDeleteCommandHandler;
import %s.domain.applicationservice.commands.%s.delete.Delete%sResponse;
import %s.domain.applicationservice.queries.%s.getbyid.%sGetByIdQueryHandler;
import %s.domain.applicationservice.queries.%s.getbyid.GetById%sResponse;
import %s.domain.applicationservice.queries.%s.query.%sQueryHandler;
import %s.domain.applicationservice.queries.%s.query.%sQuery;
import %s.domain.applicationservice.queries.%s.query.%sQueryResponse;
import %s.domain.applicationservice.mapper.%s;
import %s.domain.applicationservice.ports.input.service.%s;
import %s.domain.core.entity.%sDomainEntity;
import %s.domain.core.payload.BaseQueryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

@Slf4j
@Validated
@Service
public class %s implements %s {

    private final %sCreateCommandHandler %sCreateCommandHandler;
    private final %sUpdateCommandHandler %sUpdateCommandHandler;
    private final %sDeleteCommandHandler %sDeleteCommandHandler;
    private final %sGetByIdQueryHandler %sGetByIdQueryHandler;
    private final %sQueryHandler %sQueryHandler;
    private final %s %s;

    public %s(%sCreateCommandHandler %sCreateCommandHandler,
                                %sUpdateCommandHandler %sUpdateCommandHandler,
                                %sDeleteCommandHandler %sDeleteCommandHandler,
                                %sGetByIdQueryHandler %sGetByIdQueryHandler,
                                %sQueryHandler %sQueryHandler,
                                %s %s) {
        this.%sCreateCommandHandler = %sCreateCommandHandler;
        this.%sUpdateCommandHandler = %sUpdateCommandHandler;
        this.%sDeleteCommandHandler = %sDeleteCommandHandler;
        this.%sGetByIdQueryHandler = %sGetByIdQueryHandler;
        this.%sQueryHandler = %sQueryHandler;
        this.%s = %s;
    }

    @Override
    public Create%sResponse create(Create%sCommand create%sCommand) {
        %sDomainEntity created%s = %sCreateCommandHandler.create%s(create%sCommand);
        return %s.%sDomainEntityToCreate%sResponse(created%s);
    }

    @Override
    public Update%sResponse update(Update%sCommand update%sCommand) {
        %sDomainEntity updated%s = %sUpdateCommandHandler.update%s(update%sCommand);
        return %s.%sDomainEntityToUpdate%sResponse(updated%s);
    }

    @Override
    public Delete%sResponse delete(UUID id, UUID updatedBy) {
        return %sDeleteCommandHandler.delete(id, updatedBy);
    }

    @Override
    public GetById%sResponse getById(UUID id) {
        return %sGetByIdQueryHandler.getById(id);
    }

    @Override
    public BaseQueryResponse<%sQueryResponse> query(%sQuery %sQuery) {
        return %sQueryHandler.query(%sQuery);
    }
}
""",
            basePackageName, // package
            basePackageName, entityPackageName, entityName, // import Create Handler
            basePackageName, entityPackageName, entityName, // import Create Command
            basePackageName, entityPackageName, entityName, // import Create Response
            basePackageName, entityPackageName, entityName, // import Update Handler
            basePackageName, entityPackageName, entityName, // import Update Command
            basePackageName, entityPackageName, entityName, // import Update Response
            basePackageName, entityPackageName, entityName, // import Delete Handler
            basePackageName, entityPackageName, entityName, // import Delete Response
            basePackageName, entityPackageName, entityName, // import GetById Handler
            basePackageName, entityPackageName, entityName, // import GetById Response
            basePackageName, entityPackageName, entityName, // import Query Handler
            basePackageName, entityPackageName, entityName, // import Query
            basePackageName, entityPackageName, entityName, // import Query Response
            basePackageName, domainMapperName, // import Mapper
            basePackageName, interfaceName, // import Interface
            basePackageName, entityName, // import Domain Entity
            basePackageName, // import BaseQueryResponse
            implName, interfaceName, // class declaration
            entityName, camelCaseEntityName, // Create handler field
            entityName, camelCaseEntityName, // Update handler field
            entityName, camelCaseEntityName, // Delete handler field
            entityName, camelCaseEntityName, // GetById handler field
            entityName, camelCaseEntityName, // Query handler field
            domainMapperName, firstCharToLowerCase(domainMapperName), // Mapper field
            implName, // constructor name
            entityName, camelCaseEntityName, // constructor param 1
            entityName, camelCaseEntityName, // constructor param 2
            entityName, camelCaseEntityName, // constructor param 3
            entityName, camelCaseEntityName, // constructor param 4
            entityName, camelCaseEntityName, // constructor param 5
            domainMapperName, firstCharToLowerCase(domainMapperName), // constructor param 6
            camelCaseEntityName, camelCaseEntityName, // constructor assignment 1
            camelCaseEntityName, camelCaseEntityName, // constructor assignment 2
            camelCaseEntityName, camelCaseEntityName, // constructor assignment 3
            camelCaseEntityName, camelCaseEntityName, // constructor assignment 4
            camelCaseEntityName, camelCaseEntityName, // constructor assignment 5
            firstCharToLowerCase(domainMapperName), firstCharToLowerCase(domainMapperName), // constructor assignment 6
            entityName, entityName, entityName, // create method
            entityName, entityName, camelCaseEntityName, entityName, entityName, // create method body
            firstCharToLowerCase(domainMapperName), camelCaseEntityName, entityName, entityName, // create method mapper call
            entityName, entityName, entityName, // update method
            entityName, entityName, camelCaseEntityName, entityName, entityName, // update method body
            firstCharToLowerCase(domainMapperName), camelCaseEntityName, entityName, entityName, // update method mapper call
            entityName, // delete method
            camelCaseEntityName, // delete method body
            entityName, // getById method
            camelCaseEntityName, // getById method body
            entityName, entityName, camelCaseEntityName, // query method
            camelCaseEntityName, camelCaseEntityName // query method body
        );
        
        Files.writeString(Paths.get(implDir.toString(), implName + ".java"), implContent);
    }

    private void generateHttpRequestFiles(EnvironmentalCredentialsRequest environmentalCredentialsRequest, Path httpDir, String basePackageName) throws IOException, SQLException {
        try (Connection conn = DriverManager.getConnection(
                environmentalCredentialsRequest.getLocalDatasourceUrl(),
                environmentalCredentialsRequest.getLocalDatasourceUsername(),
                environmentalCredentialsRequest.getLocalDatasourcePassword())) {

            String schema = environmentalCredentialsRequest.getSelectedSchema();
            List<String> tables = getTables(conn, schema);
            Map<String, Set<String>> foreignKeys = getForeignKeys(conn, schema);
            Set<String> aggregateRoots = determineAggregateRootsFromUserSelection(environmentalCredentialsRequest.getTableEntityTypes());
            
            for (String tableName : aggregateRoots) {
                String entityNameLower = tableName.toLowerCase(Locale.ENGLISH).replace("_", "");
                String httpFileName = entityNameLower + ".http";
                String httpContent = generateHttpFileContent(tableName, basePackageName, environmentalCredentialsRequest.getServerPort(), conn, schema);
                Files.writeString(Paths.get(httpDir.toString(), httpFileName), httpContent);
            }
        }
    }

    private String generateHttpFileContent(String tableName, String basePackageName, String serverPort, Connection conn, String schema) throws SQLException {
        String entityNameLower = tableName.toLowerCase(Locale.ENGLISH).replace("_", "");
        String entityName = snakeKebabCaseToPascalCase(tableName);
        String entityNameLowerPlural = pluralize(entityNameLower);
        
        // Get columns to generate proper request bodies
        List<Map<String, String>> columns = getColumnsForTable(conn, schema, tableName);
        
        // Get additional data needed for accurate request body generation
        Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys = getDetailedForeignKeys(conn, schema);
        Map<String, String> columnToEnumMap = scanForEnumColumns(conn, schema);
        
        String createRequestBody = generateCreateRequestBody(columns, tableName, detailedForeignKeys, columnToEnumMap);
        String updateRequestBody = generateUpdateRequestBody(columns, tableName, detailedForeignKeys, columnToEnumMap);
        String queryRequestBody = generateQueryRequestBody(columns);
        
        return String.format("""
### Create %s
POST http://localhost:%s/api/v1/%s
Content-Type: application/json

%s

### Get %s by ID
GET http://localhost:%s/api/v1/%s/123e4567-e89b-12d3-a456-426614174000

### Update %s
PUT http://localhost:%s/api/v1/%s/123e4567-e89b-12d3-a456-426614174000
Content-Type: application/json

%s

### Query %s
POST http://localhost:%s/api/v1/%s/query
Content-Type: application/json

%s

### Delete %s
DELETE http://localhost:%s/api/v1/%s/123e4567-e89b-12d3-a456-426614174000

### LOCALHOST ALTERNATIVE (with pagination)
GET http://localhost:%s/api/v1/%s?page=0&size=10&sort=id,asc

### PRODUCTION ENVIRONMENT  
GET https://your-domain.com/api/v1/%s/123e4567-e89b-12d3-a456-426614174000

### WITH QUERY PARAMETERS (pagination and sorting)
GET http://localhost:%s/api/v1/%s?page=0&size=10&sort=name,asc
""", 
            entityName, serverPort, entityNameLowerPlural,    // Create %s, POST localhost:%s/api/v1/%s
            createRequestBody,                                // Request body for create
            entityName, serverPort, entityNameLowerPlural,    // Get %s by ID, GET localhost:%s/api/v1/%s/...
            entityName, serverPort, entityNameLowerPlural,    // Update %s, PUT localhost:%s/api/v1/%s/...
            updateRequestBody,                                // Request body for update
            entityName, serverPort, entityNameLowerPlural,    // Query %s, POST localhost:%s/api/v1/%s/query
            queryRequestBody,                                 // Request body for query
            entityName, serverPort, entityNameLowerPlural,    // Delete %s, DELETE localhost:%s/api/v1/%s/...
            serverPort, entityNameLowerPlural,                // GET localhost:%s/api/v1/%s?page=0...
            entityNameLowerPlural,                            // GET https://your-domain.com/api/v1/%s/...
            serverPort, entityNameLowerPlural                 // GET localhost:%s/api/v1/%s?page=0...
        );
    }

    private String generateCreateRequestBody(List<Map<String, String>> columns, String currentTable, Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys, Map<String, String> columnToEnumMap) {
        if (columns == null || columns.isEmpty()) {
            return "{\n  \"createdBy\": \"0197b76c-7cce-71be-a0f2-949c6f4ff9f7\",\n  \"name\": \"Sample Name\",\n  \"description\": \"Sample description\"\n}";
        }
        
        StringBuilder body = new StringBuilder("{\n");
        boolean first = true;
        
        Map<String, ForeignKeyInfo> tableForeignKeys = detailedForeignKeys != null ? 
            detailedForeignKeys.getOrDefault(currentTable, new HashMap<>()) : new HashMap<>();
        
        // Check if createdBy field already exists in database columns
        boolean hasCreatedByColumn = columns.stream()
            .anyMatch(column -> "created_by".equals(column.get("name")));
        
        // Add createdBy field only if it doesn't exist in database columns
        if (!hasCreatedByColumn) {
            body.append("  \"createdBy\": \"0197b76c-7cce-71be-a0f2-949c6f4ff9f7\"");
            first = false;
        }
        
        for (Map<String, String> column : columns) {
            String columnName = column.get("name") != null ? column.get("name") : column.get("COLUMN_NAME");
            String dataType = column.get("type") != null ? column.get("type") : column.get("DATA_TYPE");
            
            // Skip null columns or auto-generated columns
            if (columnName == null || dataType == null) {
                continue;
            }
            
            // Skip ID column like in Command DTO generation
            if (columnName.equalsIgnoreCase("id")) {
                continue;
            }
            
            if (!first) {
                body.append(",\n");
            }
            first = false;
            
            String camelCaseColumnName = snakeCaseToCamelCase(columnName);
            
            // Handle Java keywords like in Command DTO generation
            if (JAVA_KEYWORDS.contains(camelCaseColumnName)) {
                // This would need the entityName, but for HTTP requests we'll keep it simple
                camelCaseColumnName = camelCaseColumnName + "Value";
            }
            
            String columnIdentifier = currentTable + "." + columnName;
            String sampleValue;
            
            // Check if this column is an enum
            if (columnToEnumMap != null && columnToEnumMap.containsKey(columnIdentifier)) {
                sampleValue = generateEnumSampleValue(columnName);
            }
            // Check if this column is a foreign key
            else if (tableForeignKeys.containsKey(columnName)) {
                sampleValue = "\"0197b76c-7cce-71be-a0f2-949c6f4ff9f7\"";
            }
            // Generate sample value based on data type and column name
            else {
                sampleValue = generateSampleValue(dataType, columnName);
            }
            
            body.append("  \"").append(camelCaseColumnName).append("\": ").append(sampleValue);
        }
        
        // If no columns were added, add default fallback
        if (first) {
            body.append("  \"name\": \"Sample Name\",\n");
            body.append("  \"description\": \"Sample description\"");
        }
        
        body.append("\n}");
        return body.toString();
    }

    private String generateUpdateRequestBody(List<Map<String, String>> columns, String currentTable, Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys, Map<String, String> columnToEnumMap) {
        if (columns == null || columns.isEmpty()) {
            return "{\n  \"id\": \"0197b76c-7cce-71be-a0f2-949c6f4ff9f7\",\n  \"name\": \"Updated Name\",\n  \"description\": \"Updated description\"\n}";
        }
        
        StringBuilder body = new StringBuilder("{\n");
        boolean first = true;
        
        Map<String, ForeignKeyInfo> tableForeignKeys = detailedForeignKeys != null ? 
            detailedForeignKeys.getOrDefault(currentTable, new HashMap<>()) : new HashMap<>();
        
        for (Map<String, String> column : columns) {
            String columnName = column.get("name") != null ? column.get("name") : column.get("COLUMN_NAME");
            String dataType = column.get("type") != null ? column.get("type") : column.get("DATA_TYPE");
            
            // Skip null columns or auto-generated columns except ID for updates
            if (columnName == null || dataType == null) {
                continue;
            }
            
            // Skip created columns but include ID for updates
            if (columnName.toLowerCase().contains("created") || 
                columnName.toLowerCase().contains("version")) {
                continue;
            }
            
            if (!first) {
                body.append(",\n");
            }
            first = false;
            
            String camelCaseColumnName = snakeCaseToCamelCase(columnName);
            
            // Handle Java keywords like in Command DTO generation
            if (JAVA_KEYWORDS.contains(camelCaseColumnName)) {
                camelCaseColumnName = camelCaseColumnName + "Value";
            }
            
            String columnIdentifier = currentTable + "." + columnName;
            String sampleValue;
            
            if (columnName.equalsIgnoreCase("id")) {
                sampleValue = "\"0197b76c-7cce-71be-a0f2-949c6f4ff9f7\"";
            }
            // Check if this column is an enum
            else if (columnToEnumMap != null && columnToEnumMap.containsKey(columnIdentifier)) {
                sampleValue = generateEnumSampleValue(columnName);
            }
            // Check if this column is a foreign key
            else if (tableForeignKeys.containsKey(columnName)) {
                sampleValue = "\"0197b76c-7cce-71be-a0f2-949c6f4ff9f7\"";
            }
            // Generate sample value based on data type and column name
            else {
                sampleValue = generateSampleValue(dataType, columnName);
            }
            
            body.append("  \"").append(camelCaseColumnName).append("\": ").append(sampleValue);
        }
        
        // If no columns were added, add default fallback
        if (first) {
            body.append("  \"id\": \"0197b76c-7cce-71be-a0f2-949c6f4ff9f7\",\n");
            body.append("  \"name\": \"Updated Name\",\n");
            body.append("  \"description\": \"Updated description\"");
        }
        
        body.append("\n}");
        return body.toString();
    }

    private String generateQueryRequestBody(List<Map<String, String>> columns) {
        if (columns == null || columns.isEmpty()) {
            return "{\n  \"name\": \"Search criteria\",\n  \"description\": \"Sample query\"\n}";
        }
        
        StringBuilder body = new StringBuilder("{\n");
        boolean first = true;
        int fieldCount = 0;
        
        for (Map<String, String> column : columns) {
            String columnName = column.get("COLUMN_NAME");
            String dataType = column.get("DATA_TYPE");
            
            // Skip null columns, auto-generated columns and only include a few fields for query
            if (columnName == null || dataType == null) {
                continue;
            }
            
            if (columnName.equalsIgnoreCase("id") || 
                columnName.toLowerCase().contains("created") || 
                columnName.toLowerCase().contains("updated") ||
                columnName.toLowerCase().contains("version") ||
                fieldCount >= 3) {
                continue;
            }
            
            if (!first) {
                body.append(",\n");
            }
            first = false;
            fieldCount++;
            
            String camelCaseColumnName = snakeCaseToCamelCase(columnName);
            String sampleValue = generateSampleValue(dataType, columnName);
            body.append("  \"").append(camelCaseColumnName).append("\": ").append(sampleValue);
        }
        
        // If no columns were added, add default fallback
        if (first) {
            body.append("  \"name\": \"Search criteria\",\n");
            body.append("  \"description\": \"Sample query\"");
        }
        
        body.append("\n}");
        return body.toString();
    }

    private Map<String, String> scanForEnumColumns(Connection conn, String schema) throws SQLException {
        Map<String, String> columnToEnumMap = new HashMap<>();
        List<String> tables = getTables(conn, schema);
        
        for (String table : tables) {
            List<Map<String, String>> columns = getColumnsForTable(conn, schema, table);
            for (Map<String, String> column : columns) {
                String comment = column.get("comment");
                if (comment != null && !comment.isBlank() && comment.startsWith("Enum:")) {
                    // Extract enum class name from comment format: Enum:EnumClass{...}
                    String enumName = comment.substring(5); // Remove "Enum:" prefix
                    if (enumName.contains("{")) {
                        enumName = enumName.substring(0, enumName.indexOf("{"));
                    }
                    // Create fully qualified enum class name
                    String enumFqn = "com.muratagin.dddgenerator.domain.core.valueobject." + enumName;
                    columnToEnumMap.put(table + "." + column.get("name"), enumFqn);
                }
            }
        }
        
        return columnToEnumMap;
    }

    private String generateEnumSampleValue(String columnName) {
        String lowerColumnName = columnName.toLowerCase();
        
        // Generate appropriate enum values based on column name
        if (lowerColumnName.contains("type")) {
            return "0";  // First enum value (0-indexed)
        } else if (lowerColumnName.contains("status")) {
            return "0";
        } else if (lowerColumnName.contains("level")) {
            return "0";
        } else if (lowerColumnName.contains("stage")) {
            return "0";
        } else {
            return "0";  // Default to first enum value
        }
    }

    private String generateSampleValue(String dataType, String columnName) {
        if (dataType == null || columnName == null) {
            return "\"Sample Value\"";
        }
        
        String lowerColumnName = columnName.toLowerCase();
        
        if (dataType.contains("VARCHAR") || dataType.contains("TEXT") || dataType.contains("CHAR")) {
            if (lowerColumnName.contains("email")) {
                return "\"user@example.com\"";
            } else if (lowerColumnName.contains("name")) {
                // Generate more realistic sample names based on context
                if (lowerColumnName.contains("first")) {
                    return "\"John\"";
                } else if (lowerColumnName.contains("last")) {
                    return "\"Doe\"";
                } else if (lowerColumnName.contains("company") || lowerColumnName.contains("organization")) {
                    return "\"Acme Corp\"";
                } else if (lowerColumnName.contains("institution")) {
                    return "\"Hacettepe\"";
                } else {
                    return "\"Sample " + snakeKebabCaseToPascalCase(columnName) + "\"";
                }
            } else if (lowerColumnName.contains("description")) {
                return "\"Sample description\"";
            } else if (lowerColumnName.contains("code")) {
                return "\"SAMPLE_CODE\"";
            } else if (lowerColumnName.contains("country")) {
                return "\"TR\"";
            } else if (lowerColumnName.contains("city")) {
                return "\"Istanbul\"";
            } else if (lowerColumnName.contains("address")) {
                return "\"123 Main Street\"";
            } else if (lowerColumnName.contains("phone")) {
                return "\"+90-555-123-4567\"";
            } else {
                return "\"Sample Value\"";
            }
        } else if (dataType.contains("INT") || dataType.contains("NUMERIC") || dataType.contains("DECIMAL")) {
            if (lowerColumnName.contains("price") || lowerColumnName.contains("amount") || lowerColumnName.contains("cost")) {
                return "99.99";
            } else if (lowerColumnName.contains("year") || lowerColumnName.contains("established")) {
                return "2025";
            } else if (lowerColumnName.contains("age")) {
                return "25";
            } else if (lowerColumnName.contains("count") || lowerColumnName.contains("quantity")) {
                return "10";
            } else if (lowerColumnName.contains("level") || lowerColumnName.contains("accreditation")) {
                return "0";  // For enum-like integer fields
            } else {
                return "123";
            }
        } else if (dataType.contains("BOOLEAN") || dataType.contains("BIT")) {
            if (lowerColumnName.contains("deleted") || lowerColumnName.contains("inactive")) {
                return "true";  // Match the example given
            } else {
                return "true";
            }
        } else if (dataType.contains("DATE") || dataType.contains("TIME")) {
            return "\"2024-01-01T10:00:00\"";
        } else if (dataType.contains("UUID")) {
            return "\"0197b76c-7cce-71be-a0f2-949c6f4ff9f7\"";
        } else {
            return "\"Sample Value\"";
        }
    }

    private void generatePostmanCollection(EnvironmentalCredentialsRequest environmentalCredentialsRequest, Path postmanDir, String basePackageName, ProjectRequest projectRequest) throws IOException, SQLException {
        try (Connection conn = DriverManager.getConnection(
                environmentalCredentialsRequest.getLocalDatasourceUrl(),
                environmentalCredentialsRequest.getLocalDatasourceUsername(),
                environmentalCredentialsRequest.getLocalDatasourcePassword())) {

            String schema = environmentalCredentialsRequest.getSelectedSchema();
            List<String> tables = getTables(conn, schema);
            Map<String, Set<String>> foreignKeys = getForeignKeys(conn, schema);
            Set<String> aggregateRoots = determineAggregateRootsFromUserSelection(environmentalCredentialsRequest.getTableEntityTypes());
            String postmanContent = generatePostmanCollectionContent(aggregateRoots, projectRequest, environmentalCredentialsRequest.getServerPort(), conn, schema);
            String fileName = projectRequest.getArtifactId() + ".postman_collection.json";
            Files.writeString(Paths.get(postmanDir.toString(), fileName), postmanContent);
        }
    }

    private String generatePostmanCollectionContent(Set<String> aggregateRoots, ProjectRequest projectRequest, String serverPort, Connection conn, String schema) throws SQLException {
        StringBuilder items = new StringBuilder();
        boolean first = true;
        
        for (String tableName : aggregateRoots) {
            if (!first) {
                items.append(",\n");
            }
            first = false;
            
            String entityName = snakeKebabCaseToPascalCase(tableName);
            String entityNameLower = tableName.toLowerCase(Locale.ENGLISH).replace("_", "");
            String entityNameLowerPlural = pluralize(entityNameLower);
            
            // Get columns to generate proper request bodies
            List<Map<String, String>> columns = getColumnsForTable(conn, schema, tableName);
            
            // Get additional data needed for accurate request body generation
            Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys = getDetailedForeignKeys(conn, schema);
            Map<String, String> columnToEnumMap = scanForEnumColumns(conn, schema);
            
            String createRequestBody = generateCreateRequestBody(columns, tableName, detailedForeignKeys, columnToEnumMap).replace("\"", "\\\"").replace("\n", "\\n");
            String updateRequestBody = generateUpdateRequestBody(columns, tableName, detailedForeignKeys, columnToEnumMap).replace("\"", "\\\"").replace("\n", "\\n");
            String queryRequestBody = generateQueryRequestBody(columns).replace("\"", "\\\"").replace("\n", "\\n");
            
            items.append(String.format("""
        {
            "name": "%s",
            "item": [
                {
                    "name": "Commands",
                    "item": [
                        {
                            "name": "Create",
                            "request": {
                                "method": "POST",
                                "header": [
                                    {
                                        "key": "Content-Type",
                                        "value": "application/json"
                                    }
                                ],
                                "body": {
                                    "mode": "raw",
                                    "raw": "%s",
                                    "options": {
                                        "raw": {
                                            "language": "json"
                                        }
                                    }
                                },
                                "url": {
                                    "raw": "http://localhost:%s/api/v1/%s",
                                    "protocol": "http",
                                    "host": ["localhost"],
                                    "port": "%s",
                                    "path": ["api", "v1", "%s"]
                                }
                            }
                        },
                        {
                            "name": "Update",
                            "request": {
                                "method": "PUT",
                                "header": [
                                    {
                                        "key": "Content-Type",
                                        "value": "application/json"
                                    }
                                ],
                                "body": {
                                    "mode": "raw",
                                    "raw": "%s",
                                    "options": {
                                        "raw": {
                                            "language": "json"
                                        }
                                    }
                                },
                                "url": {
                                    "raw": "http://localhost:%s/api/v1/%s/{{%sId}}",
                                    "protocol": "http",
                                    "host": ["localhost"],
                                    "port": "%s",
                                    "path": ["api", "v1", "%s", "{{%sId}}"]
                                }
                            }
                        },
                        {
                            "name": "Delete",
                            "request": {
                                "method": "DELETE",
                                "header": [],
                                "url": {
                                    "raw": "http://localhost:%s/api/v1/%s/{{%sId}}",
                                    "protocol": "http",
                                    "host": ["localhost"],
                                    "port": "%s",
                                    "path": ["api", "v1", "%s", "{{%sId}}"]
                                }
                            }
                        }
                    ]
                },
                {
                    "name": "Queries",
                    "item": [
                        {
                            "name": "Get By Id",
                            "request": {
                                "method": "GET",
                                "header": [],
                                "url": {
                                    "raw": "http://localhost:%s/api/v1/%s/{{%sId}}",
                                    "protocol": "http",
                                    "host": ["localhost"],
                                    "port": "%s",
                                    "path": ["api", "v1", "%s", "{{%sId}}"]
                                }
                            }
                        },
                        {
                            "name": "Query",
                            "request": {
                                "method": "POST",
                                "header": [
                                    {
                                        "key": "Content-Type",
                                        "value": "application/json"
                                    }
                                ],
                                "body": {
                                    "mode": "raw",
                                    "raw": "%s",
                                    "options": {
                                        "raw": {
                                            "language": "json"
                                        }
                                    }
                                },
                                "url": {
                                    "raw": "http://localhost:%s/api/v1/%s/query",
                                    "protocol": "http",
                                    "host": ["localhost"],
                                    "port": "%s",
                                    "path": ["api", "v1", "%s", "query"]
                                }
                            }
                        }
                    ]
                }
            ]
        }""", 
                entityName,                                    // 1. "name": "%s"
                createRequestBody,                             // 2. Create request body
                serverPort, entityNameLowerPlural,             // 3-4. localhost:%s/api/v1/%s
                serverPort, entityNameLowerPlural,             // 5-6. port + path
                updateRequestBody,                             // 7. Update request body
                serverPort, entityNameLowerPlural, entityNameLower, // 8-10. localhost:%s/api/v1/%s/{{%sId}}
                serverPort, entityNameLowerPlural, entityNameLower, // 11-13. port + path + {{%sId}}
                serverPort, entityNameLowerPlural, entityNameLower, // 14-16. DELETE localhost:%s/api/v1/%s/{{%sId}}
                serverPort, entityNameLowerPlural, entityNameLower, // 17-19. port + path + {{%sId}}
                serverPort, entityNameLowerPlural, entityNameLower, // 20-22. GET localhost:%s/api/v1/%s/{{%sId}}
                serverPort, entityNameLowerPlural, entityNameLower, // 23-25. port + path + {{%sId}}
                queryRequestBody,                              // 26. Query request body
                serverPort, entityNameLowerPlural,             // 27-28. localhost:%s/api/v1/%s/query
                serverPort, entityNameLowerPlural              // 29-30. port + path
            ));
        }
        
        return String.format("""
{
    "info": {
        "_postman_id": "%s",
        "name": "%s API Collection",
        "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
        "_exporter_id": "generated"
    },
    "item": [
%s
    ],
    "variable": [
        {
            "key": "baseUrl",
            "value": "http://localhost:8080"
        }
    ]
}""", java.util.UUID.randomUUID().toString(), projectRequest.getArtifactId(), items.toString());
    }
}
